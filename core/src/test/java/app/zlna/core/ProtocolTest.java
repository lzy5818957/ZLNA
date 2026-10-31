package app.zlna.core;

import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.w3c.dom.*;

/** Exercises the real HTTP listener; no mocked network responses. */
public class ProtocolTest {
    private HttpServer server;
    private Upnp upnp;
    private final String uuid = "77dd4f24-6b64-491a-83ee-d8853fc625a1";
    private static final long SIZE = 6L * 1024 * 1024 * 1024 + 317;
    private static class Reply {
        int status; Map<String,String> headers = new HashMap<>(); byte[] body;
        String text() { return new String(body, StandardCharsets.UTF_8); }
    }
    @Before public void setup() throws Exception {
        List<Media.Node> nodes = Arrays.asList(
            new Media.Node("0", "-1", "Movies", null, 0, null),
            new Media.Node("folder", "0", "子目录", null, 0, null),
            new Media.Node("video", "0", "电影 & <海洋> \"3D\".mkv", "video/x-matroska", SIZE, offset -> new InputStream() {
                long position = offset;
                public int read() { return position < SIZE ? (int)(position++ % 251) : -1; }
                public int read(byte[] b, int off, int len) { int n = (int)Math.min(SIZE - position, len); if (n == 0) return -1; for (int i = 0; i < n; i++) b[off + i] = (byte)(position++ % 251); return n; }
            }),
            new Media.Node("small", "folder", "second.mp4", "video/mp4", 4, offset -> new ByteArrayInputStream(new byte[]{1,2,3,4}, (int)offset, 4 - (int)offset)),
            new Media.Node("missing", "folder", "deleted.mp4", "video/mp4", 4, offset -> { throw new FileNotFoundException(); })
        );
        upnp = new Upnp(uuid, new Media.Library(nodes), ignored -> {});
        server = new HttpServer(0, upnp, InetAddress::isLoopbackAddress, ignored -> {}); server.start();
    }
    @After public void teardown() { server.close(); upnp.close(); }
    private Reply request(String method, String path, Map<String,String> headers, String body) throws Exception {
        try (Socket s = new Socket("127.0.0.1", server.port())) {
            s.setSoTimeout(5000);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            StringBuilder head = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: attacker.invalid\r\nContent-Length: " + bytes.length + "\r\n");
            for (Map.Entry<String,String> h : headers.entrySet()) head.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
            s.getOutputStream().write(head.append("\r\n").toString().getBytes(StandardCharsets.UTF_8)); s.getOutputStream().write(bytes); s.getOutputStream().flush();
            byte[] all = s.getInputStream().readAllBytes();
            String raw = new String(all, StandardCharsets.ISO_8859_1); int split = raw.indexOf("\r\n\r\n"); assertTrue(split > 0);
            String[] lines = raw.substring(0, split).split("\r\n"); Reply r = new Reply(); r.status = Integer.parseInt(lines[0].split(" ")[1]);
            for (int i = 1; i < lines.length; i++) { int colon = lines[i].indexOf(':'); r.headers.put(lines[i].substring(0, colon).toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim()); }
            r.body = Arrays.copyOfRange(all, split + 4, all.length); return r;
        }
    }
    private Reply get(String path) throws Exception { return request("GET", path, Map.of(), ""); }
    private Reply soap(String service, String action, String args) throws Exception {
        return request("POST", service.equals(Upnp.CDS) ? "/control/content" : "/control/connection", Map.of("SOAPACTION", "\"" + service + "#" + action + "\""), "<s:Envelope xmlns:s=\"" + Upnp.SOAP + "\"><s:Body><u:" + action + " xmlns:u=\"" + service + "\">" + args + "</u:" + action + "></s:Body></s:Envelope>");
    }
    private Reply browse(String id, String flag, String index, String count, String sort) throws Exception {
        return soap(Upnp.CDS, "Browse", Xml.tag("ObjectID", id) + Xml.tag("BrowseFlag", flag) + "<Filter>*</Filter>" + Xml.tag("StartingIndex", index) + Xml.tag("RequestedCount", count) + Xml.tag("SortCriteria", sort));
    }
    private String value(Reply r, String name) throws Exception { return Xml.parse(r.body).getElementsByTagNameNS("*", name).item(0).getTextContent(); }
    @Test public void descriptionAndEveryAdvertisedSchemaAreReadable() throws Exception {
        Reply d = get("/description.xml"); assertEquals(200, d.status); assertEquals("ZLNA", value(d, "friendlyName")); assertEquals("uuid:" + uuid, value(d, "UDN"));
        NodeList urls = Xml.parse(d.body).getElementsByTagNameNS("*", "SCPDURL"); assertEquals(2, urls.getLength());
        for (int i = 0; i < urls.getLength(); i++) { Reply r = get(urls.item(i).getTextContent()); assertEquals(200, r.status); assertTrue(Xml.parse(r.body).getElementsByTagNameNS("*", "action").getLength() >= 3); }
    }
    @Test public void browseReturnsEscapedDidlAndRealConnectionAddress() throws Exception {
        Reply r = browse("0", "BrowseDirectChildren", "0", "0", ""); assertEquals(200, r.status); assertEquals("2", value(r, "TotalMatches"));
        Document didl = Xml.parse(value(r, "Result").getBytes(StandardCharsets.UTF_8));
        assertEquals("-1", didl.getElementsByTagNameNS("*", "storageUsed").item(0).getTextContent());
        assertEquals("电影 & <海洋> \"3D\".mkv", didl.getElementsByTagNameNS("*", "title").item(1).getTextContent());
        Element res = (Element)didl.getElementsByTagNameNS("*", "res").item(0); assertEquals("" + SIZE, res.getAttribute("size"));
        assertEquals("http://127.0.0.1:" + server.port() + "/media/video", res.getTextContent()); assertTrue(res.getAttribute("protocolInfo").contains("DLNA.ORG_OP=01"));
    }
    @Test public void browseMetadataAndPagination() throws Exception {
        assertEquals("1", value(browse("video", "BrowseMetadata", "50", "0", ""), "NumberReturned"));
        Reply page = browse("0", "BrowseDirectChildren", "1", "1", ""); assertEquals("1", value(page, "NumberReturned")); assertEquals("2", value(page, "TotalMatches"));
        assertEquals("0", value(browse("0", "BrowseDirectChildren", "4294967295", "0", ""), "NumberReturned"));
        assertEquals("2", value(browse("folder", "BrowseDirectChildren", "0", "0", "+dc:title"), "NumberReturned"));
    }
    @Test public void invalidBrowseUsesStructuredSoapFaults() throws Exception {
        assertEquals("701", value(browse("absent", "BrowseMetadata", "0", "0", ""), "errorCode"));
        assertEquals("710", value(browse("video", "BrowseDirectChildren", "0", "0", ""), "errorCode"));
        assertEquals("402", value(browse("0", "BrowseDirectChildren", "-1", "0", ""), "errorCode"));
        assertEquals("709", value(browse("0", "BrowseDirectChildren", "0", "0", "+res@size"), "errorCode"));
        assertEquals("401", value(soap(Upnp.CDS, "Search", ""), "errorCode"));
    }
    @Test public void headHasFullLengthAndNoBody() throws Exception {
        Reply r = request("HEAD", "/media/video", Map.of(), ""); assertEquals(200, r.status); assertEquals("" + SIZE, r.headers.get("content-length")); assertEquals(0, r.body.length); assertEquals("bytes", r.headers.get("accept-ranges"));
    }
    @Test public void offsetBeyondFourGiBReturnsExactBytes() throws Exception {
        long offset = 5L * 1024 * 1024 * 1024 + 17;
        Reply r = request("GET", "/media/video", Map.of("Range", "bytes=" + offset + "-" + (offset + 65535)), "");
        assertEquals(206, r.status); assertEquals(65536, r.body.length); assertEquals("bytes " + offset + "-" + (offset + 65535) + "/" + SIZE, r.headers.get("content-range"));
        for (int i = 0; i < r.body.length; i++) assertEquals((byte)((offset + i) % 251), r.body[i]);
    }
    @Test public void suffixAndOpenEndedRanges() throws Exception {
        Reply suffix = request("GET", "/media/small", Map.of("Range", "bytes=-2"), ""); assertEquals(206, suffix.status); assertArrayEquals(new byte[]{3,4}, suffix.body);
        assertArrayEquals(new byte[]{2,3,4}, request("GET", "/media/small", Map.of("Range", "bytes=1-"), "").body);
        assertArrayEquals(new byte[]{1,2,3,4}, request("GET", "/media/small", Map.of("Range", "bytes=-100"), "").body);
        assertArrayEquals(new byte[]{3,4}, request("GET", "/media/small", Map.of("Range", "bytes=2-100"), "").body);
    }
    @Test public void invalidAndMultiRanges() throws Exception {
        for (String range : List.of("bytes=4-", "bytes=3-1", "bytes=-0", "bytes=9223372036854775808-", "bytes=oops")) {
            Reply r = request("GET", "/media/small", Map.of("Range", range), ""); assertEquals(416, r.status); assertEquals("bytes */4", r.headers.get("content-range"));
        }
        Reply multi = request("GET", "/media/small", Map.of("Range", "bytes=0-1,3-3"), ""); assertEquals(200, multi.status); assertEquals(4, multi.body.length);
    }
    @Test public void connectionManagerAndCapabilities() throws Exception {
        assertEquals("", value(soap(Upnp.CDS, "GetSearchCapabilities", ""), "SearchCaps"));
        assertEquals("dc:title", value(soap(Upnp.CDS, "GetSortCapabilities", ""), "SortCaps"));
        assertNotNull(value(soap(Upnp.CDS, "GetSystemUpdateID", ""), "Id"));
        assertTrue(value(soap(Upnp.CM, "GetProtocolInfo", ""), "Source").contains("video/x-matroska"));
        assertEquals("0", value(soap(Upnp.CM, "GetCurrentConnectionIDs", ""), "ConnectionIDs"));
        assertEquals("Output", value(soap(Upnp.CM, "GetCurrentConnectionInfo", "<ConnectionID>0</ConnectionID>"), "Direction"));
        assertEquals("706", value(soap(Upnp.CM, "GetCurrentConnectionInfo", "<ConnectionID>5</ConnectionID>"), "errorCode"));
    }
    @Test public void missingMediaAndTraversalAreNotServed() throws Exception {
        assertEquals(404, get("/media/missing").status); assertEquals(404, get("/media/../../etc/passwd").status); assertEquals(404, get("/media/folder").status);
    }
    @Test public void xmlRejectsExternalEntities() throws Exception {
        Reply r = request("POST", "/control/content", Map.of("SOAPACTION", "\"" + Upnp.CDS + "#Browse\""), "<!DOCTYPE test [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><test>&e;</test>");
        assertEquals(500, r.status); assertEquals("402", value(r, "errorCode")); assertFalse(r.text().contains("root:"));
    }
    @Test public void eventSubscriptionNotifiesThenRenewsAndUnsubscribes() throws Exception {
        try (ServerSocket callback = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            callback.setSoTimeout(5000);
            Reply r = request("SUBSCRIBE", "/event/content", Map.of("CALLBACK", "<http://127.0.0.1:" + callback.getLocalPort() + "/events>", "NT", "upnp:event"), "");
            assertEquals(200, r.status); String sid = r.headers.get("sid"); assertNotNull(sid);
            try (Socket notification = callback.accept()) {
                notification.setSoTimeout(5000); BufferedReader reader = new BufferedReader(new InputStreamReader(notification.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder received = new StringBuilder(); int length = 0; String line;
                while (!(line = reader.readLine()).isEmpty()) { received.append(line).append('\n'); if (line.startsWith("CONTENT-LENGTH:")) length = Integer.parseInt(line.substring(15).trim()); }
                char[] body = new char[length]; int total = 0; while (total < length) total += reader.read(body, total, length - total);
                assertTrue(received.toString().contains("SEQ: 0")); assertTrue(received.toString().contains(sid)); assertTrue(new String(body).contains("SystemUpdateID"));
                notification.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            }
            assertEquals(200, request("SUBSCRIBE", "/event/content", Map.of("SID", sid), "").status);
            assertEquals(412, request("SUBSCRIBE", "/event/connection", Map.of("SID", sid), "").status);
            assertEquals(200, request("UNSUBSCRIBE", "/event/content", Map.of("SID", sid), "").status);
            assertEquals(412, request("SUBSCRIBE", "/event/content", Map.of("SID", sid), "").status);
        }
    }
    @Test public void eventCallbacksCannotTargetOtherHosts() throws Exception {
        for (String target : List.of("http://192.168.1.5:80/a", "http://localhost:80/a", "https://127.0.0.1/a", "http://127.0.0.1@evil.test/a")) assertEquals(412, request("SUBSCRIBE", "/event/content", Map.of("CALLBACK", "<" + target + ">", "NT", "upnp:event"), "").status);
    }
    @Test public void ssdpTargetsAndHotspotSubnetSelection() throws Exception {
        assertEquals(5, Ssdp.targets("ssdp:all", uuid).size()); assertEquals(1, Ssdp.targets(Upnp.DEVICE, uuid).size()); assertTrue(Ssdp.targets("urn:unknown", uuid).isEmpty());
        assertTrue(Ssdp.eligible("ap_br_wlan2", InetAddress.getByName("192.168.43.1"))); assertFalse(Ssdp.eligible("tun0", InetAddress.getByName("10.0.0.1")));
        assertFalse(Ssdp.eligible("rmnet_data0", InetAddress.getByName("10.0.0.1")));
        assertTrue(Ssdp.sameSubnet(InetAddress.getByName("172.20.10.1"), InetAddress.getByName("172.20.10.3"), 28)); assertFalse(Ssdp.sameSubnet(InetAddress.getByName("172.20.10.1"), InetAddress.getByName("172.20.10.17"), 28));
    }
    @Test public void stopClosesListenerAndIdleConnections() throws Exception {
        Socket idle = new Socket("127.0.0.1", server.port()); idle.setSoTimeout(3000); int port = server.port();
        // Complete one request so the accept thread has definitely processed the earlier idle socket.
        assertEquals(200, get("/description.xml").status); server.close();
        try { assertEquals(-1, idle.getInputStream().read()); } catch (SocketException expected) {} finally { idle.close(); }
        try (Socket ignored = new Socket("127.0.0.1", port)) { fail("Listener still open"); } catch (ConnectException expected) {}
    }
    @Test public void concurrentSeeksDoNotShareFilePosition() throws Exception {
        ExecutorService clients = Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) { long start = (long)i * 1000000000L; results.add(clients.submit(() -> { try { Reply r = request("GET", "/media/video", Map.of("Range", "bytes=" + start + "-" + (start + 1023)), ""); assertEquals(206, r.status); for (int j = 0; j < 1024; j++) assertEquals((byte)((start + j) % 251), r.body[j]); } catch (Exception e) { throw new RuntimeException(e); } })); }
            for (Future<?> r : results) r.get(10, TimeUnit.SECONDS);
        } finally { clients.shutdownNow(); }
    }
    @Test public void zeroLengthAndRangeArithmetic() {
        assertEquals(0, ByteRange.parse(null, 0).length);
        try { ByteRange.parse("bytes=0-", 0); fail(); } catch (IllegalArgumentException expected) {}
        assertEquals(Long.MAX_VALUE, ByteRange.parse("bytes=0-", Long.MAX_VALUE).length);
    }
}
