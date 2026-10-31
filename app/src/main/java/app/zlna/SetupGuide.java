package app.zlna;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.widget.*;
import java.util.Date;

/** First-open guide and live settings checks. A system grant and a playback test are distinct. */
final class SetupGuide {
    private final MainActivity activity;
    private AlertDialog dialog;
    private TextView battery, notifications, folder, proof;
    private Button folderAction, notificationAction;
    SetupGuide(MainActivity activity) { this.activity = activity; }
    boolean showing() { return dialog != null && dialog.isShowing(); }
    void close() { if (dialog != null) dialog.dismiss(); }

    void show() {
        if (showing()) return;
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(20), dp(8), dp(20), dp(12));
        battery = section(panel, activity.getString(R.string.setup_background));
        action(panel, activity.getString(R.string.open_battery), () -> {
            // App info leads to the manufacturer's per-app battery controls, including HyperOS.
            open(appInfo(), new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        });
        folder = section(panel, activity.getString(R.string.folder_required));
        folderAction = action(panel, activity.getString(R.string.choose_folder), activity::chooseFolder);
        notifications = section(panel, activity.getString(R.string.notifications_optional));
        notificationAction = action(panel, activity.getString(R.string.set_notifications), this::manageNotifications);
        proof = section(panel, activity.getString(R.string.proof_title));
        ScrollView scroll = new ScrollView(activity); scroll.addView(panel);
        dialog = new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.setup)).setView(scroll)
                .setPositiveButton(activity.getString(R.string.back_home), null).create();
        dialog.setOnDismissListener(d -> dialog = null);
        dialog.show(); refresh();
    }
    void refresh() {
        if (!showing()) return;
        PowerManager power = activity.getSystemService(PowerManager.class);
        boolean exempt = power.isIgnoringBatteryOptimizations(activity.getPackageName());
        boolean restricted = Build.VERSION.SDK_INT >= 28 && activity.getSystemService(ActivityManager.class).isBackgroundRestricted();
        battery.setText(activity.getString(R.string.battery_check, activity.getString(exempt ? R.string.enabled : R.string.disabled),
                restricted ? activity.getString(R.string.restricted) : ""));
        String uri = activity.prefs().getString("folder", null);
        boolean readable = hasFolderGrant(activity);
        folder.setText(readable ? activity.getString(R.string.folder_granted, activity.prefs().getString("folderName", activity.getString(R.string.media_folder)))
                : uri == null ? activity.getString(R.string.folder_prompt) : activity.getString(R.string.grant_expired));
        folderAction.setEnabled(!SharingService.state.active);
        folderAction.setText(SharingService.state.active ? activity.getString(R.string.stop_to_change) : readable ? activity.getString(R.string.change_folder) : activity.getString(R.string.choose_folder));
        NotificationManager nm = activity.getSystemService(NotificationManager.class);
        NotificationChannel channel = nm.getNotificationChannel("sharing");
        boolean visible = nm.areNotificationsEnabled() && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
        notifications.setText(activity.getString(R.string.notification_check, activity.getString(visible ? R.string.enabled : R.string.disabled)));
        notificationAction.setText(visible ? activity.getString(R.string.manage_notifications) : activity.getString(R.string.enable_notifications));
        long at = SharingService.screenVerifiedAt;
        proof.setText(at != 0 ? activity.getString(R.string.proof_verified, DateFormat.getTimeFormat(activity).format(new Date(at)))
                : activity.getString(R.string.proof_pending));
    }
    static boolean hasFolderGrant(MainActivity a) {
        String uri = a.prefs().getString("folder", null);
        if (uri == null) return false;
        for (UriPermission p : a.getContentResolver().getPersistedUriPermissions())
            if (p.isReadPermission() && p.getUri().toString().equals(uri)) return true;
        return false;
    }
    private void manageNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !activity.prefs().getBoolean("notificationAsked", false)) {
            activity.askNotifications(false); return;
        }
        NotificationChannel c = activity.getSystemService(NotificationManager.class).getNotificationChannel("sharing");
        Intent settings = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
        if (activity.getSystemService(NotificationManager.class).areNotificationsEnabled() && c != null && c.getImportance() == NotificationManager.IMPORTANCE_NONE)
            settings.setAction(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, "sharing");
        open(settings, appInfo());
    }
    private Intent appInfo() { return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.getPackageName(), null)); }
    private void open(Intent... options) {
        for (Intent i : options) {
            try { activity.startActivity(i); return; }
            catch (ActivityNotFoundException | SecurityException ignored) { }
        }
        new AlertDialog.Builder(activity).setMessage(activity.getString(R.string.settings_fallback)).setPositiveButton(activity.getString(R.string.ok), null).show();
    }
    private TextView section(LinearLayout panel, String name) {
        TextView title = new TextView(activity); title.setText(name); title.setTextSize(16);
        title.setTextColor(0xff9ce8c1); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, dp(12), 0, dp(4)); panel.addView(title);
        TextView detail = new TextView(activity); detail.setTextSize(14); detail.setTextColor(0xfff0f5ef);
        panel.addView(detail); return detail;
    }
    private Button action(LinearLayout panel, String label, Runnable callback) {
        Button b = new Button(activity, null, android.R.attr.borderlessButtonStyle); b.setText(label); b.setTextColor(0xff9ce8c1);
        b.setMinHeight(dp(48)); b.setOnClickListener(v -> callback.run()); panel.addView(b); return b;
    }
    private int dp(int n) { return Math.round(n * activity.getResources().getDisplayMetrics().density); }
}
