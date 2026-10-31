package app.zlna.core;

import java.io.*;
import java.util.*;
import java.util.function.Consumer;
import org.w3c.dom.*;

public final class Upnp implements HttpServer.Handler, AutoCloseable {
    public static final String SERVER = "Android/1.0 UPnP/1.0 ZLNA/0.1";
    public static final String DEVICE = "urn:schemas-upnp-org:device:MediaServer:1";
    public static final String CDS = "urn:schemas-upnp-org:service:ContentDirectory:1";
    public static final String CM = "urn:schemas-upnp-org:service:ConnectionManager:1";
    public static final String SOAP = "http://schemas.xmlsoap.org/soap/envelope/";
    private final String uuid;
    private final Media.Library library;
    private final Eventing eventing;
    private final Consumer<String> log;
    public Upnp(String uuid, Media.Library library, Consumer<String> log) {
        this.uuid = uuid; this.library = library; this.log = log; eventing = new Eventing(log);
    }
    @Override public HttpServer.Response handle(HttpServer.Request r) {
        if (r.path.startsWith("/media/")) return media(r);
        if (r.path.equals("/event/content") || r.path.equals("/event/connection")) {
            String state = r.path.endsWith("content") ? Eventing.property("SystemUpdateID", "" + library.updateId) : Eventing.property("SourceProtocolInfo", library.protocols()) + Eventing.property("SinkProtocolInfo", "") + Eventing.property("CurrentConnectionIDs", "0");
            return eventing.handle(r, state);
        }
        if (r.method.equals("GET") || r.method.equals("HEAD")) {
            return switch (r.path) {
                case "/description.xml" -> xml(200, description());
                case "/content.xml" -> xml(200, Scpd.content());
                case "/connection.xml" -> xml(200, Scpd.connection());
                case "/" -> HttpServer.Response.text(200, "ZLNA is running. Open SKYBOX > Network > ZLNA.");
                default -> HttpServer.Response.text(404, "Not found");
            };
        }
        if (r.method.equals("POST") && (r.path.equals("/control/content") || r.path.equals("/control/connection"))) return control(r);
        return HttpServer.Response.text(405, "Method not allowed");
    }
    private HttpServer.Response media(HttpServer.Request r) {
        if (!r.method.equals("GET") && !r.method.equals("HEAD")) return HttpServer.Response.text(405, "Method not allowed");
        Media.Node n = library.get(r.path.substring(7));
        if (n == null || n.directory()) return HttpServer.Response.text(404, "Video not found");
        ByteRange range;
        try { range = ByteRange.parse(r.headers.get("range"), n.size); }
        catch (IllegalArgumentException e) {
            HttpServer.Response response = HttpServer.Response.text(416, "Range not satisfiable");
            response.headers.put("Content-Range", "bytes */" + n.size); return response;
        }
        InputStream body;
        try { body = r.method.equals("HEAD") ? null : n.source.open(range.start); }
        catch (IOException | SecurityException e) { log.accept("Video unavailable: " + n.title + ": " + e); return HttpServer.Response.text(404, "Video unavailable. Stop and run ZLNA to refresh the folder."); }
        HttpServer.Response response = new HttpServer.Response(range.partial ? 206 : 200, n.mime, body, range.length);
        response.headers.put("Accept-Ranges", "bytes");
        response.headers.put("transferMode.dlna.org", "Streaming");
        response.headers.put("contentFeatures.dlna.org", Media.protocol(n.mime).split(":", 4)[3]);
        if (range.partial) response.headers.put("Content-Range", "bytes " + range.start + "-" + (range.start + range.length - 1) + "/" + n.size);
        return response;
    }
    private HttpServer.Response control(HttpServer.Request r) {
        String service = r.path.endsWith("content") ? CDS : CM;
        try {
            Document doc = Xml.parse(r.body);
            Element envelope = doc.getDocumentElement();
            if (!"Envelope".equals(envelope.getLocalName()) || !SOAP.equals(envelope.getNamespaceURI())) throw new IllegalArgumentException();
            NodeList bodies = envelope.getElementsByTagNameNS(SOAP, "Body");
            if (bodies.getLength() != 1) throw new IllegalArgumentException();
            Element action = null;
            for (Node n = bodies.item(0).getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element) { if (action != null) throw new IllegalArgumentException(); action = (Element)n; }
            if (action == null || !service.equals(action.getNamespaceURI())) throw new IllegalArgumentException();
            String name = action.getLocalName();
            String soapAction = r.headers.getOrDefault("soapaction", "").replace("\"", "");
            if (!soapAction.equals(service + "#" + name)) throw new IllegalArgumentException();
            String body;
            if (service.equals(CDS)) {
                body = switch (name) {
                    case "GetSearchCapabilities" -> Xml.tag("SearchCaps", "");
                    case "GetSortCapabilities" -> Xml.tag("SortCaps", "dc:title");
                    case "GetSystemUpdateID" -> Xml.tag("Id", "" + library.updateId);
                    case "Browse" -> browse(action, r.baseUrl);
                    default -> throw new UpnpError(401, "Invalid Action");
                };
            } else {
                body = switch (name) {
                    case "GetProtocolInfo" -> Xml.tag("Source", library.protocols()) + Xml.tag("Sink", "");
                    case "GetCurrentConnectionIDs" -> Xml.tag("ConnectionIDs", "0");
                    case "GetCurrentConnectionInfo" -> connectionInfo(action);
                    default -> throw new UpnpError(401, "Invalid Action");
                };
            }
            return xml(200, envelope("<u:" + name + "Response xmlns:u=\"" + service + "\">" + body + "</u:" + name + "Response>"));
        } catch (UpnpError e) { return fault(e.code, e.getMessage()); }
        catch (Exception e) { log.accept("SOAP request rejected: " + e); return fault(402, "Invalid Args"); }
    }
    private String browse(Element action, String base) throws UpnpError {
        String id = Xml.value(action, "ObjectID"), flag = Xml.value(action, "BrowseFlag");
        Xml.value(action, "Filter"); // Returning all metadata is permitted when a narrower filter is requested.
        long start = uint(Xml.value(action, "StartingIndex")), count = uint(Xml.value(action, "RequestedCount"));
        String sort = Xml.value(action, "SortCriteria");
        if (!sort.isEmpty() && !sort.equals("+dc:title") && !sort.equals("-dc:title")) throw new UpnpError(709, "Unsupported Sort Criteria");
        Media.Node n = library.get(id); if (n == null) throw new UpnpError(701, "No Such Object");
        List<Media.Node> all;
        if (flag.equals("BrowseMetadata")) { all = Collections.singletonList(n); start = 0; count = 1; }
        else if (flag.equals("BrowseDirectChildren")) {
            if (!n.directory()) throw new UpnpError(710, "No Such Container");
            all = new ArrayList<>(library.children(id));
            if (!sort.isEmpty()) { all.sort(Comparator.comparing(v -> v.title, String.CASE_INSENSITIVE_ORDER)); if (sort.startsWith("-")) Collections.reverse(all); }
        } else throw new UpnpError(402, "Invalid Args");
        int from = (int)Math.min(start, all.size());
        int to = count == 0 ? all.size() : (int)Math.min(all.size(), (long)from + count);
        StringBuilder didl = new StringBuilder("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">");
        for (Media.Node item : all.subList(from, to)) {
            String tag = item.directory() ? "container" : "item";
            didl.append('<').append(tag).append(" id=\"").append(Xml.escape(item.id)).append("\" parentID=\"").append(Xml.escape(item.parent)).append("\" restricted=\"1\"");
            if (item.directory()) didl.append(" childCount=\"").append(library.children(item.id).size()).append("\" searchable=\"0\"");
            didl.append('>').append(Xml.tag("dc:title", item.title)).append(Xml.tag("upnp:class", item.directory() ? "object.container.storageFolder" : "object.item.videoItem"));
            // ContentDirectory:1 requires this for storageFolder; -1 means unknown.
            if (item.directory()) didl.append("<upnp:storageUsed>-1</upnp:storageUsed>");
            if (!item.directory()) didl.append("<res protocolInfo=\"").append(Xml.escape(Media.protocol(item.mime))).append("\" size=\"").append(item.size).append("\">").append(Xml.escape(base + "/media/" + item.id)).append("</res>");
            didl.append("</").append(tag).append('>');
        }
        didl.append("</DIDL-Lite>");
        return Xml.tag("Result", didl.toString()) + Xml.tag("NumberReturned", "" + (to - from)) + Xml.tag("TotalMatches", "" + all.size()) + Xml.tag("UpdateID", "" + library.updateId);
    }
    private String connectionInfo(Element action) throws UpnpError {
        if (!Xml.value(action, "ConnectionID").equals("0")) throw new UpnpError(706, "Invalid Connection Reference");
        return "<RcsID>-1</RcsID><AVTransportID>-1</AVTransportID><ProtocolInfo></ProtocolInfo><PeerConnectionManager></PeerConnectionManager><PeerConnectionID>-1</PeerConnectionID><Direction>Output</Direction><Status>OK</Status>";
    }
    private static long uint(String value) { long n = Long.parseLong(value); if (n < 0 || n > 0xffffffffL) throw new IllegalArgumentException(); return n; }
    public String description() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion><device><deviceType>" + DEVICE + "</deviceType><friendlyName>ZLNA</friendlyName><manufacturer>ZLNA</manufacturer><modelDescription>Personal video server</modelDescription><modelName>ZLNA</modelName><modelNumber>0.1</modelNumber><UDN>uuid:" + Xml.escape(uuid) + "</UDN><dlna:X_DLNADOC>DMS-1.50</dlna:X_DLNADOC><serviceList>" + service(CDS, "ContentDirectory", "content") + service(CM, "ConnectionManager", "connection") + "</serviceList></device></root>";
    }
    private static String service(String type, String name, String path) { return "<service><serviceType>" + type + "</serviceType><serviceId>urn:upnp-org:serviceId:" + name + "</serviceId><SCPDURL>/" + path + ".xml</SCPDURL><controlURL>/control/" + path + "</controlURL><eventSubURL>/event/" + path + "</eventSubURL></service>"; }
    private static String envelope(String body) { return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><s:Envelope xmlns:s=\"" + SOAP + "\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" + body + "</s:Body></s:Envelope>"; }
    private static HttpServer.Response fault(int code, String message) { return xml(500, envelope("<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>" + code + "</errorCode>" + Xml.tag("errorDescription", message) + "</UPnPError></detail></s:Fault>")); }
    private static HttpServer.Response xml(int status, String text) { HttpServer.Response r = HttpServer.Response.text(status, "text/xml; charset=\"utf-8\"", text); r.headers.put("EXT", ""); return r; }
    @Override public void close() { eventing.close(); }
    private static final class UpnpError extends Exception { final int code; UpnpError(int code, String message) { super(message); this.code = code; } }
}
