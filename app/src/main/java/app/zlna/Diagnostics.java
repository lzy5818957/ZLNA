package app.zlna;

import android.util.Log;
import android.content.Context;
import java.text.SimpleDateFormat;
import java.util.*;

final class Diagnostics {
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    static synchronized void log(String message) {
        Log.i("ZLNA", message);
        if (lines.size() == 150) lines.removeFirst();
        lines.addLast(new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + " " + message);
    }
    static synchronized String report(Context context) {
        return "ZLNA " + BuildConfig.VERSION_NAME + " / Android " + android.os.Build.VERSION.RELEASE
                + "\n" + SharingService.state.detailText(context) + "\n\n" + String.join("\n", lines);
    }
}
