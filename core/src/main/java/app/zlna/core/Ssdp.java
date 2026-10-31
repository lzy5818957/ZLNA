package app.zlna.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Discovers all local IPv4 LANs, including Android's hotspot interface, without a default-network dependency. */
public final class Ssdp implements AutoCloseable {
    private static final InetSocketAddress GROUP = new InetSocketAddress("239.255.255.250", 1900);
    private static final class Link implements AutoCloseable {
        final NetworkInterface network; final InetAddress address; final short prefix; final MulticastSocket sender;
        Link(NetworkInterface network, InterfaceAddress address) throws IOException {
            this.network = network; this.address = address.getAddress(); prefix = address.getNetworkPrefixLength();
            sender = new MulticastSocket(new InetSocketAddress(this.address, 0));
            try { sender.setNetworkInterface(network); sender.setTimeToLive(2); }
            catch (IOException e) { sender.close(); throw e; }
        }
        public void close() { sender.close(); }
    }
    private final String uuid;
    private final int httpPort;
    private final Consumer<String> log;
    private final Consumer<List<String>> networks;
    private final MulticastSocket listener;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Link> links = new LinkedHashMap<>();
    private List<String> lastUrls;
    private final AtomicInteger pending = new AtomicInteger();
    private volatile boolean closed;
    public Ssdp(String uuid, int httpPort, Consumer<String> log, Consumer<List<String>> networks) throws IOException {
        this.uuid = uuid; this.httpPort = httpPort; this.log = log; this.networks = networks;
        listener = new MulticastSocket(null);
        try { listener.setReuseAddress(true); listener.bind(new InetSocketAddress(1900)); }
        catch (IOException e) { listener.close(); timer.shutdownNow(); throw e; }
    }
    public void start() {
        timer.scheduleWithFixedDelay(this::refresh, 0, 5, TimeUnit.SECONDS);
        timer.scheduleWithFixedDelay(() -> advertise("ssdp:alive"), 30, 90, TimeUnit.SECONDS);
        Thread t = new Thread(this::receive, "zlna-ssdp"); t.setDaemon(true); t.start();
    }
    public static boolean eligible(String name, InetAddress address) {
        String lower = name.toLowerCase(Locale.ROOT);
        return address instanceof Inet4Address && address.isSiteLocalAddress() && !lower.matches("^(lo|tun|tap|utun|ipsec|ppp|rmnet|ccmni|pdp|wg|tailscale|dummy).*" );
    }
    public static boolean sameSubnet(InetAddress a, InetAddress b, int prefix) {
        byte[] aa = a.getAddress(), bb = b.getAddress();
        if (aa.length != 4 || bb.length != 4 || prefix < 1 || prefix > 32) return false;
        for (int i = 0; i < 4; i++) { int bits = Math.min(8, Math.max(0, prefix - 8 * i)); int mask = (0xff << (8 - bits)) & 0xff; if ((aa[i] & mask) != (bb[i] & mask)) return false; }
        return true;
    }
    public synchronized boolean allows(InetAddress peer) { for (Link l : links.values()) if (sameSubnet(l.address, peer, l.prefix)) return true; return false; }
    private synchronized void refresh() {
        if (closed) return;
        Set<String> present = new HashSet<>(); boolean changed = false;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return;
            for (NetworkInterface network : Collections.list(interfaces)) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InterfaceAddress address : network.getInterfaceAddresses()) {
                    if (!eligible(network.getName(), address.getAddress())) continue;
                    String key = network.getName() + "/" + address.getAddress().getHostAddress(); present.add(key);
                    if (links.containsKey(key)) continue;
                    Link link = null;
                    try {
                        link = new Link(network, address); listener.joinGroup(GROUP, network); links.put(key, link); changed = true;
                        announce(link, "ssdp:alive");
                        log.accept("SSDP joined " + key);
                    } catch (IOException e) { if (link != null) link.close(); log.accept("SSDP join " + key + ": " + e.getMessage()); }
                }
            }
            Iterator<Map.Entry<String, Link>> it = links.entrySet().iterator();
            while (it.hasNext()) { Map.Entry<String, Link> entry = it.next(); if (!present.contains(entry.getKey())) { Link l = entry.getValue(); announce(l, "ssdp:byebye"); try { listener.leaveGroup(GROUP, l.network); } catch (IOException ignored) {} l.close(); it.remove(); changed = true; } }
            if (changed || lastUrls == null) {
                List<String> urls = new ArrayList<>(); for (Link l : links.values()) urls.add(location(l));
                if (!urls.equals(lastUrls)) { lastUrls = urls; networks.accept(urls); }
            }
        } catch (Exception e) { log.accept("Network refresh: " + e); }
    }
    public static List<String> targets(String st, String uuid) {
        List<String> all = Arrays.asList("upnp:rootdevice", "uuid:" + uuid, Upnp.DEVICE, Upnp.CDS, Upnp.CM);
        return st.equals("ssdp:all") ? all : all.contains(st) ? Collections.singletonList(st) : Collections.emptyList();
    }
    private String usn(String target) { return target.equals("uuid:" + uuid) ? target : "uuid:" + uuid + "::" + target; }
    private String location(Link l) { return "http://" + l.address.getHostAddress() + ":" + httpPort + "/description.xml"; }
    private void receive() {
        while (!closed) {
            try {
                byte[] bytes = new byte[8192]; DatagramPacket packet = new DatagramPacket(bytes, bytes.length); listener.receive(packet);
                if (!allows(packet.getAddress())) continue;
                String[] lines = new String(bytes, 0, packet.getLength(), StandardCharsets.US_ASCII).split("\r?\n");
                if (lines.length == 0 || !lines[0].equals("M-SEARCH * HTTP/1.1")) continue;
                Map<String, String> headers = new HashMap<>();
                for (String line : lines) { int colon = line.indexOf(':'); if (colon > 0) headers.put(line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).trim()); }
                if (!"\"ssdp:discover\"".equalsIgnoreCase(headers.get("man"))) continue;
                List<String> matches = targets(headers.getOrDefault("st", ""), uuid); if (matches.isEmpty()) continue;
                int mx; try { mx = Math.min(5, Math.max(1, Integer.parseInt(headers.getOrDefault("mx", "1")))); } catch (NumberFormatException e) { continue; }
                if (pending.incrementAndGet() > 128) { pending.decrementAndGet(); continue; }
                InetAddress peer = packet.getAddress(); int port = packet.getPort();
                try { timer.schedule(() -> { try { respond(peer, port, matches); } finally { pending.decrementAndGet(); } }, ThreadLocalRandom.current().nextInt(mx * 1000), TimeUnit.MILLISECONDS); }
                catch (RejectedExecutionException e) { pending.decrementAndGet(); }
            } catch (IOException e) { if (!closed) log.accept("SSDP receive: " + e); }
        }
    }
    private synchronized void respond(InetAddress peer, int port, List<String> matches) {
        if (closed) return;
        for (Link l : links.values()) if (sameSubnet(l.address, peer, l.prefix)) {
            for (String target : matches) send(l, "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=180\r\nEXT:\r\nLOCATION: " + location(l) + "\r\nSERVER: " + Upnp.SERVER + "\r\nST: " + target + "\r\nUSN: " + usn(target) + "\r\n\r\n", new InetSocketAddress(peer, port));
            log.accept("SSDP discovery answered for " + peer.getHostAddress());
        }
    }
    private synchronized void advertise(String state) { for (Link l : links.values()) announce(l, state); }
    private void announce(Link l, String state) {
        for (String target : targets("ssdp:all", uuid)) send(l, "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nCACHE-CONTROL: max-age=180\r\nLOCATION: " + location(l) + "\r\nSERVER: " + Upnp.SERVER + "\r\nNT: " + target + "\r\nNTS: " + state + "\r\nUSN: " + usn(target) + "\r\n\r\n", GROUP);
    }
    private void send(Link l, String message, InetSocketAddress destination) { try { byte[] data = message.getBytes(StandardCharsets.US_ASCII); l.sender.send(new DatagramPacket(data, data.length, destination)); } catch (IOException e) { if (!closed) log.accept("SSDP send: " + e.getMessage()); } }
    @Override public synchronized void close() {
        if (closed) return; advertise("ssdp:byebye"); closed = true; timer.shutdownNow(); listener.close(); for (Link l : links.values()) l.close(); links.clear();
    }
}
