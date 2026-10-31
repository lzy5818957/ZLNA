package app.zlna.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Small, bounded HTTP/1.1 server. Every response closes the connection. */
public final class HttpServer implements AutoCloseable {
    public interface Handler { Response handle(Request request) throws Exception; }
    public static final class Request {
        public final String method, path, baseUrl;
        public final InetAddress peer;
        public final Map<String, String> headers;
        public final byte[] body;
        Request(String method, String path, String baseUrl, InetAddress peer, Map<String, String> headers, byte[] body) {
            this.method = method; this.path = path; this.baseUrl = baseUrl; this.peer = peer; this.headers = headers; this.body = body;
        }
    }
    public static final class Response implements AutoCloseable {
        public int status;
        public final Map<String, String> headers = new LinkedHashMap<>();
        public final InputStream body;
        public final long length;
        public Runnable afterSend;
        public Runnable afterFirstBodyWrite;
        public Response(int status, String type, InputStream body, long length) {
            this.status = status; this.body = body; this.length = length; headers.put("Content-Type", type);
        }
        public static Response text(int status, String type, String text) {
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            return new Response(status, type, new ByteArrayInputStream(data), data.length);
        }
        public static Response text(int status, String text) { return text(status, "text/plain; charset=utf-8", text); }
        public void close() throws IOException { if (body != null) body.close(); }
    }
    private final Handler handler;
    private final Predicate<InetAddress> allowed;
    private final Consumer<String> log;
    private final ServerSocket listener;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(12, 12, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(24));
    private volatile boolean closed;

    public HttpServer(int port, Handler handler, Predicate<InetAddress> allowed, Consumer<String> log) throws IOException {
        this.handler = handler; this.allowed = allowed; this.log = log;
        listener = new ServerSocket();
        try { listener.setReuseAddress(true); listener.bind(new InetSocketAddress(port)); }
        catch (IOException e) { listener.close(); workers.shutdownNow(); throw e; }
    }
    public int port() { return listener.getLocalPort(); }
    public void start() { Thread t = new Thread(this::accept, "zlna-http"); t.setDaemon(true); t.start(); }
    private void accept() {
        while (!closed) {
            try {
                Socket socket = listener.accept();
                if (closed || !allowed.test(socket.getInetAddress())) { socket.close(); continue; }
                socket.setSoTimeout(10000); socket.setTcpNoDelay(true);
                clients.add(socket);
                try { workers.execute(() -> serve(socket)); }
                catch (RejectedExecutionException e) { clients.remove(socket); socket.close(); }
            } catch (IOException e) { if (!closed) log.accept("HTTP accept: " + e); }
        }
    }
    private void serve(Socket socket) {
        try (Socket ignored = socket) {
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
            Request request;
            try { request = read(in, socket); }
            catch (Exception e) { try (Response r = Response.text(400, "Invalid HTTP request")) { write(out, r, false); } return; }
            Response result;
            try { result = handler.handle(request); }
            catch (Exception e) { log.accept(request.method + " " + request.path + ": " + e); result = Response.text(500, "ZLNA could not complete this request. See ZLNA diagnostics."); }
            try (Response response = result) {
                log.accept(request.method + " " + request.path.substring(0, Math.min(request.path.length(), 160)) + " → " + response.status + " (" + response.length + " bytes" + (response.headers.containsKey("Content-Range") ? ", " + response.headers.get("Content-Range") : "") + ")");
                write(out, response, request.method.equals("HEAD"));
                if (response.afterSend != null) response.afterSend.run();
            }
        } catch (IOException e) { if (!closed) log.accept("HTTP connection: " + e.getMessage()); }
        finally { clients.remove(socket); }
    }
    private static Request read(InputStream in, Socket socket) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        String[] first = line(in, 8192, deadline).split(" ");
        if (first.length != 3 || !first[2].matches("HTTP/1\\.[01]") || !first[1].startsWith("/")) throw new IOException("Request line");
        Map<String, String> headers = new HashMap<>();
        int total = 0;
        while (true) {
            String s = line(in, 8192, deadline); total += s.length();
            if (total > 32768) throw new IOException("Headers too large");
            if (s.isEmpty()) break;
            int colon = s.indexOf(':');
            if (colon <= 0) throw new IOException("Header");
            String key = s.substring(0, colon).toLowerCase(Locale.ROOT);
            if (headers.put(key, s.substring(colon + 1).trim()) != null) throw new IOException("Duplicate header");
        }
        if (headers.containsKey("transfer-encoding") || headers.containsKey("expect")) throw new IOException("Unsupported request framing");
        int length;
        try { length = Integer.parseInt(headers.getOrDefault("content-length", "0")); } catch (NumberFormatException e) { throw new IOException(e); }
        if (length < 0 || length > 65536) throw new IOException("Body too large");
        byte[] data = new byte[length]; int pos = 0;
        while (pos < length) {
            if (System.nanoTime() > deadline) throw new IOException("Request timeout");
            int n = in.read(data, pos, length - pos); if (n < 0) throw new EOFException(); pos += n;
        }
        String path = first[1].split("\\?", 2)[0];
        // Use the address the client actually reached, never its untrusted Host header.
        String base = "http://" + socket.getLocalAddress().getHostAddress() + ":" + socket.getLocalPort();
        return new Request(first[0], path, base, socket.getInetAddress(), headers, data);
    }
    private static String line(InputStream in, int max, long deadline) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        while (buf.size() < max) {
            if (System.nanoTime() > deadline) throw new IOException("Request timeout");
            int b = in.read(); if (b < 0) throw new EOFException();
            if (b == 10) return buf.toString(StandardCharsets.ISO_8859_1.name()).replaceFirst("\\r$", "");
            buf.write(b);
        }
        throw new IOException("Line too long");
    }
    private static void write(OutputStream out, Response r, boolean head) throws IOException {
        String reason = switch (r.status) { case 200 -> "OK"; case 206 -> "Partial Content"; case 400 -> "Bad Request"; case 404 -> "Not Found"; case 405 -> "Method Not Allowed"; case 412 -> "Precondition Failed"; case 416 -> "Range Not Satisfiable"; case 503 -> "Service Unavailable"; default -> "Internal Server Error"; };
        StringBuilder header = new StringBuilder("HTTP/1.1 " + r.status + " " + reason + "\r\n");
        header.append("Server: ").append(Upnp.SERVER).append("\r\nDate: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC))).append("\r\nConnection: close\r\nContent-Length: ").append(r.length).append("\r\n");
        for (Map.Entry<String, String> h : r.headers.entrySet()) header.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        out.write(header.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1));
        if (!head && r.body != null) {
            byte[] buf = new byte[128 * 1024]; long remaining = r.length;
            while (remaining > 0) {
                int n = r.body.read(buf, 0, (int)Math.min(buf.length, remaining));
                if (n < 0) throw new EOFException("Media changed while streaming");
                if (n == 0) continue;
                out.write(buf, 0, n); remaining -= n;
                if (r.afterFirstBodyWrite != null) {
                    out.flush(); r.afterFirstBodyWrite.run(); r.afterFirstBodyWrite = null;
                }
            }
        }
        out.flush();
    }
    @Override public void close() {
        closed = true;
        try { listener.close(); } catch (IOException ignored) {}
        for (Socket s : clients) try { s.close(); } catch (IOException ignored) {}
        workers.shutdownNow();
    }
}
