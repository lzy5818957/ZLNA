package app.zlna;

import android.app.*;
import android.accessibilityservice.AccessibilityService;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.inspector.WindowInspector;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** UI integration checks for the disposable API 36 ZLNA emulator, with its pre-authorized Movies fixture. */
public class SetupAndCoverTest {
    private final Instrumentation inst = InstrumentationRegistry.getInstrumentation();
    private final Context app = inst.getTargetContext();
    private final SharedPreferences prefs = app.getSharedPreferences("zlna", Context.MODE_PRIVATE);
    @Before public void dedicatedEmulator() {
        Assume.assumeTrue(Build.HARDWARE.contains("ranchu") && Build.VERSION.SDK_INT >= 35);
    }
    private String shell(String cmd) throws Exception {
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(inst.getUiAutomation().executeShellCommand(cmd))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[2048]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n); return out.toString("UTF-8");
        }
    }
    private MainActivity launch() {
        Intent i = new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return (MainActivity)inst.startActivitySync(i);
    }
    private TextView find(View v, String s) {
        if (v instanceof TextView t && t.getText().toString().contains(s) && t.isShown()) return t;
        if (v instanceof ViewGroup g) for (int j=0; j<g.getChildCount(); j++) { TextView t=find(g.getChildAt(j),s); if (t!=null) return t; }
        return null;
    }
    private TextView widget(String s) {
        AtomicReference<TextView> ref = new AtomicReference<>();
        inst.runOnMainSync(() -> {
            List<View> roots = WindowInspector.getGlobalWindowViews();
            for (int j=roots.size()-1; j>=0; j--) { TextView t=find(roots.get(j),s); if(t!=null) { ref.set(t); break; } }
        });
        return ref.get();
    }
    private void await(String detail, BooleanSupplier condition) {
        long end = SystemClock.elapsedRealtime()+8000;
        while (!condition.getAsBoolean()) { assertTrue(detail, SystemClock.elapsedRealtime()<end); SystemClock.sleep(100); }
    }
    private void click(String s) { await(s,()->widget(s)!=null); TextView v=widget(s); inst.runOnMainSync(v::performClick); inst.waitForIdleSync(); }
    private void screenshot(String name) throws Exception {
        inst.getUiAutomation().waitForIdle(650,6000);
        try (OutputStream out = new FileOutputStream(new File(app.getExternalFilesDir(null),name+".png"))) {
            Bitmap b=inst.getUiAutomation().takeScreenshot(); assertNotNull(b); b.compress(Bitmap.CompressFormat.PNG,100,out); b.recycle();
        }
    }
    @Test public void systemSettingsRecheckedAndStaleFolderDetected() throws Exception {
        String original=prefs.getString("folder",null);
        PowerManager pm=app.getSystemService(PowerManager.class);
        boolean exemptBefore=pm.isIgnoringBatteryOptimizations(app.getPackageName());
        try {
            shell("cmd deviceidle whitelist -app.zlna");
            prefs.edit().remove("setupSeen").putString("folder","content://example.invalid/tree/stale").commit();
            launch();
            await("first open prompts",()->widget(app.getString(R.string.setup_background))!=null);
            assertNotNull(widget(app.getString(R.string.grant_expired)));
            assertNotNull(widget(app.getString(R.string.battery_check, app.getString(R.string.disabled), "").split("\\n")[1].trim())); assertNotNull(widget(app.getString(R.string.proof_pending)));
            screenshot("setup-guide");
            click(app.getString(R.string.open_battery));
            await("system settings in front",()->{
                android.view.accessibility.AccessibilityNodeInfo r=inst.getUiAutomation().getRootInActiveWindow();
                return r!=null && "com.android.settings".contentEquals(String.valueOf(r.getPackageName()));
            });
            shell("cmd deviceidle whitelist +app.zlna");
            assertTrue(inst.getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK));
            await("return refreshes actual grant",()->widget(app.getString(R.string.battery_check, app.getString(R.string.enabled), "").split("\\n")[1].trim())!=null);
            assertNotNull(widget(app.getString(R.string.proof_pending))); assertFalse(SharingService.state.active);
            click(app.getString(R.string.back_home)); launch();
            assertNull(widget(app.getString(R.string.setup_background))); click(app.getString(R.string.setup));
            assertNotNull(widget(app.getString(R.string.notifications_optional)));
        } finally {
            if (!exemptBefore) shell("cmd deviceidle whitelist -app.zlna");
            if (original!=null) prefs.edit().putString("folder",original).commit(); else prefs.edit().remove("folder").commit();
        }
    }
    @Test public void resizeDuringSharingKeepsServerAndControls() throws Exception {
        prefs.edit().putBoolean("setupSeen",true).putBoolean("notificationAsked",true).commit();
        MainActivity a=launch(); Assume.assumeTrue("select the emulator Movies fixture first",SetupGuide.hasFolderGrant(a));
        try {
            click(app.getString(R.string.start)); await("shared videos",()->widget(app.getString(R.string.sharing))!=null);
            ServerIdentity before=new ServerIdentity();
            shell("wm size 750x1050");
            await("compact screen replaces large intro",()->widget(app.getString(R.string.heading))==null && widget(app.getString(R.string.stop))!=null);
            TextView stop=widget(app.getString(R.string.stop)); Rect rect=new Rect(); inst.runOnMainSync(()->assertTrue(stop.getGlobalVisibleRect(rect)));
            assertEquals(stop.getHeight(),rect.height());
            screenshot("cover-sharing");
            assertTrue(SharingService.state.active); assertTrue(before.sameServer());
            click(app.getString(R.string.setup)); assertNotNull(widget(app.getString(R.string.folder_granted, prefs.getString("folderName", app.getString(R.string.media_folder)))));
            shell("wm size reset");
            await("guide survives unfolding",()->widget(app.getString(R.string.heading))!=null && widget(app.getString(R.string.setup_background))!=null);
            assertTrue(before.sameServer()); click(app.getString(R.string.back_home)); click(app.getString(R.string.stop));
            await("service stopped",()->!SharingService.state.active);
        } finally { shell("wm size reset"); app.stopService(new Intent(app, SharingService.class)); }
    }
    /** Driven by scripts/check_screen_off.py, which reads a real video Range from the Mac. */
    @Test public void externalReadAfterOneMinute() throws Exception {
        Assume.assumeTrue("external".equals(InstrumentationRegistry.getArguments().getString("screenProbe")));
        prefs.edit().putBoolean("setupSeen",true).putBoolean("notificationAsked",true).commit();
        MainActivity a=launch(); assertTrue("authorize the Movies test fixture first",SetupGuide.hasFolderGrant(a));
        try {
            click(app.getString(R.string.start)); await("sharing",()->widget(app.getString(R.string.sharing))!=null);
            assertTrue(inst.getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN));
            await("screen goes off",()->!app.getSystemService(PowerManager.class).isInteractive());
            android.util.Log.i("ZLNA-test","locked " + InstrumentationRegistry.getArguments().getString("probeToken") + " " + ServerIdentity.lastUrls());
            long started=SystemClock.elapsedRealtime();
            while (SharingService.screenVerifiedAt == 0 && SystemClock.elapsedRealtime()-started < 95_000) SystemClock.sleep(100);
            assertNotEquals("no external video evidence",0L,SharingService.screenVerifiedAt);
            assertTrue("verified too early",SystemClock.elapsedRealtime()-started >= 59_000);
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard");
            click(app.getString(R.string.setup)); assertNotNull(widget(app.getString(R.string.proof_verified, android.text.format.DateFormat.getTimeFormat(app).format(new Date(SharingService.screenVerifiedAt)))));
            click(app.getString(R.string.back_home)); click(app.getString(R.string.stop)); await("stopped",()->!SharingService.state.active);
            assertEquals(0L,SharingService.screenVerifiedAt);
        } finally {
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard");
            app.stopService(new Intent(app,SharingService.class));
        }
    }
    private static class ServerIdentity {
        final String urls = lastUrls();
        static String lastUrls() {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            String found=""; for(String line:Diagnostics.report(context).split("\n")) if(line.contains("Server URLs:")) found=line;
            return found;
        }
        boolean sameServer(){return !urls.isEmpty() && urls.equals(lastUrls());}
    }
}
