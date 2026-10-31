package app.zlna;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.*;
import app.zlna.core.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public final class SharingService extends Service {
    static final String STOP = "app.zlna.STOP";
    static final class State {
        final boolean active, preparing;
        final int title, detail;
        final Object[] args;
        State(boolean active, boolean preparing, int title, int detail, Object... args) {
            this.active = active;
            this.preparing = preparing;
            this.title = title;
            this.detail = detail;
            this.args = args;
        }
        String detailText(Context context) {
            return args.length == 0 ? context.getString(detail) : context.getString(detail, args);
        }
    }
    static volatile State state = new State(false, false, R.string.ready, R.string.initial_hint);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final CancellationSignal cancellation = new CancellationSignal();
    private volatile boolean destroyed;
    private boolean started;
    private HttpServer http;
    private Ssdp ssdp;
    private Upnp upnp;
    private ScreenOffCheck screenCheck;
    static volatile long screenVerifiedAt;
    private PowerManager.WakeLock wake;
    private WifiManager.MulticastLock multicast;
    private WifiManager.WifiLock wifi;
    private State failure;
    private int notificationLabel = R.string.preparing;
    private int videoCount;
    @Override public void onCreate() {
        super.onCreate();
        updateChannel();
        screenVerifiedAt = 0;
        screenCheck = new ScreenOffCheck(this, at -> screenVerifiedAt = at);
    }
    private void updateChannel() {
        NotificationChannel channel = new NotificationChannel("sharing", getString(R.string.channel), NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        updateChannel();
        if (started && !destroyed) getSystemService(NotificationManager.class).notify(1, notification());
    }
    // Sharing is explicitly user-controlled, potentially longer than a movie. The lock belongs
    // to this foreground service and is released in onDestroy (or by Android on process death).
    @android.annotation.SuppressLint("WakelockTimeout")
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (started) return START_NOT_STICKY;
        started = true;
        state = new State(true, true, R.string.indexing, R.string.index_hint);
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(1, notification());
        try {
            wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZLNA:sharing"); wake.acquire();
            WifiManager manager = (WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
            multicast = manager.createMulticastLock("ZLNA:discovery"); multicast.setReferenceCounted(false); multicast.acquire();
            wifi = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ZLNA:streaming"); wifi.setReferenceCounted(false); wifi.acquire();
        } catch (RuntimeException e) { fail(R.string.network_error, e.toString()); return START_NOT_STICKY; }
        worker.execute(this::prepare);
        return START_NOT_STICKY;
    }
    private void prepare() {
        HttpServer newHttp = null; Ssdp newSsdp = null; Upnp newUpnp = null;
        try {
            SharedPreferences preferences = getSharedPreferences("zlna", MODE_PRIVATE);
            String folder = preferences.getString("folder", null);
            if (folder == null) throw new IOException(getString(R.string.choose_first));
            Media.Library library = FolderLibrary.scan(this, Uri.parse(folder), cancellation, count -> main.post(() -> {
                if (!destroyed) state = new State(true, true, R.string.indexing, R.string.indexed, count);
            }));
            cancellation.throwIfCanceled();
            String uuid = preferences.getString("uuid", null);
            if (uuid == null) { uuid = UUID.randomUUID().toString(); preferences.edit().putString("uuid", uuid).apply(); }
            newUpnp = new Upnp(uuid, library, Diagnostics::log);
            // Bind once to an OS-assigned port, then advertise that same port on every LAN.
            Upnp protocol = newUpnp;
            ScreenOffCheck check = screenCheck;
            newHttp = new HttpServer(0, request -> {
                long period = check.eligiblePeriod(request);
                HttpServer.Response response = protocol.handle(request);
                if (period >= 0 && (response.status == 200 || response.status == 206) && response.length > 0 && response.body != null)
                    response.afterFirstBodyWrite = () -> check.sent(period);
                return response;
            }, peer -> { Ssdp current = ssdp; return current != null && current.allows(peer); }, Diagnostics::log);
            newSsdp = new Ssdp(uuid, newHttp.port(), Diagnostics::log, urls -> main.post(() -> {
                if (destroyed) return;
                videoCount = library.videoCount();
                int detail = urls.isEmpty() ? R.string.waiting_hint : videoCount == 0 ? R.string.empty_folder : R.string.shared_hint;
                state = new State(true, false, urls.isEmpty() ? R.string.waiting : R.string.sharing, detail, videoCount);
                notificationLabel = urls.isEmpty() ? R.string.waiting : R.string.shared_notification;
                getSystemService(NotificationManager.class).notify(1, notification());
                Diagnostics.log("Server URLs: " + String.join(", ", urls));
            }));
            synchronized (this) {
                if (destroyed) throw new OperationCanceledException();
                upnp = newUpnp; http = newHttp; ssdp = newSsdp;
                http.start(); ssdp.start();
            }
            Diagnostics.log("ZLNA started; " + library.videoCount() + " videos");
        } catch (Exception e) {
            if (newSsdp != null) newSsdp.close(); if (newHttp != null) newHttp.close(); if (newUpnp != null) newUpnp.close();
            if (!destroyed) {
                Diagnostics.log("Startup failed: " + e);
                main.post(() -> {
                    if (e instanceof SecurityException) fail(R.string.service_grant_expired);
                    else fail(R.string.start_failed, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                });
            }
        }
    }
    private void fail(int detail, Object... args) {
        if (destroyed) return;
        failure = new State(false, false, R.string.attention, detail, args);
        stopSelf();
    }
    private Notification notification() {
        String text = notificationLabel == R.string.shared_notification
                ? getString(R.string.shared_notification, videoCount) : getString(notificationLabel);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, SharingService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "sharing").setSmallIcon(R.drawable.ic_notification).setContentTitle("ZLNA").setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(null, getString(R.string.stop), stop).build()).build();
    }
    @Override public synchronized void onDestroy() {
        destroyed = true; cancellation.cancel(); worker.shutdownNow();
        screenCheck.close(); screenVerifiedAt = 0;
        state = new State(true, true, R.string.stopping, R.string.closing);
        // SSDP close sends byebye packets. Android forbids that network I/O on the
        // main thread, including Service.onDestroy. Keep Run unavailable until
        // the old server has retired so its byebye cannot cancel a newer alive.
        new Thread(() -> {
            try {
                if (http != null) http.close();
                if (upnp != null) upnp.close();
                if (ssdp != null) ssdp.close();
            } catch (RuntimeException e) { Diagnostics.log("Shutdown: " + e); }
            finally {
                if (multicast != null && multicast.isHeld()) multicast.release();
                if (wifi != null && wifi.isHeld()) wifi.release();
                if (wake != null && wake.isHeld()) wake.release();
                main.post(() -> state = failure != null ? failure : new State(false, false, R.string.stopped, R.string.stopped_hint));
                Diagnostics.log("ZLNA stopped" + (failure == null ? "" : ": " + getString(failure.detail, failure.args)));
            }
        }, "zlna-shutdown").start();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
