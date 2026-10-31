package app.zlna;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import app.zlna.core.Media;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.IntConsumer;

final class FolderLibrary {
    private static final Map<String, String> TYPES = new HashMap<>();
    static {
        TYPES.put("mp4", "video/mp4"); TYPES.put("m4v", "video/mp4"); TYPES.put("mkv", "video/x-matroska");
        TYPES.put("webm", "video/webm"); TYPES.put("avi", "video/x-msvideo"); TYPES.put("mov", "video/quicktime");
        TYPES.put("ts", "video/mp2t"); TYPES.put("m2ts", "video/mp2t"); TYPES.put("mts", "video/mp2t");
        TYPES.put("mpg", "video/mpeg"); TYPES.put("mpeg", "video/mpeg"); TYPES.put("vob", "video/mpeg");
        TYPES.put("wmv", "video/x-ms-wmv"); TYPES.put("3gp", "video/3gpp"); TYPES.put("flv", "video/x-flv");
    }
    private static final String[] COLUMNS = { Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_FLAGS };
    private static final class Folder { final String documentId, id; Folder(String documentId, String id) { this.documentId = documentId; this.id = id; } }
    static Media.Library scan(Context context, Uri tree, CancellationSignal cancellation, IntConsumer progress) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String rootDocument = DocumentsContract.getTreeDocumentId(tree);
        List<Media.Node> nodes = new ArrayList<>();
        String rootName = name(resolver, tree);
        nodes.add(new Media.Node("0", "-1", rootName != null ? rootName : context.getString(R.string.media_folder), null, 0, null));
        ArrayDeque<Folder> queue = new ArrayDeque<>(); queue.add(new Folder(rootDocument, "0"));
        Set<String> visited = new HashSet<>(); visited.add(rootDocument);
        int videos = 0, skipped = 0;
        while (!queue.isEmpty()) {
            cancellation.throwIfCanceled();
            Folder folder = queue.removeFirst();
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folder.documentId);
            try (Cursor cursor = resolver.query(children, COLUMNS, null, null, null, cancellation)) {
                if (cursor == null) throw new IOException(context.getString(R.string.folder_read_error));
                while (cursor.moveToNext()) {
                    cancellation.throwIfCanceled();
                    String document = cursor.getString(0), title = cursor.getString(1), mime = cursor.getString(2);
                    if (document == null || !visited.add(document)) continue;
                    if (title == null) title = context.getString(R.string.unnamed);
                    String id = UUID.nameUUIDFromBytes((tree + "\n" + document).getBytes(StandardCharsets.UTF_8)).toString();
                    if (Document.MIME_TYPE_DIR.equals(mime)) {
                        nodes.add(new Media.Node(id, folder.id, title, null, 0, null)); queue.addLast(new Folder(document, id)); continue;
                    }
                    String extension = title.substring(title.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                    String type = TYPES.get(extension);
                    if (type == null && mime != null && mime.startsWith("video/")) type = mime;
                    if (type == null) continue;
                    if ((cursor.getInt(4) & Document.FLAG_VIRTUAL_DOCUMENT) != 0) { skipped++; continue; }
                    Uri file = DocumentsContract.buildDocumentUriUsingTree(tree, document);
                    long size = cursor.isNull(3) ? -1 : cursor.getLong(3);
                    if (size < 0) try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(file, "r", cancellation)) { if (descriptor != null) size = descriptor.getStatSize(); }
                    if (size < 0) { skipped++; Diagnostics.log("Skipped video with unknown size: " + title); continue; }
                    nodes.add(new Media.Node(id, folder.id, title, type, size, offset -> open(resolver, file, offset)));
                    videos++; if (videos % 50 == 0) progress.accept(videos);
                }
            }
        }
        Diagnostics.log("Indexed " + videos + " videos; skipped " + skipped + " virtual/unknown-size videos");
        if (skipped > 0) throw new IOException(context.getString(R.string.unreadable, skipped));
        return new Media.Library(nodes);
    }
    static String name(ContentResolver resolver, Uri tree) {
        Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        try (Cursor c = resolver.query(root, new String[]{ Document.COLUMN_DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        }
        return null;
    }
    private static InputStream open(ContentResolver resolver, Uri uri, long offset) throws IOException {
        ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r");
        if (descriptor == null) throw new FileNotFoundException("Video unavailable");
        ParcelFileDescriptor.AutoCloseInputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
        try { stream.getChannel().position(offset); return stream; }
        catch (IOException | RuntimeException e) { stream.close(); throw e; }
    }
}
