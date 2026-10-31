package app.zlna.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** GENA initial events, renewal and unsubscribe for immutable per-run media catalogs. */
final class Eventing implements AutoCloseable {
    private static final class Subscription {
        String sid, path; InetAddress peer; URI callback; long expires;
    }
    private final Map<String, Subscription> subscriptions = new HashMap<>();
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64));
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Consumer<String> log;
    private volatile boolean closed;
    Eventing(Consumer<String> log) { this.log = log; }
    synchronized HttpServer.Response handle(HttpServer.Request r, String state) {
        subscriptions.values().removeIf(s -> s.expires <= System.nanoTime());
        String sid = r.headers.get("sid");
        Subscription s = subscriptions.get(sid);
        if (r.method.equals("UNSUBSCRIBE")) {
            if (s == null || !s.peer.equals(r.peer) || !s.path.equals(r.path) || r.headers.containsKey("callback") || r.headers.containsKey("nt")) return HttpServer.Response.text(412, "Unknown subscription");
            subscriptions.remove(sid); return HttpServer.Response.text(200, "");
        }
        if (!r.method.equals("SUBSCRIBE")) return HttpServer.Response.text(405, "Method not allowed");
        boolean initial = sid == null;
        if (initial) {
            if (subscriptions.size() >= 64) return HttpServer.Response.text(503, "Too many subscriptions");
            try {
                String callback = r.headers.getOrDefault("callback", "");
                if (!"upnp:event".equals(r.headers.get("nt")) || !callback.startsWith("<") || !callback.endsWith(">")) throw new IllegalArgumentException();
                URI uri = URI.create(callback.substring(1, callback.length() - 1));
                // Pin notifications to the subscriber IP; no DNS, redirects, or third-party callbacks.
                if (!"http".equals(uri.getScheme()) || !r.peer.getHostAddress().equals(uri.getHost()) || uri.getUserInfo() != null || uri.getFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
                s = new Subscription(); s.sid = "uuid:" + UUID.randomUUID(); s.path = r.path; s.peer = r.peer; s.callback = uri;
                subscriptions.put(s.sid, s);
            } catch (IllegalArgumentException e) { return HttpServer.Response.text(412, "Invalid callback"); }
        } else if (s == null || !s.peer.equals(r.peer) || !s.path.equals(r.path) || r.headers.containsKey("callback") || r.headers.containsKey("nt")) return HttpServer.Response.text(412, "Unknown subscription");
        s.expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(1800);
        HttpServer.Response response = HttpServer.Response.text(200, "");
        response.headers.put("SID", s.sid); response.headers.put("TIMEOUT", "Second-1800");
        if (initial) {
            Subscription subscription = s;
            response.afterSend = () -> { try { worker.execute(() -> notify(subscription, state)); } catch (RejectedExecutionException ignored) {} };
        }
        return response;
    }
    private void notify(Subscription s, String state) {
        try (Socket socket = new Socket()) {
            sockets.add(socket); if (closed) return;
            socket.connect(new InetSocketAddress(s.peer, s.callback.getPort() < 0 ? 80 : s.callback.getPort()), 3000); socket.setSoTimeout(3000);
            String path = s.callback.getRawPath(); if (path == null || path.isEmpty()) path = "/";
            if (s.callback.getRawQuery() != null) path += "?" + s.callback.getRawQuery();
            byte[] data = ("<?xml version=\"1.0\"?><e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">" + state + "</e:propertyset>").getBytes(StandardCharsets.UTF_8);
            String header = "NOTIFY " + path + " HTTP/1.1\r\nHOST: " + s.callback.getRawAuthority() + "\r\nCONTENT-TYPE: text/xml; charset=\"utf-8\"\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\nSID: " + s.sid + "\r\nSEQ: 0\r\nCONTENT-LENGTH: " + data.length + "\r\nCONNECTION: close\r\n\r\n";
            socket.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().write(data); socket.getOutputStream().flush();
            socket.getInputStream().read();
        } catch (IOException e) { log.accept("Event callback: " + e.getMessage()); }
        finally { sockets.removeIf(Socket::isClosed); }
    }
    static String property(String name, String value) { return "<e:property>" + Xml.tag(name, value) + "</e:property>"; }
    public synchronized void close() { closed = true; subscriptions.clear(); worker.shutdownNow(); for (Socket s : sockets) try { s.close(); } catch (IOException ignored) {} }
}
