package org.quarkpress.persistence.deployment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;
import org.quarkpress.core.runtime.SiteBinding;
import org.quarkpress.persistence.SiteScopedNativeSql;

/**
 * Reads the bytecode of QuarkPress classes for paths around the site filter and the stamping listener.
 *
 * <ul>
 *   <li>Native SQL on site-scoped tables: a class that calls a native-query method, JDBC
 *       ({@code prepareStatement}, {@code prepareCall}, {@code execute*}, {@code addBatch}) or the Vert.x SQL client
 *       and holds a string naming a site-scoped table anywhere (in any method, lambda or constant) is rejected unless
 *       it is annotated {@link SiteScopedNativeSql}.
 *   <li>HQL inserts into site-scoped entities ({@code into} is optional), assignments to {@code siteId} in query
 *       strings, Panache {@code update(...)} strings and criteria updates, and criteria inserts of a site-scoped
 *       entity.
 *   <li>{@code disableFilter}, {@code enableFilter} or {@code getEnabledFilter} in a class that names the site filter.
 *   <li>Any reference to a StatelessSession type or to a Hibernate method about stateless sessions.
 *   <li>Outside the core and persistence packages, calls of (or method references to) {@code SiteBinding.bind}:
 *       requests are bound by site resolution, and work for another site or the network goes through SiteScope or
 *       NetworkScope.
 * </ul>
 *
 * <p>This is a best-effort guard against mistakes, not against deliberate evasion: SQL kept in a non-final field or
 * built from variables, attribute names held in variables, reflection, and SQL defined in one class but run from
 * another are not seen. The annotation covers only the class that carries it, not its nested classes.
 */
final class SiteIsolationScanner {

    record Finding(String className, String methodName, String problem) {}

    static final String STATELESS =
            "opens a StatelessSession or refers to one: updates of detached objects bypass the stored-site snapshot"
                    + " and its DML is not filtered by site";

    private static final String ANNOTATION =
            "L" + SiteScopedNativeSql.class.getName().replace('.', '/') + ";";
    private static final String FILTER = "qp_site";
    private static final String SITE_BINDING = SiteBinding.class.getName().replace('.', '/');
    private static final List<String> MAY_BIND = List.of("org.quarkpress.core.", "org.quarkpress.persistence.");
    private static final List<String> STATELESS_TYPES = List.of(
            "org/hibernate/StatelessSession",
            "org/hibernate/reactive/mutiny/Mutiny$StatelessSession",
            "org/hibernate/reactive/stage/Stage$StatelessSession");
    private static final Set<String> NATIVE_METHODS =
            Set.of("createNativeQuery", "createNativeMutationQuery", "createNativeSelectionQuery");
    private static final Set<String> JDBC_METHODS = Set.of(
            "prepareStatement",
            "prepareCall",
            "nativeSQL",
            "execute",
            "executeQuery",
            "executeUpdate",
            "executeLargeUpdate",
            "addBatch");
    private static final Set<String> SQL_CLIENT_METHODS = Set.of("query", "preparedQuery");
    private static final Set<String> FILTER_METHODS = Set.of("disableFilter", "enableFilter", "getEnabledFilter");
    private static final Set<String> CRITERIA_UPDATES =
            Set.of("jakarta/persistence/criteria/CriteriaUpdate", "org/hibernate/query/criteria/JpaCriteriaUpdate");
    private static final Set<String> CRITERIA_INSERTS =
            Set.of("createCriteriaInsertSelect", "createCriteriaInsertValues");
    private static final int FOLLOW_LIMIT = 4;

    private SiteIsolationScanner() {}

    /** What one method (or the constants of the class) contains. */
    private static final class Facts {
        final String member;
        final List<String> strings = new ArrayList<>();
        final List<String> types = new ArrayList<>();
        boolean nativeCall;
        boolean binds;
        boolean stateless;
        boolean panacheUpdate;
        boolean criteriaSet;
        boolean criteriaInsert;
        boolean metamodelSiteId;
        final Set<String> filterCalls = new LinkedHashSet<>();

        Facts(String member) {
            this.member = member;
        }

        void reference(String owner, String name, String descriptor) {
            if (mentionsStateless(owner)
                    || mentionsStateless(descriptor)
                    || (owner.startsWith("org/hibernate/") && name.contains("Stateless"))) {
                stateless = true;
            }
            if (NATIVE_METHODS.contains(name)
                    || (owner.startsWith("java/sql/") && JDBC_METHODS.contains(name))
                    || ((owner.startsWith("io/vertx/sqlclient/") || owner.startsWith("io/vertx/mutiny/sqlclient/"))
                            && SQL_CLIENT_METHODS.contains(name))) {
                nativeCall = true;
            }
            if (FILTER_METHODS.contains(name)) {
                filterCalls.add(name);
            }
            if (SITE_BINDING.equals(owner) && "bind".equals(name)) {
                binds = true;
            }
            if (isPanacheUpdate(name, descriptor)) {
                panacheUpdate = true;
            }
            if (CRITERIA_UPDATES.contains(owner) && "set".equals(name)) {
                criteriaSet = true;
            }
            if (CRITERIA_INSERTS.contains(name)) {
                criteriaInsert = true;
            }
        }

        void constant(Object value) {
            if (value instanceof String text) {
                strings.add(text);
            } else if (value instanceof Type type) {
                String descriptor = type.getDescriptor();
                types.add(type.getSort() == Type.OBJECT ? type.getInternalName() : descriptor);
                if (mentionsStateless(descriptor)) {
                    stateless = true;
                }
            } else if (value instanceof Handle handle) {
                reference(handle.getOwner(), handle.getName(), handle.getDesc());
            }
        }
    }

    static List<Finding> scan(byte[] classBytes, Set<String> siteTables, Set<String> siteEntities) {
        ClassNode type = new ClassNode();
        new ClassReader(classBytes).accept(type, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        String className = type.name.replace('/', '.');
        boolean nativeSqlAllowed = annotated(type.invisibleAnnotations) || annotated(type.visibleAnnotations);

        List<Facts> all = new ArrayList<>();
        Facts constants = new Facts("<class>");
        if (mentionsStateless(type.superName) || mentionsStateless(type.signature)) {
            constants.stateless = true;
        }
        for (String implemented : type.interfaces) {
            if (mentionsStateless(implemented)) {
                constants.stateless = true;
            }
        }
        all.add(constants);
        for (FieldNode field : type.fields) {
            Facts facts = new Facts(field.name);
            facts.constant(field.value);
            facts.stateless = mentionsStateless(field.desc) || mentionsStateless(field.signature);
            all.add(facts);
        }

        Set<Finding> found = new LinkedHashSet<>();
        for (MethodNode method : type.methods) {
            Facts facts = read(method);
            all.add(facts);
            for (String text : facts.strings) {
                for (String entity : siteEntities) {
                    if (insertsInto(text, entity)) {
                        found.add(new Finding(className, method.name, "inserts into site-scoped entity " + entity));
                    }
                }
                if (assignsSiteId(text, false)) {
                    found.add(new Finding(className, method.name, "assigns siteId in a query"));
                }
            }
            if (facts.panacheUpdate) {
                for (String text : panacheQueries(type.name, method, facts)) {
                    if (assignsSiteId(text, true)) {
                        found.add(new Finding(className, method.name, "assigns siteId in a query"));
                    }
                }
            }
            if (facts.criteriaSet && (facts.metamodelSiteId || facts.strings.contains("siteId"))) {
                found.add(new Finding(className, method.name, "assigns siteId in a criteria update"));
            }
            if (facts.criteriaInsert) {
                for (String referenced : facts.types) {
                    String simple = simpleName(referenced);
                    for (String entity : siteEntities) {
                        if (simple.equals(entity)) {
                            found.add(new Finding(className, method.name, "inserts into site-scoped entity " + entity));
                        }
                    }
                }
            }
            if (facts.binds && !mayBind(className)) {
                found.add(new Finding(
                        className,
                        method.name,
                        "calls SiteBinding.bind outside QuarkPress core and persistence; site resolution binds"
                                + " requests, and SiteScope or NetworkScope run work for another binding"));
            }
        }

        // Class-wide rules: the SQL or the filter name may sit in a helper method, a lambda or a constant.
        String nativeCallIn = null;
        String statelessIn = null;
        boolean namesFilter = false;
        Map<String, String> filterCalls = new LinkedHashMap<>();
        for (Facts facts : all) {
            if (facts.nativeCall && nativeCallIn == null) {
                nativeCallIn = facts.member;
            }
            if (facts.stateless && statelessIn == null) {
                statelessIn = facts.member;
            }
            namesFilter |= facts.strings.contains(FILTER);
            for (String call : facts.filterCalls) {
                filterCalls.putIfAbsent(call, facts.member);
            }
        }
        if (nativeCallIn != null && !nativeSqlAllowed) {
            for (String table : siteTables) {
                String seenIn = null;
                for (Facts facts : all) {
                    for (String text : facts.strings) {
                        if (seenIn == null && mentionsTable(text, table)) {
                            seenIn = facts.member;
                        }
                    }
                }
                if (seenIn != null) {
                    found.add(new Finding(className, seenIn, "native SQL on site-scoped table " + table));
                }
            }
        }
        if (namesFilter) {
            String disabledIn = filterCalls.get("disableFilter");
            if (disabledIn != null) {
                found.add(new Finding(className, disabledIn, "disables the site filter"));
            }
            for (String call : List.of("enableFilter", "getEnabledFilter")) {
                String calledIn = filterCalls.get(call);
                if (calledIn != null) {
                    found.add(new Finding(
                            className,
                            calledIn,
                            "changes the site filter through " + call + ": a new parameter can show every site"));
                }
            }
        }
        if (statelessIn != null) {
            found.add(new Finding(className, statelessIn, STATELESS));
        }
        return new ArrayList<>(found);
    }

    private static Facts read(MethodNode method) {
        Facts facts = new Facts(method.name);
        facts.stateless = mentionsStateless(method.desc) || mentionsStateless(method.signature);
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof LdcInsnNode ldc) {
                facts.constant(ldc.cst);
            } else if (insn instanceof MethodInsnNode call) {
                facts.reference(call.owner, call.name, call.desc);
            } else if (insn instanceof InvokeDynamicInsnNode indy) {
                if (mentionsStateless(indy.desc)) {
                    facts.stateless = true;
                }
                facts.constant(indy.bsm);
                for (Object argument : indy.bsmArgs) {
                    facts.constant(argument);
                }
            } else if (insn instanceof FieldInsnNode field) {
                if (mentionsStateless(field.owner) || mentionsStateless(field.desc)) {
                    facts.stateless = true;
                }
                if ("siteId".equals(field.name) && field.owner.endsWith("_")) {
                    facts.metamodelSiteId = true;
                }
            } else if (insn instanceof TypeInsnNode typeInsn) {
                if (mentionsStateless(typeInsn.desc)) {
                    facts.stateless = true;
                }
            } else if (insn instanceof MultiANewArrayInsnNode array) {
                if (mentionsStateless(array.desc)) {
                    facts.stateless = true;
                }
            }
        }
        return facts;
    }

    /**
     * The strings passed as the query of Panache {@code update(String, ...)} calls in the method, followed through
     * local variables a few steps; when a query cannot be traced, every string of the method counts.
     */
    private static List<String> panacheQueries(String owner, MethodNode method, Facts facts) {
        Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, method);
        } catch (AnalyzerException | RuntimeException e) {
            return facts.strings;
        }
        List<String> queries = new ArrayList<>();
        AbstractInsnNode[] insns = method.instructions.toArray();
        for (int i = 0; i < insns.length; i++) {
            if (!(insns[i] instanceof MethodInsnNode call) || !isPanacheUpdate(call.name, call.desc)) {
                continue;
            }
            Frame<SourceValue> frame = frames[i];
            if (frame == null) {
                continue;
            }
            int arguments = Type.getArgumentTypes(call.desc).length;
            SourceValue query = frame.getStack(frame.getStackSize() - arguments);
            if (!collect(query, method, frames, queries, 0)) {
                return facts.strings;
            }
        }
        return queries;
    }

    /** Adds the string constants a value comes from; false when a source is not a constant. */
    private static boolean collect(
            SourceValue value, MethodNode method, Frame<SourceValue>[] frames, List<String> into, int depth) {
        if (depth > FOLLOW_LIMIT || value.insns.isEmpty()) {
            return false;
        }
        for (AbstractInsnNode source : value.insns) {
            if (source instanceof LdcInsnNode ldc && ldc.cst instanceof String text) {
                into.add(text);
            } else if (source instanceof InvokeDynamicInsnNode indy) {
                for (Object argument : indy.bsmArgs) {
                    if (argument instanceof String text) {
                        into.add(text);
                    }
                }
            } else if (source instanceof VarInsnNode load) {
                Frame<SourceValue> frame = frames[method.instructions.indexOf(load)];
                if (frame == null || !collectStores(frame.getLocal(load.var), method, frames, into, depth + 1)) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return true;
    }

    private static boolean collectStores(
            SourceValue local, MethodNode method, Frame<SourceValue>[] frames, List<String> into, int depth) {
        if (depth > FOLLOW_LIMIT || local.insns.isEmpty()) {
            return false;
        }
        for (AbstractInsnNode store : local.insns) {
            if (!(store instanceof VarInsnNode)) {
                return false;
            }
            Frame<SourceValue> frame = frames[method.instructions.indexOf(store)];
            if (frame == null
                    || frame.getStackSize() == 0
                    || !collect(frame.getStack(frame.getStackSize() - 1), method, frames, into, depth + 1)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPanacheUpdate(String name, String descriptor) {
        return "update".equals(name) && descriptor.startsWith("(Ljava/lang/String;");
    }

    private static boolean annotated(List<AnnotationNode> annotations) {
        if (annotations != null) {
            for (AnnotationNode annotation : annotations) {
                if (ANNOTATION.equals(annotation.desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean mentionsStateless(String text) {
        if (text == null) {
            return false;
        }
        for (String name : STATELESS_TYPES) {
            if (text.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /** The simple name of an internal class name ({@code a/b/Outer$Inner} gives {@code Inner}). */
    private static String simpleName(String internalName) {
        int cut = Math.max(internalName.lastIndexOf('/'), internalName.lastIndexOf('$'));
        return internalName.substring(cut + 1);
    }

    /** Whether a class may bind requests: core and persistence classes only. */
    static boolean mayBind(String className) {
        for (String prefix : MAY_BIND) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code sql} names {@code table} as a whole word, ignoring case. */
    static boolean mentionsTable(String sql, String table) {
        return indexOfWord(sql, table, 0) >= 0;
    }

    /**
     * Whether {@code text} has {@code insert [into] <entity>} (HQL allows leaving out {@code into}); a qualified name
     * counts by its last segment.
     */
    static boolean insertsInto(String text, String entity) {
        int from = 0;
        while (true) {
            int insert = indexOfWord(text, "insert", from);
            if (insert < 0) {
                return false;
            }
            int start = skipSpaces(text, insert + "insert".length());
            if (startsWithWord(text, start, "into")) {
                start = skipSpaces(text, start + "into".length());
            }
            int end = start;
            while (end < text.length() && (isIdentifierChar(text, end) || text.charAt(end) == '.')) {
                end++;
            }
            String name = text.substring(start, end);
            int dot = name.lastIndexOf('.');
            if (name.substring(dot + 1).equalsIgnoreCase(entity)) {
                return true;
            }
            from = insert + "insert".length();
        }
    }

    /**
     * Whether {@code text} assigns {@code siteId}: {@code siteId =} (not {@code ==}) after a {@code set} keyword with no
     * later {@code where} at the same parenthesis depth (a subquery's {@code where} does not end the assignment list),
     * or, for Panache {@code update(...)} strings (which have no {@code set}), at the start of the assignment list.
     */
    static boolean assignsSiteId(String text, boolean panacheUpdate) {
        int from = 0;
        while (true) {
            int at = indexOfWord(text, "siteId", from);
            if (at < 0) {
                return false;
            }
            int next = skipSpaces(text, at + "siteId".length());
            boolean equalsSign = next < text.length()
                    && text.charAt(next) == '='
                    && (next + 1 >= text.length() || text.charAt(next + 1) != '=');
            if (equalsSign) {
                int[] last = clauseKeywordsBefore(text, at);
                int set = last[0];
                int where = last[1];
                if (set > where) {
                    return true;
                }
                if (panacheUpdate && set < 0 && where < 0 && last[2] < 0 && last[3] < 0) {
                    return true;
                }
            }
            from = at + "siteId".length();
        }
    }

    /**
     * The last positions of {@code set}, {@code where}, {@code from} and {@code select} before {@code end} at the
     * parenthesis depth of {@code end}, outside quoted text; -1 where there is none.
     */
    private static int[] clauseKeywordsBefore(String text, int end) {
        String[] keywords = {"set", "where", "from", "select"};
        // last[depth * 4 + k]: the depth never exceeds the number of characters read
        int[] last = new int[(end + 1) * keywords.length];
        Arrays.fill(last, -1);
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < end; i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"' || c == '`') {
                quote = c;
            } else if (c == '(') {
                depth++;
                for (int k = 0; k < keywords.length; k++) {
                    last[depth * keywords.length + k] = -1;
                }
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (!isIdentifierChar(text, i - 1)) {
                for (int k = 0; k < keywords.length; k++) {
                    if (startsWithWord(text, i, keywords[k])) {
                        last[depth * keywords.length + k] = i;
                    }
                }
            }
        }
        int[] result = new int[keywords.length];
        System.arraycopy(last, depth * keywords.length, result, 0, keywords.length);
        return result;
    }

    static String message(List<Finding> findings) {
        StringBuilder text = new StringBuilder(
                "QuarkPress: these paths bypass the site filter or the site checks. Use HQL or Panache with the"
                        + " site in the condition; native SQL on site tables belongs in a class annotated"
                        + " @SiteScopedNativeSql that takes the site explicitly:");
        for (Finding finding : findings) {
            text.append("\n- ")
                    .append(finding.className())
                    .append('#')
                    .append(finding.methodName())
                    .append(' ')
                    .append(finding.problem());
        }
        return text.toString();
    }

    private static int indexOfWord(String text, String word, int from) {
        int length = word.length();
        for (int i = Math.max(0, from); i + length <= text.length(); i++) {
            if (text.regionMatches(true, i, word, 0, length)
                    && !isIdentifierChar(text, i - 1)
                    && !isIdentifierChar(text, i + length)) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOfWord(String text, String word) {
        int last = -1;
        int at = indexOfWord(text, word, 0);
        while (at >= 0) {
            last = at;
            at = indexOfWord(text, word, at + word.length());
        }
        return last;
    }

    private static boolean startsWithWord(String text, int at, String word) {
        return at + word.length() <= text.length()
                && text.regionMatches(true, at, word, 0, word.length())
                && !isIdentifierChar(text, at + word.length());
    }

    private static int skipSpaces(String text, int at) {
        int i = at;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean isIdentifierChar(String text, int index) {
        if (index < 0 || index >= text.length()) {
            return false;
        }
        char c = text.charAt(index);
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
