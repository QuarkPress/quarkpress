package org.quarkpress.testing.wprestparity;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One scenario: setup steps on a fresh subdirectory site, then requests. Request targets, header values and bodies may
 * name refs in braces ({@code {t:news}}, {@code {u:carol}}, {@code {p:draft}}); each side replaces them by its own ids
 * before sending (see {@link #resolve}).
 */
public record RestScenario(String id, String rule, List<SetupStep> setup, List<RestCall> requests) {

    /** The callers a request may name. */
    public static final List<String> CALLERS = List.of("anonymous", "invalid", "alice", "bob", "carol", "dave", "erin");

    /** The refs every scenario's site starts with: its default category and the five users. */
    public static final List<String> STARTING_REFS =
            List.of("t:uncategorized", "u:alice", "u:bob", "u:carol", "u:dave", "u:erin");

    /** One step that prepares the scenario's site through the side's own API. */
    public sealed interface SetupStep {
        /** A post written by {@code as} ({@code type} post or page). */
        record Post(String ref, String as, String type, String status, String title) implements SetupStep {}

        /** A term of {@code taxonomy} (category or post_tag) created by {@code as} through the REST API. */
        record Term(String ref, String as, String taxonomy, String name, Optional<String> parentRef)
                implements SetupStep {}

        /** An option of the scenario's site, set directly. */
        record Option(String name, JsonNode value) implements SetupStep {}

        /**
         * {@code user} leaves the scenario's site (no role there) and stays a member of the main site: a member of
         * another site only, for the requests that follow. Never alice, the super admin.
         */
        record Leave(String user) implements SetupStep {}
    }

    public RestScenario {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rule, "rule");
        setup = List.copyOf(setup);
        requests = List.copyOf(requests);
        validate(id, setup, requests);
    }

    /** The site path of the scenario: {@code /<id>/}. */
    public String sitePath() {
        return "/" + id + "/";
    }

    /** {@code call} with every {@code {ref}} in its target, header values and body replaced by the ref's id. */
    public static RestCall resolve(RestCall call, Map<String, Long> refs) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : call.headers().entrySet()) {
            headers.put(header.getKey(), substitute(header.getValue(), refs));
        }
        Optional<byte[]> body = call.body()
                .map(bytes -> substitute(new String(bytes, StandardCharsets.UTF_8), refs)
                        .getBytes(StandardCharsets.UTF_8));
        return new RestCall(
                call.as(), call.method(), substitute(call.target(), refs), headers, call.contentType(), body);
    }

    /** {@code text} with each {@code {ref}} replaced by its id; an unknown ref fails. */
    static String substitute(String text, Map<String, Long> refs) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int end = refEnd(text, i);
            if (end < 0) {
                out.append(text.charAt(i++));
                continue;
            }
            String ref = text.substring(i + 1, end);
            Long id = refs.get(ref);
            if (id == null) {
                throw new IllegalArgumentException("unknown ref {" + ref + "} in " + text);
            }
            out.append(id);
            i = end + 1;
        }
        return out.toString();
    }

    /** The refs named in braces in {@code text}, in order. */
    static List<String> refsIn(String text) {
        List<String> refs = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            int end = refEnd(text, i);
            if (end >= 0) {
                refs.add(text.substring(i + 1, end));
                i = end;
            }
        }
        return refs;
    }

    /** When a ref in braces starts at {@code start}, the index of its closing brace; otherwise -1. */
    private static int refEnd(String text, int start) {
        if (text.charAt(start) != '{' || start + 3 >= text.length() || text.charAt(start + 2) != ':') {
            return -1;
        }
        if ("tupc".indexOf(text.charAt(start + 1)) < 0) {
            return -1;
        }
        int i = start + 3;
        while (i < text.length() && isRefChar(text.charAt(i))) {
            i++;
        }
        return i > start + 3 && i < text.length() && text.charAt(i) == '}' ? i : -1;
    }

    /** Whether {@code ref} is a well-formed ref name: {@code t:}, {@code u:}, {@code p:} or {@code c:} and a name. */
    static boolean isRef(String ref) {
        if (ref.length() < 3 || "tupc".indexOf(ref.charAt(0)) < 0 || ref.charAt(1) != ':') {
            return false;
        }
        for (int i = 2; i < ref.length(); i++) {
            if (!isRefChar(ref.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isRefChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
    }

    private static void validate(String id, List<SetupStep> setup, List<RestCall> requests) {
        if (id.isEmpty()) {
            throw new IllegalArgumentException("a scenario needs an id");
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-')) {
                throw new IllegalArgumentException("scenario id " + id + ": only a-z, 0-9 and '-' (it is a site path)");
            }
        }
        Set<String> defined = new HashSet<>(STARTING_REFS);
        for (SetupStep step : setup) {
            switch (step) {
                case SetupStep.Post post -> {
                    caller(id, post.as(), false);
                    define(id, defined, post.ref(), 'p');
                }
                case SetupStep.Term term -> {
                    caller(id, term.as(), false);
                    if (!term.taxonomy().equals("category") && !term.taxonomy().equals("post_tag")) {
                        throw new IllegalArgumentException(id + ": unknown taxonomy " + term.taxonomy());
                    }
                    term.parentRef().ifPresent(parent -> used(id, defined, parent));
                    define(id, defined, term.ref(), 't');
                }
                case SetupStep.Option option -> {
                    if (option.name().isEmpty()) {
                        throw new IllegalArgumentException(id + ": an option needs a name");
                    }
                }
                case SetupStep.Leave leave -> {
                    caller(id, leave.user(), false);
                    if (leave.user().equals("alice")) {
                        throw new IllegalArgumentException(id + ": alice, the super admin, never leaves a site");
                    }
                }
            }
        }
        if (requests.isEmpty()) {
            throw new IllegalArgumentException(id + ": a scenario sends at least one request");
        }
        for (RestCall call : requests) {
            caller(id, call.as(), true);
            List<String> texts = new ArrayList<>();
            texts.add(call.target());
            texts.addAll(call.headers().values());
            call.body().ifPresent(body -> texts.add(new String(body, StandardCharsets.UTF_8)));
            for (String text : texts) {
                for (String ref : refsIn(text)) {
                    used(id, defined, ref);
                }
            }
        }
    }

    private static void caller(String id, String as, boolean invalidAllowed) {
        if (!CALLERS.contains(as) || (!invalidAllowed && (as.equals("invalid") || as.equals("anonymous")))) {
            throw new IllegalArgumentException(id + ": unknown caller '" + as + "'");
        }
    }

    private static void define(String id, Set<String> defined, String ref, char kind) {
        if (!isRef(ref) || ref.charAt(0) != kind) {
            throw new IllegalArgumentException(id + ": not a " + kind + ": ref: " + ref);
        }
        if (!defined.add(ref)) {
            throw new IllegalArgumentException(id + ": ref " + ref + " is defined twice");
        }
    }

    private static void used(String id, Set<String> defined, String ref) {
        if (!defined.contains(ref)) {
            throw new IllegalArgumentException(id + ": ref " + ref + " is used before it is defined");
        }
    }
}
