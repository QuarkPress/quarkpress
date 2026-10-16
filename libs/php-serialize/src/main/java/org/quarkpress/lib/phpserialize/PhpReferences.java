package org.quarkpress.lib.phpserialize;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Replaces back-references by copies of the values they point to, for the plain-Java conversions. The {@link
 * PhpValue} tree itself keeps references symbolic so that it re-serializes byte for byte.
 *
 * <p>Slots are numbered over the tree as PHP numbers them while reading (see {@link PhpParser}). A tree parsed
 * from a document that repeated a key lost the overwritten values, so later slot numbers can be off by the
 * values it lost; such a reference may then read as null. PHP-written documents never repeat keys.
 */
final class PhpReferences {

    /**
     * References can multiply a document's size; resolution stops once the copies made for references exceed this
     * many values. Values that are copied only because they are part of the document do not count.
     */
    static final int MAX_RESOLVED_VALUES = 1_000_000;

    /**
     * Copies placed at a reference can nest deeper than the document did; resolution stops beyond this nesting
     * depth, the parser's own upper bound, so that the recursive copy and conversions stay within the stack.
     */
    static final int MAX_RESOLVED_DEPTH = ParseOptions.MAX_DEPTH_LIMIT;

    private final List<PhpValue> bySlot = new ArrayList<>();
    private final List<Integer> span = new ArrayList<>();
    private final BitSet resolving = new BitSet();
    private int produced;
    private int[] finalSlots = new int[0];

    private PhpReferences() {
        bySlot.add(null); // slots start at 1
        span.add(0);
    }

    /**
     * The value with every reference replaced by a copy of its target. A reference at the root, or one whose
     * target does not exist (possible only in hand-built trees), becomes {@link PhpNull}.
     *
     * @throws PhpSerializeException for a reference cycle, when the copies made for references exceed {@link
     *     #MAX_RESOLVED_VALUES} values, or when they nest deeper than {@link #MAX_RESOLVED_DEPTH}
     */
    static PhpValue resolve(PhpValue root) {
        if (root instanceof PhpReference) {
            return PhpNull.INSTANCE;
        }
        if (!containsReference(root)) {
            return root;
        }
        PhpReferences references = new PhpReferences();
        references.number(root);
        references.finalSlots = new int[references.bySlot.size()];
        return references.copy(root, 1, 0, false);
    }

    /** Iterative, so that it is safe at any nesting depth. */
    static boolean containsReference(PhpValue value) {
        Deque<PhpValue> pending = new ArrayDeque<>();
        pending.push(value);
        while (!pending.isEmpty()) {
            switch (pending.pop()) {
                case PhpReference r -> {
                    return true;
                }
                case PhpArray a -> a.entries().values().forEach(pending::push);
                case PhpObject o -> pending.push(o.properties());
                default -> {}
            }
        }
        return false;
    }

    /** Numbers {@code value} and its children; returns how many slots they took. */
    private int number(PhpValue value) {
        if (value instanceof PhpReference r && r.kind() == PhpReference.Kind.REFERENCE) {
            return 0;
        }
        int slot = bySlot.size();
        bySlot.add(value);
        span.add(0);
        int taken = 1;
        PhpArray children = switch (value) {
            case PhpArray a -> a;
            case PhpObject o -> o.properties();
            default -> null;
        };
        if (children != null) {
            for (PhpValue child : children.entries().values()) {
                taken += number(child);
            }
        }
        span.set(slot, taken);
        return taken;
    }

    /**
     * Copies {@code value}, which sits at nesting {@code depth} of the result; {@code expanded} is true inside a
     * copy made for a reference.
     */
    private PhpValue copy(PhpValue value, int slot, int depth, boolean expanded) {
        if (depth > MAX_RESOLVED_DEPTH) {
            throw new PhpSerializeException("reference expansion too deep: nests deeper than " + MAX_RESOLVED_DEPTH);
        }
        if (expanded && ++produced > MAX_RESOLVED_VALUES) {
            throw new PhpSerializeException("references expand to more than " + MAX_RESOLVED_VALUES + " values");
        }
        return switch (value) {
            case PhpArray a -> copyEntries(a, slot, depth, expanded);
            case PhpObject o -> new PhpObject(o.className(), copyEntries(o.properties(), slot, depth, expanded));
            case PhpReference r -> follow(r, depth);
            default -> value;
        };
    }

    private PhpArray copyEntries(PhpArray array, int slot, int depth, boolean expanded) {
        resolving.set(slot);
        PhpArray.Builder out = PhpArray.builder();
        int next = slot + 1;
        for (Map.Entry<PhpArray.Key, PhpValue> e : array.entries().entrySet()) {
            PhpValue child = e.getValue();
            if (child instanceof PhpReference r) {
                out.put(e.getKey(), follow(r, depth + 1));
                if (r.kind() == PhpReference.Kind.OBJECT) {
                    next++; // an r: handle takes a slot of its own
                }
            } else {
                out.put(e.getKey(), copy(child, next, depth + 1, expanded));
                next += span.get(next);
            }
        }
        resolving.clear(slot);
        return out.build();
    }

    /** A copy of the target of {@code reference}, placed at nesting {@code depth}. */
    private PhpValue follow(PhpReference reference, int depth) {
        int slot = finalSlot(reference.index());
        if (slot == 0) {
            return PhpNull.INSTANCE;
        }
        if (resolving.get(slot)) {
            throw new PhpSerializeException("cyclic reference");
        }
        return copy(bySlot.get(slot), slot, depth, true);
    }

    /**
     * The slot of the non-handle value that {@code target} ends at after following any chain of {@code r:}
     * handles, or 0 when the chain leaves the tree. Iterative, and memoised so that long chains cost O(1) each.
     */
    private int finalSlot(long target) {
        int start = (int) Math.min(Math.max(target, 0), Integer.MAX_VALUE);
        if (start < 1 || start >= bySlot.size()) {
            return 0;
        }
        List<Integer> path = new ArrayList<>();
        int slot = start;
        int result;
        while (true) {
            if (finalSlots[slot] != 0) {
                result = finalSlots[slot];
                break;
            }
            if (!(bySlot.get(slot) instanceof PhpReference handle)) {
                result = slot;
                break;
            }
            if (path.size() > bySlot.size()) {
                throw new PhpSerializeException("cyclic reference");
            }
            path.add(slot);
            long next = handle.index();
            if (next < 1 || next >= bySlot.size()) {
                result = -1; // dangling
                break;
            }
            slot = (int) next;
        }
        for (int p : path) {
            finalSlots[p] = result;
        }
        return Math.max(result, 0);
    }
}
