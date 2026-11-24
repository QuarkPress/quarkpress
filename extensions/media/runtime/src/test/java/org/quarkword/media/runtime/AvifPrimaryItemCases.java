package org.quarkword.media.runtime;

import static org.quarkword.testing.wprestparity.MediaFixtures.AvifBoxes.av1C;
import static org.quarkword.testing.wprestparity.MediaFixtures.AvifBoxes.box;
import static org.quarkword.testing.wprestparity.MediaFixtures.AvifBoxes.dimg;
import static org.quarkword.testing.wprestparity.MediaFixtures.AvifBoxes.file;
import static org.quarkword.testing.wprestparity.MediaFixtures.AvifBoxes.ftyp;
import static org.quarkword.testing.wprestparity.MediaFixtures.AvifBoxes.fullBox;
import static org.quarkword.testing.wprestparity.MediaFixtures.AvifBoxes.iinf;
import static org.quarkword.testing.wprestparity.MediaFixtures.AvifBoxes.ipco;
import static org.quarkword.testing.wprestparity.MediaFixtures.AvifBoxes.ipma;
import static org.quarkword.testing.wprestparity.MediaFixtures.AvifBoxes.iprp;
import static org.quarkword.testing.wprestparity.MediaFixtures.AvifBoxes.iref;
import static org.quarkword.testing.wprestparity.MediaFixtures.AvifBoxes.ispe;
import static org.quarkword.testing.wprestparity.MediaFixtures.AvifBoxes.item;
import static org.quarkword.testing.wprestparity.MediaFixtures.AvifBoxes.meta;
import static org.quarkword.testing.wprestparity.MediaFixtures.AvifBoxes.pitm;
import static org.quarkword.testing.wprestparity.MediaFixtures.AvifBoxes.pixi;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Synthetic AVIF headers whose size depends on which item is the primary one, with the size WordPress 7.1.3's
 * {@code wp_getimagesize} gives each (recorded from the media parity run): {@code width} and {@code height}, or 0 and
 * 0 for no size. {@link ImageHeadersTest} checks them against {@link ImageHeaders}, {@link MediaParityTest} against
 * WordPress itself.
 */
final class AvifPrimaryItemCases {

    record Case(String name, byte[] bytes, int width, int height) {

        boolean sized() {
            return width > 0;
        }
    }

    private AvifPrimaryItemCases() {}

    /** Whether the file type box's major brand is {@code avif} or {@code avis}. */
    static boolean avifMajorBrand(byte[] bytes) {
        String major = new String(bytes, 8, 4, StandardCharsets.US_ASCII);
        return major.equals("avif") || major.equals("avis");
    }

    static final byte[] AVIF = ftyp("avif", "avif", "mif1", "miaf");

    private static byte[] avif(byte[]... metaChildren) {
        return file(AVIF, meta(0, metaChildren), box("mdat"));
    }

    /** Four 512x512 tiles (items 1-4) of a 1024x1024 grid (item 5), the tiles' properties listed first. */
    private static byte[] grid(boolean gridHasDepth, boolean tilesHaveDepth, boolean irefFirst) {
        return grid(gridHasDepth, tilesHaveDepth, irefFirst, true);
    }

    private static byte[] grid(boolean gridHasDepth, boolean tilesHaveDepth, boolean irefFirst, boolean withIinf) {
        byte[] ipco = ipco(ispe(512, 512), pixi(8, 8, 8), av1C(0), ispe(1024, 1024));
        int[] tile = tilesHaveDepth ? new int[] {0, 0x8001, 0x8003, 2} : new int[] {0, 0x8001};
        int[][] entries = new int[5][];
        for (int i = 0; i < 4; i++) {
            entries[i] = tile.clone();
            entries[i][0] = i + 1;
        }
        entries[4] = gridHasDepth ? new int[] {5, 4, 2} : new int[] {5, 4};
        byte[] iprp = iprp(ipco, ipma(0, 0, entries));
        byte[] iref = iref(0, dimg(5, 1, 2, 3, 4));
        byte[] iinf = withIinf
                ? iinf(item(1, "av01"), item(2, "av01"), item(3, "av01"), item(4, "av01"), item(5, "grid"))
                : new byte[0];
        return irefFirst ? avif(pitm(0, 5), iinf, iref, iprp) : avif(pitm(0, 5), iinf, iprp, iref);
    }

    /** Items 1 and 2 (100x100) listed before the primary item 3 (640x480). */
    private static byte[] primaryLast(int pitmVersion, int ipmaVersion, int ipmaFlags) {
        return primaryLast(pitmVersion, ipmaVersion, ipmaFlags, true);
    }

    private static byte[] primaryLast(int pitmVersion, int ipmaVersion, int ipmaFlags, boolean withIinf) {
        byte[] ipco = ipco(ispe(100, 100), pixi(8, 8, 8), ispe(640, 480), av1C(0x40));
        byte[] ipma = ipma(ipmaVersion, ipmaFlags, new int[] {1, 1, 2}, new int[] {2, 1, 2}, new int[] {3, 0x8004, 3});
        return avif(pitm(pitmVersion, 3), withIinf ? iinf(1, 2, 3) : new byte[0], iprp(ipco, ipma));
    }

    static List<Case> cases() {
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("grid", grid(true, true, false), 1024, 1024));
        cases.add(new Case("grid-depth-from-tile", grid(false, true, false), 1024, 1024));
        cases.add(new Case("grid-no-depth", grid(false, false, false), 0, 0));
        cases.add(new Case("grid-iref-first", grid(false, true, true), 1024, 1024));
        cases.add(new Case("primary-last", primaryLast(0, 0, 0), 640, 480));
        cases.add(new Case("two-byte-associations", primaryLast(0, 0, 1), 640, 480));
        cases.add(new Case("ipma-v1", primaryLast(0, 1, 0), 640, 480));
        cases.add(new Case("ipma-v1-two-byte", primaryLast(0, 1, 1), 640, 480));
        cases.add(new Case("pitm-v1", primaryLast(1, 0, 0), 640, 480));
        // a depth that only another item has
        cases.add(new Case(
                "depth-elsewhere",
                avif(pitm(0, 1), iinf(1, 2), iprp(ipco(ispe(64, 32), pixi(8)), ipma(0, 0, new int[] {1, 1}, new int[] {
                    2, 2
                }))),
                0,
                0));
        cases.add(new Case(
                "no-depth-source",
                avif(pitm(0, 1), iinf(1, 2), iprp(ipco(ispe(64, 32)), ipma(0, 0, new int[] {1, 1}))),
                0,
                0));
        // associations read before the properties they name
        byte[] props = ipco(ispe(64, 32), pixi(10, 10, 10));
        byte[] assoc = ipma(0, 0, new int[] {1, 1, 2});
        cases.add(new Case("ipma-before-ipco", avif(pitm(0, 1), iinf(1, 2), iprp(assoc, props)), 0, 0));
        cases.add(new Case(
                "ipma-before-ipco-then-dimg",
                avif(pitm(0, 1), iinf(1, 2), iprp(assoc, props), iref(0, dimg(1, 2))),
                64,
                32));
        cases.add(new Case("no-pitm", avif(iinf(1, 2), iprp(props, assoc)), 0, 0));
        cases.add(new Case(
                "pitm-without-ispe",
                avif(pitm(0, 2), iinf(1, 2), iprp(props, ipma(0, 0, new int[] {1, 1, 2}, new int[] {2, 2}))),
                0,
                0));
        cases.add(new Case("zero-width", avif(pitm(0, 1), iinf(1, 2), iprp(ipco(ispe(0, 32), pixi(8)), assoc)), 0, 0));
        cases.add(new Case(
                "zero-width-elsewhere",
                avif(pitm(0, 1), iinf(1, 2), iprp(ipco(ispe(64, 32), pixi(8), ispe(0, 5)), assoc)),
                0,
                0));
        byte[] valid = meta(0, pitm(0, 1), iinf(1, 2), iprp(props, assoc));
        byte[] newer = meta(1, pitm(0, 1), iinf(1, 2), iprp(ipco(ispe(111, 111), pixi(8)), assoc));
        cases.add(new Case("meta-v1-first", file(AVIF, newer, valid, box("mdat")), 64, 32));
        byte[] body = meta(0, pitm(0, 1), iinf(1, 2), iprp(props, assoc));
        cases.add(new Case("ftyp-major-only", file(ftyp("avif"), body), 64, 32));
        cases.add(new Case("ftyp-avis", file(ftyp("avis", "msf1"), body), 64, 32));
        cases.add(new Case("ftyp-compatible-only", file(ftyp("mif1", "mif1", "avif"), body), 64, 32));
        cases.add(new Case("ftyp-mif1-only", file(ftyp("mif1", "mif1", "miaf"), body), 0, 0));
        // without item information PHP's getimagesize gives no size and WordPress asks its own reader
        cases.add(new Case("grid-no-iinf", grid(false, true, false, false), 1024, 1024));
        cases.add(new Case("primary-last-no-iinf", primaryLast(0, 0, 0, false), 640, 480));
        cases.addAll(limits());
        cases.addAll(associationLimits());
        cases.addAll(tileLimits());
        cases.addAll(oddBoxes());
        return cases;
    }

    /** One item (1) with these properties and associations, with item information unless {@code bare}. */
    private static byte[] single(boolean bare, byte[] ipco, byte[] ipma) {
        return avif(pitm(0, 1), bare ? new byte[0] : iinf(1), iprp(ipco, ipma));
    }

    private static byte[] single(byte[] ipco, int... properties) {
        int[] entry = new int[properties.length + 1];
        entry[0] = 1;
        System.arraycopy(properties, 0, entry, 1, properties.length);
        return single(false, ipco, ipma(0, 0, entry));
    }

    private static byte[][] repeat(byte[] box, int count) {
        byte[][] boxes = new byte[count][];
        Arrays.fill(boxes, box);
        return boxes;
    }

    private static byte[][] with(byte[][] first, byte[]... then) {
        byte[][] all = Arrays.copyOf(first, first.length + then.length);
        System.arraycopy(then, 0, all, first.length, then.length);
        return all;
    }

    static final byte[] FREE = box("free");

    // ---- raw boxes whose counts need not match their bytes ----------------------------------------------------------

    private static byte[] u16(int value) {
        return new byte[] {(byte) (value >>> 8), (byte) value};
    }

    private static byte[] u32(int value) {
        return new byte[] {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }

    private static byte[] concat(byte[]... parts) {
        return file(parts);
    }

    /** An ipma that claims {@code entries} entries, followed by {@code body} as written. */
    private static byte[] rawIpma(int flags, int entries, byte[]... body) {
        return fullBox("ipma", 0, flags, u32(entries), concat(body));
    }

    /** One version 0 ipma entry: item, association count, then one byte (or two, when {@code wide}) per number. */
    private static byte[] entry(boolean wide, int item, int... numbers) {
        byte[][] parts = new byte[numbers.length + 2][];
        parts[0] = u16(item);
        parts[1] = new byte[] {(byte) numbers.length};
        for (int i = 0; i < numbers.length; i++) {
            parts[i + 2] = wide ? u16(numbers[i]) : new byte[] {(byte) numbers[i]};
        }
        return concat(parts);
    }

    private static byte[] entry(int item, int... numbers) {
        return entry(false, item, numbers);
    }

    /** A dimg that claims {@code count} references, followed by {@code ids}. */
    private static byte[] rawDimg(int from, int count, int... ids) {
        byte[][] parts = new byte[ids.length + 2][];
        parts[0] = u16(from);
        parts[1] = u16(count);
        for (int i = 0; i < ids.length; i++) {
            parts[i + 2] = u16(ids[i]);
        }
        return box("dimg", concat(parts));
    }

    private static int[] times(int value, int count) {
        int[] values = new int[count];
        Arrays.fill(values, value);
        return values;
    }

    private static int[] then(int[] first, int... more) {
        int[] all = Arrays.copyOf(first, first.length + more.length);
        System.arraycopy(more, 0, all, first.length, more.length);
        return all;
    }

    private static byte[] entries(int count, int firstItem) {
        byte[][] parts = new byte[count][];
        for (int i = 0; i < count; i++) {
            parts[i] = entry(firstItem + i);
        }
        return concat(parts);
    }

    private static Case sized(String name, byte[] bytes) {
        return new Case(name, bytes, 64, 32);
    }

    private static Case none(String name, byte[] bytes) {
        return new Case(name, bytes, 0, 0);
    }

    /** Properties 1 (64x32), 2 (a depth) and 3 (a free box). */
    static final byte[] THREE = ipco(ispe(64, 32), pixi(8), FREE);

    /** A file without item information, so only WordPress's own reader answers for it (PHP's gives no size). */
    private static byte[] bare(byte[]... metaChildren) {
        return avif(metaChildren);
    }

    /** How many associations the {@code ipma} boxes are read for (the primary item 1 gets 1 and 2 last). */
    static List<Case> associationLimits() {
        List<Case> cases = new ArrayList<>();
        byte[] p1 = pitm(0, 1);
        // associations dropped for their property number count: 32 of them leave none for the size
        cases.add(none(
                "assoc-32-dropped-numbers-first",
                bare(p1, iprp(THREE, rawIpma(1, 1, entry(true, 1, then(times(300, 32), 1, 2)))))));
        cases.add(sized(
                "assoc-30-dropped-numbers-first",
                bare(p1, iprp(THREE, rawIpma(1, 1, entry(true, 1, then(times(300, 30), 1, 2)))))));
        // once 32 are read the box ends before the next entry: a missing second entry is not read
        cases.add(sized(
                "assoc-32-then-missing-entry",
                bare(p1, iprp(THREE, rawIpma(0, 2, entry(1, then(times(3, 30), 1, 2)))))));
        cases.add(none(
                "assoc-31-then-missing-entry",
                bare(p1, iprp(THREE, rawIpma(0, 2, entry(1, then(times(3, 29), 1, 2)))))));
        // or before the next association: a missing 33rd is not read
        byte[] thirtyThree = entry(1, then(new int[] {1, 2}, times(3, 31)));
        cases.add(sized(
                "assoc-33rd-missing",
                bare(p1, iprp(THREE, rawIpma(0, 1, Arrays.copyOf(thirtyThree, thirtyThree.length - 1))))));
        // the count runs on across ipma boxes; the entry limit does not
        cases.add(none(
                "assoc-two-ipma-33",
                bare(
                        p1,
                        iprp(
                                THREE,
                                rawIpma(0, 1, entry(9, times(3, 20))),
                                rawIpma(0, 1, entry(1, then(times(3, 11), 1, 2)))))));
        cases.add(sized(
                "assoc-two-ipma-32",
                bare(
                        p1,
                        iprp(
                                THREE,
                                rawIpma(0, 1, entry(9, times(3, 20))),
                                rawIpma(0, 1, entry(1, then(times(3, 10), 1, 2)))))));
        cases.add(sized(
                "assoc-two-ipma-40-entries",
                bare(
                        p1,
                        iprp(
                                THREE,
                                rawIpma(0, 20, entries(20, 10)),
                                rawIpma(0, 20, entries(19, 40), entry(1, 1, 2))))));
        cases.add(none(
                "assoc-32-entries-of-items-above-255",
                bare(p1, iprp(THREE, rawIpma(0, 33, entries(32, 300), entry(1, 1, 2))))));
        // an item above 255: its associations are read, not counted, and its 33rd ends the box
        cases.add(sized(
                "assoc-item-300-31-uncounted",
                bare(p1, iprp(THREE, rawIpma(0, 2, entry(300, times(3, 31)), entry(1, 1, 2))))));
        cases.add(sized(
                "assoc-item-300-twice-20",
                bare(
                        p1,
                        iprp(
                                THREE,
                                rawIpma(0, 3, entry(300, times(3, 20)), entry(301, times(3, 20)), entry(1, 1, 2))))));
        cases.add(sized(
                "assoc-item-300-32-then-primary",
                bare(p1, iprp(THREE, rawIpma(0, 2, entry(300, times(3, 32)), entry(1, 1, 2))))));
        cases.add(none(
                "assoc-item-300-33-then-primary",
                bare(p1, iprp(THREE, rawIpma(0, 2, entry(300, times(3, 33)), entry(1, 1, 2))))));
        byte[] forty = entry(300, times(3, 40));
        cases.add(sized(
                "assoc-item-300-40-with-32-there",
                bare(p1, iprp(THREE, rawIpma(0, 2, entry(1, 1, 2), Arrays.copyOf(forty, 3 + 32))))));
        cases.add(none(
                "assoc-item-300-40-with-31-there",
                bare(p1, iprp(THREE, rawIpma(0, 2, entry(1, 1, 2), Arrays.copyOf(forty, 3 + 31))))));
        cases.add(none("assoc-item-300-cut-short", bare(p1, iprp(THREE, rawIpma(0, 2, entry(1, 1, 2), new byte[] {
            1, 44, 5, 3
        })))));
        cases.add(none(
                "assoc-31-then-item-300-then-primary",
                bare(p1, iprp(THREE, rawIpma(0, 3, entry(9, times(3, 31)), entry(300, 3, 3), entry(1, 1, 2))))));
        cases.add(sized(
                "assoc-primary-33-associations",
                bare(p1, iprp(THREE, rawIpma(0, 2, entry(1, then(new int[] {1, 2}, times(3, 31))), entry(2, 2))))));
        // property numbers above 255 are dropped, not cut to a byte
        cases.add(none("assoc-numbers-257-258", bare(p1, iprp(THREE, rawIpma(1, 1, entry(true, 1, 257, 258))))));
        return cases;
    }

    /** How many dimg references are read and kept (the primary item 1 has a size; item 2 alone has a depth). */
    static List<Case> tileLimits() {
        List<Case> cases = new ArrayList<>();
        byte[] p1 = pitm(0, 1);
        byte[] props = iprp(THREE, rawIpma(0, 2, entry(1, 1), entry(2, 2)));
        byte[] own = iprp(THREE, rawIpma(0, 1, entry(1, 1, 2)));
        // at most 16 references of each dimg are read, ids above 255 among them
        cases.add(none("tiles-16-dropped-then-2", bare(p1, props, iref(0, rawDimg(1, 17, then(times(300, 16), 2))))));
        cases.add(sized("tiles-15-dropped-then-2", bare(p1, props, iref(0, rawDimg(1, 16, then(times(300, 15), 2))))));
        cases.add(sized(
                "tiles-claims-20-has-17", bare(p1, props, iref(0, rawDimg(1, 20, then(new int[] {2}, times(5, 16)))))));
        cases.add(none(
                "tiles-claims-20-has-15", bare(p1, props, iref(0, rawDimg(1, 20, then(new int[] {2}, times(5, 14)))))));
        cases.add(sized("tiles-claims-17-has-16", bare(p1, props, iref(0, rawDimg(1, 17, then(times(5, 15), 2))))));
        cases.add(sized("tiles-claims-17-has-16-then-ipma", bare(p1, iref(0, rawDimg(1, 17, times(5, 16))), own)));
        cases.add(none("tiles-claims-3-has-1", bare(p1, props, iref(0, rawDimg(1, 3, 2)))));
        // at most 16 kept in all dimg boxes; ids above 255 are not kept and take no place
        cases.add(none(
                "tiles-16-kept-then-2", bare(p1, props, iref(0, rawDimg(1, 16, times(5, 16)), rawDimg(1, 1, 2)))));
        cases.add(none(
                "tiles-two-dimg-17",
                bare(p1, props, iref(0, rawDimg(1, 10, times(5, 10)), rawDimg(1, 7, then(times(5, 6), 2))))));
        cases.add(sized(
                "tiles-two-dimg-16",
                bare(p1, props, iref(0, rawDimg(1, 10, times(5, 10)), rawDimg(1, 6, then(times(5, 5), 2))))));
        cases.add(sized(
                "tiles-ids-300-take-no-place",
                bare(p1, props, iref(0, rawDimg(1, 10, times(300, 10)), rawDimg(1, 7, then(times(5, 6), 2))))));
        // a later dimg is still read once 16 are kept, and a short one ends the reading
        cases.add(none(
                "tiles-full-then-short-dimg", bare(p1, iref(0, rawDimg(1, 16, times(5, 16)), rawDimg(1, 5)), own)));
        cases.add(none("tiles-then-short-dimg", bare(p1, iref(0, rawDimg(1, 3, times(5, 3)), rawDimg(1, 5)), own)));
        cases.add(none(
                "tiles-full-then-one-byte-dimg",
                bare(p1, iref(0, rawDimg(1, 16, times(5, 16)), box("dimg", new byte[1])), own)));
        // a derived image above 255: its references are read, at most 16, and none is kept
        cases.add(sized(
                "tiles-from-300-then-2", bare(p1, props, iref(0, rawDimg(300, 16, times(5, 16)), rawDimg(1, 1, 2)))));
        cases.add(sized(
                "tiles-from-300-take-no-place",
                bare(p1, props, iref(0, rawDimg(300, 10, times(5, 10)), rawDimg(1, 7, then(times(5, 6), 2))))));
        cases.add(none(
                "tiles-from-300-then-full",
                bare(
                        p1,
                        props,
                        iref(0, rawDimg(300, 16, times(5, 16)), rawDimg(1, 16, times(5, 16)), rawDimg(1, 1, 2)))));
        cases.add(none("tiles-from-300-short", bare(p1, iref(0, rawDimg(300, 5)), own)));
        cases.add(sized("tiles-from-300-claims-20-has-17", bare(p1, iref(0, rawDimg(300, 20, times(5, 17))), own)));
        cases.add(sized("tiles-from-300-claims-17-has-17", bare(p1, iref(0, rawDimg(300, 17, times(5, 17))), own)));
        // ids are not cut to a byte
        cases.add(none("tiles-id-258", bare(p1, props, iref(0, rawDimg(1, 1, 258)))));
        cases.add(none("tiles-from-257", bare(p1, props, iref(0, rawDimg(257, 1, 2)))));
        return cases;
    }

    /** Short, repeated, unknown-version and open-ended boxes. */
    static List<Case> oddBoxes() {
        List<Case> cases = new ArrayList<>();
        byte[] own = iprp(THREE, rawIpma(0, 1, entry(1, 1, 2)));
        // a pitm too short for its id: the id comes from the following bytes and the reading goes on after the box
        cases.add(sized("pitm-short-then-valid", bare(box("pitm", new byte[] {0, 0, 0, 0, 0}), pitm(0, 1), own)));
        cases.add(sized("pitm-empty-then-valid", bare(box("pitm", new byte[] {0, 0, 0, 0}), pitm(0, 1), own)));
        cases.add(sized(
                "pitm-short-reads-item-0",
                bare(box("pitm", new byte[] {0, 0, 0, 0, 0}), iprp(THREE, rawIpma(0, 1, entry(0, 1, 2))))));
        cases.add(none(
                "pitm-short-reads-256",
                bare(box("pitm", new byte[] {0, 0, 0, 0, 1}), iprp(THREE, rawIpma(0, 1, entry(256, 1, 2))))));
        cases.add(sized("pitm-v1-short-then-valid", bare(box("pitm", new byte[] {1, 0, 0, 0, 0, 0}), pitm(0, 1), own)));
        cases.add(none("pitm-v1-short-reads-65536", bare(box("pitm", new byte[] {1, 0, 0, 0, 0, 1}), pitm(0, 1), own)));
        // but a full box too short for its version and flags ends the reading
        cases.add(none("pitm-without-version", bare(box("pitm"), pitm(0, 1), own)));
        cases.add(none("pitm-half-version", bare(box("pitm", new byte[] {0, 0}), pitm(0, 1), own)));
        cases.add(sized("pitm-id-0", bare(pitm(0, 0), iprp(THREE, rawIpma(0, 1, entry(0, 1, 2))))));
        cases.add(sized("pitm-twice-last-wins", bare(pitm(0, 7), pitm(0, 1), own)));
        cases.add(none("pitm-v2", bare(fullBox("pitm", 2, 0, u16(1)), own)));
        // the derived images read after ipma before ipco complete the reading
        byte[] props = ipco(ispe(64, 32), pixi(10, 10, 10));
        byte[] assoc = ipma(0, 0, new int[] {1, 1, 2});
        cases.add(sized("iref-v1-after-early-ipma", bare(pitm(0, 1), iprp(assoc, props), iref(1, dimg(1, 2)))));
        cases.add(sized(
                "iref-v1-32-bit-ids",
                bare(pitm(0, 1), iprp(assoc, props), iref(1, box("dimg", u32(1), u16(1), u32(2))))));
        cases.add(none("iref-v2", bare(pitm(0, 1), iprp(assoc, props), iref(2, dimg(1, 2)))));
        cases.add(none("ipma-v2", bare(pitm(0, 1), iprp(props, fullBox("ipma", 2, 0, u32(1), u16(1), new byte[] {2, 1, 2
        })))));
        cases.add(sized(
                "association-order",
                bare(pitm(0, 1), iprp(ipco(ispe(10, 10), ispe(64, 32), pixi(8)), ipma(0, 0, new int[] {1, 2, 1, 3})))));
        cases.add(sized("association-to-property-0", bare(pitm(0, 1), iprp(props, ipma(0, 0, new int[] {1, 0, 1, 2
        })))));
        cases.add(none(
                "primary-without-ispe-tile-with-one",
                bare(pitm(0, 1), iprp(props, ipma(0, 0, new int[] {1, 2}, new int[] {2, 1, 2})), iref(0, dimg(1, 2)))));
        // property numbers start again at 1 in each ipco; the first size kept under a number wins
        cases.add(new Case(
                "two-ipco-number-1",
                bare(pitm(0, 1), iprp(ipco(ispe(10, 10)), ipco(ispe(64, 32), pixi(8)), ipma(0, 0, new int[] {1, 1, 2
                }))),
                10,
                10));
        cases.add(none(
                "two-ipco-numbers-2-3",
                bare(pitm(0, 1), iprp(ipco(ispe(10, 10)), ipco(ispe(64, 32), pixi(8)), ipma(0, 0, new int[] {1, 2, 3
                })))));
        cases.add(sized(
                "two-ipco-depth-first", bare(pitm(0, 1), iprp(ipco(pixi(8)), ipco(ispe(64, 32)), ipma(0, 0, new int[] {
                    1, 1
                })))));
        cases.add(none(
                "two-iprp",
                bare(pitm(0, 1), iprp(ipco(ispe(10, 10))), iprp(ipco(ispe(64, 32), pixi(8)), ipma(0, 0, new int[] {
                    1, 2, 3
                })))));
        // a malformed property ends the reading even with a valid one after it
        cases.add(none(
                "short-ispe-then-valid",
                bare(
                        pitm(0, 1),
                        iprp(
                                ipco(fullBox("ispe", 0, 0, new byte[7]), ispe(64, 32), pixi(8)),
                                ipma(0, 0, new int[] {1, 2, 3})))));
        cases.add(none(
                "short-av1C-then-pixi",
                bare(pitm(0, 1), iprp(ipco(ispe(64, 32), box("av1C", new byte[1]), pixi(8)), ipma(0, 0, new int[] {
                    1, 1, 3
                })))));
        cases.add(none(
                "short-pixi-then-valid",
                bare(
                        pitm(0, 1),
                        iprp(
                                ipco(ispe(64, 32), fullBox("pixi", 0, 0, new byte[] {3, 8}), pixi(8)),
                                ipma(0, 0, new int[] {1, 1, 3})))));
        // the file type and the top-level boxes
        byte[] meta = meta(0, pitm(0, 1), iprp(props, assoc));
        cases.add(none(
                "ftyp-avif-as-minor-version", file(box("ftyp", ascii("mif1"), ascii("avif"), ascii("mif1")), meta)));
        cases.add(none("free-size-0-before-meta", file(AVIF, concat(u32(0), ascii("free")), meta)));
        byte[] open = meta.clone();
        Arrays.fill(open, 0, 4, (byte) 0);
        cases.add(sized("meta-size-0", file(AVIF, open)));
        cases.add(sized(
                "free-64-bit-before-meta",
                file(AVIF, concat(u32(1), ascii("free"), u32(0), u32(20), new byte[4]), meta)));
        return cases;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    /** The edges of the reader's limits. */
    static List<Case> limits() {
        List<Case> cases = new ArrayList<>();
        byte[] props = ipco(ispe(64, 32), pixi(8));
        for (int id : new int[] {255, 256}) {
            cases.add(new Case(
                    "pitm-" + id,
                    avif(pitm(1, id), iinf(item(id, "av01")), iprp(props, ipma(1, 0, new int[] {id, 1, 2}))),
                    id == 255 ? 64 : 0,
                    id == 255 ? 32 : 0));
        }
        // an ispe numbered 255 or 256: the free boxes before it take numbers too
        for (int number : new int[] {255, 256}) {
            byte[] ipco = ipco(with(with(new byte[][] {pixi(8)}, repeat(FREE, number - 2)), ispe(64, 32)));
            cases.add(new Case(
                    "ispe-number-" + number,
                    single(false, ipco, ipma(0, 1, new int[] {1, number, 1})),
                    number == 255 ? 64 : 0,
                    number == 255 ? 32 : 0));
        }
        // the primary item's size as the ninth or the tenth ispe
        for (int count : new int[] {9, 10}) {
            byte[] ipco = ipco(with(repeat(ispe(10, 10), count - 1), ispe(64, 32), pixi(8)));
            cases.add(new Case(
                    "ispe-" + count + "th", single(ipco, count, count + 1), count == 9 ? 64 : 0, count == 9 ? 32 : 0));
        }
        cases.add(new Case("pixi-unequal", single(ipco(ispe(64, 32), pixi(8, 10, 8)), 1, 2), 0, 0));
        cases.add(new Case("pixi-no-channel", single(ipco(ispe(64, 32), pixi()), 1, 2), 0, 0));
        int[] many = new int[33];
        Arrays.fill(many, 8);
        cases.add(new Case("pixi-33-channels", single(ipco(ispe(64, 32), pixi(many)), 1, 2), 64, 32));
        cases.add(new Case("pixi-34-channels", single(ipco(ispe(64, 32), pixi(Arrays.copyOf(many, 34))), 1, 2), 0, 0));
        cases.add(new Case("pixi-depth-0", single(ipco(ispe(64, 32), pixi(0, 0, 0)), 1, 2), 0, 0));
        cases.add(new Case("av1C-twelve-without-high", single(ipco(ispe(64, 32), av1C(0x20)), 1, 2), 0, 0));
        cases.add(new Case("av1C-twelve", single(ipco(ispe(64, 32), av1C(0x60)), 1, 2), 64, 32));
        cases.add(new Case("av1C-mono", single(ipco(ispe(64, 32), av1C(0x10)), 1, 2), 64, 32));
        cases.add(new Case(
                "av1C-short", single(ipco(ispe(64, 32), box("av1C", new byte[] {(byte) 0x81, 0})), 1, 2), 0, 0));
        byte[] ispeV1 = fullBox("ispe", 1, 0, new byte[] {0, 0, 0, 111, 0, 0, 0, 111});
        cases.add(new Case("ispe-v1-ignored", single(ipco(ispeV1, ispe(64, 32), pixi(8)), 1, 2, 3), 64, 32));
        byte[] pixiV1 = fullBox("pixi", 1, 0, new byte[] {1, 8});
        cases.add(new Case("pixi-v1-ignored", single(ipco(ispe(64, 32), pixiV1), 1, 2), 0, 0));
        cases.add(new Case("ispe-short", single(ipco(fullBox("ispe", 0, 0, new byte[7]), pixi(8)), 1, 2), 0, 0));
        cases.add(new Case(
                "meta-v1-only",
                file(AVIF, meta(1, pitm(0, 1), iinf(1), iprp(props, ipma(0, 0, new int[] {1, 1, 2})))),
                0,
                0));
        // the primary item's entry after 31 or 32 others (with no associations)
        for (int others : new int[] {31, 32}) {
            int[][] entries = new int[others + 1][];
            for (int i = 0; i < others; i++) {
                entries[i] = new int[] {i + 2};
            }
            entries[others] = new int[] {1, 1, 2};
            cases.add(new Case(
                    "ipma-entry-" + (others + 1),
                    single(false, props, ipma(0, 0, entries)),
                    others == 31 ? 64 : 0,
                    others == 31 ? 32 : 0));
        }
        // the primary item's depth as its 32nd or 33rd association
        for (int before : new int[] {30, 31}) {
            int[] entry = new int[before + 3];
            entry[0] = 1;
            Arrays.fill(entry, 1, before + 1, 3);
            entry[before + 1] = 1;
            entry[before + 2] = 2;
            cases.add(new Case(
                    "association-" + (before + 2),
                    single(false, ipco(ispe(64, 32), pixi(8), FREE), ipma(0, 0, entry)),
                    before == 30 ? 64 : 0,
                    before == 30 ? 32 : 0));
        }
        // the depth only on a tile 3 or 4 levels down a chain of derived images
        for (int levels : new int[] {3, 4}) {
            byte[][] refs = new byte[levels][];
            int[][] entries = new int[levels + 1][];
            entries[0] = new int[] {1, 1};
            for (int i = 0; i < levels; i++) {
                refs[i] = dimg(i + 1, i + 2);
                entries[i + 1] = i == levels - 1 ? new int[] {i + 2, 2} : new int[] {i + 2};
            }
            cases.add(new Case(
                    "tile-depth-" + levels + "-levels",
                    avif(pitm(0, 1), iprp(props, ipma(0, 0, entries)), iref(0, refs)),
                    levels == 3 ? 64 : 0,
                    levels == 3 ? 32 : 0));
        }
        // the depth only on the 16th or the 17th tile
        for (int tiles : new int[] {16, 17}) {
            int[] ids = new int[tiles];
            for (int i = 0; i < tiles; i++) {
                ids[i] = i + 2;
            }
            cases.add(new Case(
                    "tile-" + tiles,
                    avif(
                            pitm(0, 1),
                            iprp(props, ipma(0, 0, new int[] {1, 1}, new int[] {tiles + 1, 2})),
                            iref(0, dimg(1, ids))),
                    tiles == 16 ? 64 : 0,
                    tiles == 16 ? 32 : 0));
        }
        // free boxes before the meta box, up to the box limit (ftyp, meta and six boxes inside it are read too)
        for (int free = 4086; free <= 4089; free++) {
            byte[] meta = meta(0, pitm(0, 1), iprp(props, ipma(0, 0, new int[] {1, 1, 2})));
            cases.add(new Case(
                    "boxes-" + (free + 8),
                    file(with(with(new byte[][] {AVIF}, repeat(FREE, free)), meta)),
                    free + 8 < 4096 ? 64 : 0,
                    free + 8 < 4096 ? 32 : 0));
        }
        byte[] huge = new byte[16];
        huge[3] = 1;
        System.arraycopy("free".getBytes(StandardCharsets.US_ASCII), 0, huge, 4, 4);
        huge[11] = 1; // upper 32 bits 1
        byte[] body = meta(0, pitm(0, 1), iinf(1), iprp(props, ipma(0, 0, new int[] {1, 1, 2})));
        cases.add(new Case("size-above-4-gib", file(AVIF, huge, body), 0, 0));
        // the brand avif in slot 31 to 35 of the file type box (slot 0 major, slot 1 the minor version)
        for (int slot = 31; slot <= 35; slot++) {
            String[] brands = new String[slot - 1];
            Arrays.fill(brands, "mif1");
            brands[slot - 2] = "avif";
            cases.add(new Case(
                    "ftyp-slot-" + slot, file(ftyp("mif1", brands), body), slot <= 33 ? 64 : 0, slot <= 33 ? 32 : 0));
        }
        cases.add(new Case("ftyp-not-first", file(FREE, AVIF, body), 0, 0));
        return cases;
    }
}
