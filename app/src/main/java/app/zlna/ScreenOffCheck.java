package app.zlna;

import android.content.*;
import android.os.*;
import app.zlna.core.*;
import java.net.*;
import java.util.function.LongConsumer;

/** Evidence belongs to one running service, never to a saved checkbox. */
final class ScreenOffCheck extends BroadcastReceiver implements AutoCloseable {
    private final Context context;
    private final PowerManager power;
    private long offSince = -1;
    private boolean closed;
    private boolean verified;
    private final LongConsumer onVerified;

    ScreenOffCheck(Context context, LongConsumer onVerified) {
        this.context = context; this.onVerified = onVerified; power = context.getSystemService(PowerManager.class);
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(this, filter, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(this, filter);
        if (!power.isInteractive()) offSince = SystemClock.elapsedRealtime();
    }
    @Override public synchronized void onReceive(Context c, Intent i) {
        offSince = Intent.ACTION_SCREEN_OFF.equals(i.getAction()) ? SystemClock.elapsedRealtime() : -1;
    }
    synchronized long eligiblePeriod(HttpServer.Request r) {
        if (closed || verified || offSince < 0 || SystemClock.elapsedRealtime() - offSince < 60_000
                || !r.method.equals("GET") || !r.path.startsWith("/media/") || power.isInteractive()
                || r.peer.isLoopbackAddress()) return -1;
        try { return NetworkInterface.getByInetAddress(r.peer) == null ? offSince : -1; }
        catch (SocketException e) { return -1; }
    }
    synchronized void sent(long period) {
        if (!closed && !verified && period >= 0 && period == offSince && !power.isInteractive()) {
            verified = true; onVerified.accept(System.currentTimeMillis());
            Diagnostics.log("Screen-off check passed: sent video data to a LAN client after 60 seconds screen-off");
        }
    }
    @Override public synchronized void close() {
        closed = true; context.unregisterReceiver(this);
    }
}
