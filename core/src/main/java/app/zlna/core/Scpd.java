package app.zlna.core;

/** The advertised service schema matches exactly the implemented actions. */
final class Scpd {
    private final StringBuilder actions = new StringBuilder(), variables = new StringBuilder();
    private void variable(String name, String type, boolean evented, String... values) {
        variables.append("<stateVariable sendEvents=\"").append(evented ? "yes" : "no").append("\">").append(Xml.tag("name", name)).append(Xml.tag("dataType", type));
        if (values.length > 0) { variables.append("<allowedValueList>"); for (String v : values) variables.append(Xml.tag("allowedValue", v)); variables.append("</allowedValueList>"); }
        variables.append("</stateVariable>");
    }
    private void action(String name, String... args) {
        actions.append("<action>").append(Xml.tag("name", name));
        if (args.length > 0) {
            actions.append("<argumentList>");
            for (String arg : args) { String[] v = arg.split(":"); actions.append("<argument>").append(Xml.tag("name", v[0])).append(Xml.tag("direction", v[1])).append(Xml.tag("relatedStateVariable", v[2])).append("</argument>"); }
            actions.append("</argumentList>");
        }
        actions.append("</action>");
    }
    private String xml() { return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion><actionList>" + actions + "</actionList><serviceStateTable>" + variables + "</serviceStateTable></scpd>"; }
    static String content() {
        Scpd s = new Scpd();
        s.action("GetSearchCapabilities", "SearchCaps:out:SearchCapabilities");
        s.action("GetSortCapabilities", "SortCaps:out:SortCapabilities");
        s.action("GetSystemUpdateID", "Id:out:SystemUpdateID");
        s.action("Browse", "ObjectID:in:A_ARG_TYPE_ObjectID", "BrowseFlag:in:A_ARG_TYPE_BrowseFlag", "Filter:in:A_ARG_TYPE_Filter", "StartingIndex:in:A_ARG_TYPE_Index", "RequestedCount:in:A_ARG_TYPE_Count", "SortCriteria:in:A_ARG_TYPE_SortCriteria", "Result:out:A_ARG_TYPE_Result", "NumberReturned:out:A_ARG_TYPE_Count", "TotalMatches:out:A_ARG_TYPE_Count", "UpdateID:out:A_ARG_TYPE_UpdateID");
        s.variable("SearchCapabilities", "string", false); s.variable("SortCapabilities", "string", false); s.variable("SystemUpdateID", "ui4", true);
        for (String n : new String[]{"ObjectID", "Filter", "SortCriteria", "Result"}) s.variable("A_ARG_TYPE_" + n, "string", false);
        for (String n : new String[]{"Index", "Count", "UpdateID"}) s.variable("A_ARG_TYPE_" + n, "ui4", false);
        s.variable("A_ARG_TYPE_BrowseFlag", "string", false, "BrowseMetadata", "BrowseDirectChildren");
        return s.xml();
    }
    static String connection() {
        Scpd s = new Scpd();
        s.action("GetProtocolInfo", "Source:out:SourceProtocolInfo", "Sink:out:SinkProtocolInfo");
        s.action("GetCurrentConnectionIDs", "ConnectionIDs:out:CurrentConnectionIDs");
        s.action("GetCurrentConnectionInfo", "ConnectionID:in:A_ARG_TYPE_ConnectionID", "RcsID:out:A_ARG_TYPE_RcsID", "AVTransportID:out:A_ARG_TYPE_AVTransportID", "ProtocolInfo:out:A_ARG_TYPE_ProtocolInfo", "PeerConnectionManager:out:A_ARG_TYPE_ConnectionManager", "PeerConnectionID:out:A_ARG_TYPE_ConnectionID", "Direction:out:A_ARG_TYPE_Direction", "Status:out:A_ARG_TYPE_ConnectionStatus");
        for (String n : new String[]{"SourceProtocolInfo", "SinkProtocolInfo", "CurrentConnectionIDs"}) s.variable(n, "string", true);
        for (String n : new String[]{"ConnectionID", "RcsID", "AVTransportID"}) s.variable("A_ARG_TYPE_" + n, "i4", false);
        for (String n : new String[]{"ProtocolInfo", "ConnectionManager"}) s.variable("A_ARG_TYPE_" + n, "string", false);
        s.variable("A_ARG_TYPE_Direction", "string", false, "Input", "Output");
        s.variable("A_ARG_TYPE_ConnectionStatus", "string", false, "OK", "ContentFormatMismatch", "InsufficientBandwidth", "UnreliableChannel", "Unknown");
        return s.xml();
    }
}
