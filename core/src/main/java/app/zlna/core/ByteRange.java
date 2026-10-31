package app.zlna.core;

/** RFC 9110 single byte range. Multi-range requests are ignored (full 200 response). */
public final class ByteRange {
    public final long start, length;
    public final boolean partial;
    private ByteRange(long start, long length, boolean partial) { this.start = start; this.length = length; this.partial = partial; }
    public static ByteRange parse(String header, long size) {
        if (size < 0) throw new IllegalArgumentException("Unknown size");
        if (header == null || !header.startsWith("bytes=") || header.contains(",")) return new ByteRange(0, size, false);
        try {
            String s = header.substring(6).trim();
            if (!s.matches("[0-9]*-[0-9]*") || s.equals("-") || size == 0) throw new IllegalArgumentException();
            int dash = s.indexOf('-');
            long start, end;
            if (dash == 0) {
                long suffix = Long.parseLong(s.substring(1));
                if (suffix == 0) throw new IllegalArgumentException();
                start = size - Math.min(size, suffix); end = size - 1;
            } else {
                start = Long.parseLong(s.substring(0, dash));
                end = dash == s.length() - 1 ? size - 1 : Math.min(size - 1, Long.parseLong(s.substring(dash + 1)));
            }
            if (start >= size || end < start) throw new IllegalArgumentException();
            return new ByteRange(start, end - start + 1, true);
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid range", e); }
    }
}
