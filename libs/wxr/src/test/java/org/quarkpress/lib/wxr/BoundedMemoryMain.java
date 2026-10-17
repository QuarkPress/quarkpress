package org.quarkpress.lib.wxr;

/** Entry point for {@link BoundedMemoryTest}: reads a generated huge value and prints what happened. */
public final class BoundedMemoryMain {

    private BoundedMemoryMain() {}

    public static void main(String[] args) {
        if (!args[0].equals("cdata") && !args[0].equals("text")) {
            throw new IllegalArgumentException(args[0]);
        }
        WxrReadOptions options = WxrReadOptions.LENIENT.withMaxCharsPerValue(1 << 20);
        try (WxrReader reader =
                WxrReader.open(GeneratedWxr.oneHugeValue(200_000_000L, args[0].equals("cdata")), options)) {
            while (reader.hasNext()) {
                reader.next();
            }
            System.out.println("no error");
        } catch (WxrException e) {
            System.out.println(e.kind());
        }
    }
}
