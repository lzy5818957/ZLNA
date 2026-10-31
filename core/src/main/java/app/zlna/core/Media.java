package app.zlna.core;

import java.io.*;
import java.util.*;

/** Immutable metadata; file content is opened only when a player asks for it. */
public final class Media {
    private Media() {}
    public interface Source { InputStream open(long offset) throws IOException; }
    public static final class Node {
        public final String id, parent, title, mime;
        public final long size;
        public final Source source;
        public Node(String id, String parent, String title, String mime, long size, Source source) {
            this.id = id; this.parent = parent; this.title = title; this.mime = mime;
            this.size = size; this.source = source;
        }
        public boolean directory() { return source == null; }
    }
    public static final class Library {
        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private final Map<String, List<Node>> children = new HashMap<>();
        public final long updateId = (System.currentTimeMillis() / 1000) & 0xffffffffL;
        public Library(List<Node> entries) {
            for (Node n : entries) {
                if (nodes.put(n.id, n) != null) throw new IllegalArgumentException("Duplicate media ID");
                children.computeIfAbsent(n.parent, k -> new ArrayList<>()).add(n);
            }
            if (nodes.get("0") == null || !nodes.get("0").directory()) throw new IllegalArgumentException("Missing root");
            for (List<Node> list : children.values()) list.sort(Comparator.comparing((Node n) -> !n.directory()).thenComparing(n -> n.title, String.CASE_INSENSITIVE_ORDER).thenComparing(n -> n.id));
        }
        public Node get(String id) { return nodes.get(id); }
        public List<Node> children(String id) { return Collections.unmodifiableList(children.getOrDefault(id, Collections.emptyList())); }
        public int videoCount() { int count = 0; for (Node n : nodes.values()) if (!n.directory()) count++; return count; }
        public String protocols() {
            Set<String> values = new TreeSet<>();
            for (Node n : nodes.values()) if (!n.directory()) values.add(protocol(n.mime));
            return String.join(",", values);
        }
    }
    public static String protocol(String mime) { return "http-get:*:" + mime + ":DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"; }
}
