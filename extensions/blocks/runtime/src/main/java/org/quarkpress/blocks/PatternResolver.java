package org.quarkpress.blocks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jboss.logging.Logger;
import org.quarkpress.blocks.runtime.BlocksConfig;
import org.quarkpress.lib.wpblocks.Block;
import org.quarkpress.lib.wpblocks.BlockParseException;
import org.quarkpress.lib.wpblocks.ParseOptions;
import org.quarkpress.lib.wpblocks.WpBlocks;
import org.quarkpress.lib.wpkses.TextFields;

/**
 * A pattern's content as WordPress lists it: the registered markup parsed as it is (its surrounding whitespace kept),
 * its nested pattern references
 * resolved and written again in WordPress's canonical block form (attribute JSON re-encoded, empty blocks in the void
 * form, the text between blocks kept).
 *
 * <p>Resolution walks the blocks depth first. A {@code core/pattern} block whose {@code slug} is text PHP does not call
 * empty is replaced by the blocks of the pattern registered under that name (its markup trimmed), themselves
 * resolved; one naming no
 * registered pattern, or without a slug, stays where it is. A reference to a pattern already being expanded further up
 * is removed. The pattern whose content is resolved is not itself on that chain, so a pattern that refers to itself has
 * that reference expanded once, and only the reference inside the inserted copy is removed. When the inserted pattern
 * has exactly one top-level block, that block's {@code metadata} gains {@code patternName} (the slug), {@code name}
 * (the title) and, when the pattern has them, {@code description} and {@code categories} (as registered), each
 * sanitized as a text field; the block's own metadata keeps its place and what the pattern does not set.
 *
 * <p>Unlike WordPress, the work is bounded: once {@code quarkpress.blocks.max-resolved-blocks} blocks were inserted,
 * and wherever the patterns and blocks would nest deeper than the block parser reads ({@value #MAX_DEPTH} levels), the
 * remaining references are left as they are and one warning names the pattern. Each pattern's markup is parsed once
 * per resolution, so the work is linear in the blocks inserted.
 */
@Singleton
public final class PatternResolver {

    private static final Logger LOG = Logger.getLogger(PatternResolver.class);

    private static final String PATTERN_BLOCK = "core/pattern";

    /** How deep the blocks and the chain of patterns being expanded may nest together. */
    static final int MAX_DEPTH = ParseOptions.MAX_DEPTH_LIMIT;

    private final Function<String, Optional<BlockPattern>> patterns;
    private final int maxResolvedBlocks;
    private final Consumer<String> warning;

    @Inject
    PatternResolver(BlockPatternRegistry registry, BlocksConfig config) {
        this(registry::find, config.maxResolvedBlocks());
    }

    /**
     * A resolver over the patterns {@code patterns} finds by name, inserting at most {@code maxResolvedBlocks} blocks
     * per resolved pattern.
     */
    public PatternResolver(Function<String, Optional<BlockPattern>> patterns, int maxResolvedBlocks) {
        this(patterns, maxResolvedBlocks, LOG::warn);
    }

    PatternResolver(
            Function<String, Optional<BlockPattern>> patterns, int maxResolvedBlocks, Consumer<String> warning) {
        if (maxResolvedBlocks < 0) {
            throw new IllegalArgumentException(
                    "quarkpress.blocks.max-resolved-blocks must not be negative: " + maxResolvedBlocks);
        }
        this.patterns = Objects.requireNonNull(patterns, "patterns");
        this.maxResolvedBlocks = maxResolvedBlocks;
        this.warning = Objects.requireNonNull(warning, "warning");
    }

    /**
     * The pattern's content with its nested patterns resolved, in WordPress's canonical block form. Markup beyond the
     * block parser's limits is answered as registered.
     */
    public String content(BlockPattern pattern) {
        List<Block> blocks;
        try {
            blocks = WpBlocks.parse(pattern.content());
        } catch (BlockParseException e) {
            warning.accept("Block pattern " + pattern.name() + " is beyond the block parser's limits; its content is"
                    + " answered as registered: " + e.getMessage());
            return pattern.content();
        }
        return WpBlocks.serializeCanonical(new Resolution(pattern.name()).list(blocks, 1));
    }

    /** One resolution: the chain of patterns being expanded, the patterns parsed so far and the blocks inserted. */
    private final class Resolution {

        /** A registered pattern's blocks, how deep they nest and how many there are; no blocks when unparsable. */
        private record Parsed(List<Block> blocks, int depth, long size) {}

        private final String listed;
        private final Set<String> chain = new HashSet<>();
        private final Map<String, Parsed> parsed = new HashMap<>();
        private long inserted;
        private boolean warned;

        Resolution(String listed) {
            this.listed = listed;
        }

        /** The blocks of one level, {@code depth} deep (1 at the top), with every reference resolved. */
        List<Block> list(List<Block> blocks, int depth) {
            List<Block> out = new ArrayList<>(blocks.size());
            for (Block block : blocks) {
                out.addAll(replacement(block, depth));
            }
            return out;
        }

        /** What takes the block's place: itself (its inner blocks resolved), nothing, or a pattern's blocks. */
        private List<Block> replacement(Block block, int depth) {
            if (!PATTERN_BLOCK.equals(block.name())) {
                return List.of(block.innerBlocks().isEmpty() ? block : withResolvedInnerBlocks(block, depth));
            }
            String slug = slug(block);
            if (slug == null) {
                return List.of(block);
            }
            if (chain.contains(slug)) {
                // a cycle: the reference is removed
                return List.of();
            }
            Optional<BlockPattern> pattern = patterns.apply(slug);
            if (pattern.isEmpty()) {
                return List.of(block);
            }
            Parsed blocks = parsed(pattern.get());
            if (blocks.blocks() == null) {
                return List.of(block);
            }
            if (inserted >= maxResolvedBlocks) {
                warnOnce("Block pattern " + listed + ": nested patterns are left unresolved after " + maxResolvedBlocks
                        + " inserted blocks (quarkpress.blocks.max-resolved-blocks)");
                return List.of(block);
            }
            if (depth - 1 + blocks.depth() > MAX_DEPTH || depth + chain.size() >= MAX_DEPTH) {
                warnOnce("Block pattern " + listed + ": nested patterns are left unresolved deeper than " + MAX_DEPTH
                        + " levels");
                return List.of(block);
            }
            inserted += blocks.size();
            chain.add(slug);
            List<Block> resolvedBlocks = list(withMetadata(blocks.blocks(), pattern.get()), depth);
            chain.remove(slug);
            return resolvedBlocks;
        }

        /** The block with its inner blocks resolved, the text between them kept. */
        private Block withResolvedInnerBlocks(Block block, int depth) {
            List<Block> innerBlocks = block.innerBlocks();
            List<List<Block>> replacements = new ArrayList<>(innerBlocks.size());
            boolean changed = false;
            for (Block inner : innerBlocks) {
                List<Block> replacement = replacement(inner, depth + 1);
                replacements.add(replacement);
                changed |= replacement.size() != 1 || replacement.get(0) != inner;
            }
            if (!changed) {
                return block;
            }
            List<Block> newBlocks = new ArrayList<>();
            List<String> newContent = new ArrayList<>(block.innerContent().size());
            int next = 0;
            for (String chunk : block.innerContent()) {
                if (chunk != null) {
                    newContent.add(chunk);
                    continue;
                }
                for (Block replacement : replacements.get(next++)) {
                    newBlocks.add(replacement);
                    newContent.add(null);
                }
            }
            return block.withInnerContent(newBlocks, newContent);
        }

        private Parsed parsed(BlockPattern pattern) {
            return parsed.computeIfAbsent(pattern.name(), name -> {
                try {
                    List<Block> blocks = WpBlocks.parse(phpTrim(pattern.content()));
                    long[] size = new long[1];
                    int depth = measure(blocks, size);
                    return new Parsed(blocks, depth, size[0]);
                } catch (BlockParseException e) {
                    return new Parsed(null, 0, 0);
                }
            });
        }

        private void warnOnce(String message) {
            if (!warned) {
                warned = true;
                warning.accept(message);
            }
        }
    }

    /** How deep the blocks nest (1 for blocks without inner blocks), adding the number of blocks to {@code size[0]}. */
    private static int measure(List<Block> blocks, long[] size) {
        int deepest = 0;
        List<Block> level = blocks;
        while (!level.isEmpty()) {
            deepest++;
            List<Block> next = new ArrayList<>();
            for (Block block : level) {
                size[0]++;
                next.addAll(block.innerBlocks());
            }
            level = next;
        }
        return deepest;
    }

    /** The reference's slug when it is text PHP does not call empty ({@code ""} and {@code "0"} are), else null. */
    private static String slug(Block block) {
        JsonNode slug = block.attributes().get("slug");
        if (slug == null || !slug.isTextual()) {
            return null;
        }
        String text = slug.textValue();
        return text.isEmpty() || text.equals("0") ? null : text;
    }

    /** The pattern's blocks, the single top-level one given the pattern's metadata. */
    private static List<Block> withMetadata(List<Block> blocks, BlockPattern pattern) {
        if (blocks.size() != 1 || blocks.get(0).isFreeform()) {
            return blocks;
        }
        Block block = blocks.get(0);
        ObjectNode attributes = block.attributes();
        // an object keeps its keys in place, as PHP's array does when a key is assigned again
        ObjectNode metadata =
                attributes.get("metadata") instanceof ObjectNode existing ? existing : attributes.objectNode();
        metadata.put("patternName", pattern.name());
        // the pattern's value, else the block's own, sanitized either way; a value PHP calls false ("" or "0") is
        // skipped, and a value that is not a list of text is read as PHP reads it
        text(metadata, "name", pattern.title());
        text(metadata, "description", pattern.description().orElse(null));
        List<String> categories = pattern.categories().orElse(null);
        if (categories == null && metadata.get("categories") instanceof ArrayNode existing) {
            categories = new ArrayList<>(existing.size());
            for (JsonNode category : existing) {
                categories.add(phpString(category));
            }
        }
        if (categories != null && !categories.isEmpty()) {
            ArrayNode sanitized = metadata.arrayNode(categories.size());
            categories.forEach(category -> sanitized.add(TextFields.textField(category)));
            metadata.set("categories", sanitized);
        }
        attributes.set("metadata", metadata);
        return List.of(block.withAttributes(attributes));
    }

    /** Sets the sanitized text of the pattern's value, else of the block's own, unless PHP calls it false. */
    private static void text(ObjectNode metadata, String key, String patternValue) {
        String value = patternValue;
        if (value == null) {
            JsonNode existing = metadata.get(key);
            value = existing == null || existing.isNull() ? null : phpString(existing);
        }
        if (value != null && !value.isEmpty() && !value.equals("0")) {
            metadata.put(key, TextFields.textField(value));
        }
    }

    /** A JSON value as PHP casts it to a string: text as is, numbers and booleans as PHP prints them. */
    private static String phpString(JsonNode node) {
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue() ? "1" : "";
        }
        if (node.isNull()) {
            return "";
        }
        return node.asText();
    }

    /** PHP's {@code trim}: spaces, tabs, line feeds, carriage returns, NUL and vertical tabs off both ends. */
    static String phpTrim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && trimmed(text.charAt(start))) {
            start++;
        }
        while (end > start && trimmed(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean trimmed(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\0' || c == '\u000B';
    }
}
