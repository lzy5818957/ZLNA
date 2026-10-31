package app.zlna;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.view.*;
import android.widget.*;

/** A single screen: choose a folder, run, stop. No network configuration. */
public final class MainActivity extends Activity {
    private static final int BG = 0xff101815, CARD = 0xff1b2721, GREEN = 0xff9ce8c1, WHITE = 0xfff0f5ef, MUTED = 0xff9caea2;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusTitle, statusDetail, folderTitle, folderMeta, badge;
    private Button folderButton, runButton;
    private Beacon beacon;
    private SharedPreferences preferences;
    private SetupGuide guide;
    private ScrollView scroll;
    private boolean compact;
    private SharingService.State rendered;
    private final Runnable refresh = new Runnable() { public void run() { render(); handler.postDelayed(this, 350); } };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); preferences = getSharedPreferences("zlna", MODE_PRIVATE);
        guide = new SetupGuide(this);
        compact = getResources().getBoolean(R.bool.compact_layout);
        FrameLayout frame = new FrameLayout(this); frame.setBackgroundColor(BG);
        frame.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()); view.setPadding(i.left, i.top, i.right, i.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        frame.addView(scroll); setContentView(frame);
        LinearLayout page = column(); int pad = dp(compact ? 14 : 28); page.setPadding(pad, dp(compact ? 10 : 24), pad, dp(16)); scroll.addView(page);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("ZLNA", 24, WHITE); brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD); brand.setLetterSpacing(0.13f);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        badge = text(getString(R.string.badge_idle), 11, MUTED); badge.setPadding(dp(12), dp(6), dp(12), dp(6)); badge.setBackground(shape(CARD, 30)); header.addView(badge); page.addView(header);
        if (!compact) {
            gap(page, 32);
            TextView heading = text(getString(R.string.heading), 32, WHITE); heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD); heading.setLineSpacing(dp(5), 1); page.addView(heading);
            gap(page, 12);
            TextView intro = text(getString(R.string.intro), 14, MUTED); intro.setLineSpacing(dp(5), 1); page.addView(intro);
        }
        gap(page, compact ? 10 : 28);
        LinearLayout status = column(); int inset = dp(compact ? 12 : 20); status.setPadding(inset, inset, inset, inset); status.setBackground(shape(CARD, 22));
        LinearLayout statusTop = new LinearLayout(this); statusTop.setGravity(Gravity.CENTER_VERTICAL);
        beacon = new Beacon(); statusTop.addView(beacon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout labels = column(); labels.setPadding(dp(14), 0, 0, 0);
        TextView label = text(getString(R.string.status_label), 10, MUTED); label.setLetterSpacing(0.13f); labels.addView(label);
        statusTitle = text(getString(R.string.ready), 20, WHITE); statusTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD); labels.addView(statusTitle);
        statusTop.addView(labels, new LinearLayout.LayoutParams(0, -2, 1)); status.addView(statusTop);
        gap(status, 12); statusDetail = text("", 13, MUTED); statusDetail.setLineSpacing(dp(4), 1); status.addView(statusDetail); page.addView(status);
        gap(page, compact ? 10 : 24);
        int compactRunPosition = page.getChildCount();
        TextView sourceLabel = text(getString(R.string.media_folder), 12, MUTED); page.addView(sourceLabel); gap(page, 10);
        LinearLayout folder = column(); folder.setPadding(inset, dp(compact ? 6 : 16), inset, dp(compact ? 4 : 14)); GradientDrawable outline = shape(BG, 18); outline.setStroke(dp(1), 0xff34463a); folder.setBackground(outline);
        folderTitle = text(getString(R.string.choose_videos), 17, WHITE); folderTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD); folderTitle.setMaxLines(2); folder.addView(folderTitle);
        folderMeta = text(getString(R.string.include_subfolders), 12, MUTED); folderMeta.setPadding(0, dp(5), 0, dp(9)); folder.addView(folderMeta);
        if (compact) folderMeta.setVisibility(View.GONE);
        folderButton = button(getString(R.string.choose_folder), CARD, GREEN); folderButton.setTextSize(13); folderButton.setOnClickListener(v -> chooseFolder()); folder.addView(folderButton, new LinearLayout.LayoutParams(-1, dp(48))); page.addView(folder);
        gap(page, compact ? 10 : 20);
        runButton = button(getString(R.string.start), GREEN, BG); runButton.setOnClickListener(v -> toggle());
        page.addView(runButton, compact ? compactRunPosition : -1, new LinearLayout.LayoutParams(-1, dp(compact ? 48 : 58)));
        if (compact) { View spacer = new View(this); page.addView(spacer, compactRunPosition + 1, new LinearLayout.LayoutParams(1, dp(10))); }
        gap(page, 13);
        TextView note = text(getString(R.string.sharing_note), 11, MUTED); note.setGravity(Gravity.CENTER); page.addView(note);
        gap(page, compact ? 4 : 16);
        Button setup = button(getString(R.string.setup), CARD, GREEN); setup.setTextSize(14); setup.setOnClickListener(v -> guide.show()); page.addView(setup, new LinearLayout.LayoutParams(-1, dp(48)));
        Button help = button(getString(R.string.help), BG, MUTED); help.setTextSize(12); help.setOnClickListener(v -> help()); page.addView(help, new LinearLayout.LayoutParams(-1, dp(48)));
        render();
        if (state != null) scroll.post(() -> scroll.scrollTo(0, state.getInt("scroll")));
        if (!preferences.getBoolean("setupSeen", false) || (state != null && state.getBoolean("guide"))) {
            guide.show(); preferences.edit().putBoolean("setupSeen", true).apply();
        }
    }
    SharedPreferences prefs() { return preferences; }
    void chooseFolder() {
        if (SharingService.state.active) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION).putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        String previous = preferences.getString("folder", null); if (previous != null) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(previous));
        try { startActivityForResult(intent, 10); } catch (ActivityNotFoundException e) { message(getString(R.string.picker_unavailable)); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 10 || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            String name = FolderLibrary.name(getContentResolver(), uri);
            String previous = preferences.getString("folder", null);
            preferences.edit().putString("folder", uri.toString()).putString("folderName", name).apply();
            if (previous != null && !previous.equals(uri.toString())) try { getContentResolver().releasePersistableUriPermission(Uri.parse(previous), Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) {}
            SharingService.state = new SharingService.State(false, false, R.string.ready, R.string.folder_selected); rendered = null; render(); guide.refresh();
        } catch (RuntimeException e) { Diagnostics.log("Folder selection failed: " + e); message(getString(R.string.folder_permission_error)); }
    }
    private void toggle() {
        if (SharingService.state.active) { stopService(new Intent(this, SharingService.class)); return; }
        if (!SetupGuide.hasFolderGrant(this)) { chooseFolder(); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notificationAsked", false)) {
            askNotifications(true); return;
        }
        startSharing();
    }
    void askNotifications(boolean runAfterwards) {
        if (Build.VERSION.SDK_INT >= 33) {
            preferences.edit().putBoolean("notificationAsked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, runAfterwards ? 20 : 21);
        }
    }
    private void startSharing() {
        try { startForegroundService(new Intent(this, SharingService.class)); }
        catch (RuntimeException e) { Diagnostics.log("Start service: " + e); message(getString(R.string.start_retry)); }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        guide.refresh();
        if (request == 20 && !SharingService.state.active && SetupGuide.hasFolderGrant(this)) startSharing();
    }
    private void render() {
        SharingService.State state = SharingService.state;
        if (rendered == state) return; rendered = state;
        statusTitle.setText(state.title); statusDetail.setText(state.detailText(this));
        folderTitle.setText(preferences.getString("folderName", getString(preferences.contains("folder") ? R.string.media_folder : R.string.choose_videos)));
        folderMeta.setText(state.active ? getString(R.string.folder_while_sharing) : preferences.contains("folder") && !SetupGuide.hasFolderGrant(this) ? getString(R.string.folder_expired) : getString(R.string.include_subfolders));
        folderButton.setText(preferences.contains("folder") ? getString(R.string.change_folder) : getString(R.string.choose_folder)); folderButton.setEnabled(!state.active); folderButton.setAlpha(state.active ? 0.4f : 1);
        if (compact) folderButton.setVisibility(state.active ? View.GONE : View.VISIBLE);
        runButton.setText(state.active ? getString(R.string.stop) : getString(R.string.start)); runButton.setBackground(shape(state.active ? 0xff34463a : GREEN, 18)); runButton.setTextColor(state.active ? WHITE : BG);
        badge.setText(state.active ? getString(R.string.badge_running) : getString(R.string.badge_idle)); badge.setTextColor(state.active ? GREEN : MUTED);
        beacon.running = state.active && !state.preparing; beacon.invalidate();
        guide.refresh();
    }
    private void help() {
        new AlertDialog.Builder(this).setTitle(getString(R.string.help_title))
            .setMessage(getString(R.string.help_body) + "\n\n" + getString(R.string.flip_help))
            .setPositiveButton(getString(R.string.ok), null).setNeutralButton(getString(R.string.view_diagnostics), (d, w) -> diagnostics()).show();
    }
    private void diagnostics() {
        String report = Diagnostics.report(this);
        TextView text = text(report, 12, WHITE); text.setTextIsSelectable(true); text.setPadding(dp(20), dp(12), dp(20), dp(12)); text.setTypeface(Typeface.MONOSPACE);
        ScrollView view = new ScrollView(this); view.addView(text);
        new AlertDialog.Builder(this).setTitle(getString(R.string.diagnostics)).setView(view).setPositiveButton(getString(R.string.close), null).setNeutralButton(getString(R.string.copy), (d, w) -> {
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(getString(R.string.diagnostics_label), report)); Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show();
        }).show();
    }
    private void message(String text) { new AlertDialog.Builder(this).setMessage(text).setPositiveButton(getString(R.string.ok), null).show(); }
    @Override protected void onResume() { super.onResume(); rendered = null; handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putBoolean("guide", guide.showing()); state.putInt("scroll", scroll.getScrollY());
    }
    @Override protected void onDestroy() { guide.close(); super.onDestroy(); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private TextView text(String value, int size, int color) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setIncludeFontPadding(false); return v; }
    private Button button(String value, int bg, int fg) { Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setTextSize(17); b.setTextColor(fg); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setBackground(shape(bg, 16)); b.setStateListAnimator(null); b.setMinHeight(0); b.setMinimumHeight(0); return b; }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private void gap(LinearLayout parent, int height) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private final class Beacon extends View {
        boolean running; final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); final Path path = new Path();
        Beacon() { super(MainActivity.this); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            float c = getWidth() / 2f;
            paint.setColor(running ? 0xff304c3d : 0xff293a30); canvas.drawCircle(c, c, c, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(running ? GREEN : MUTED); canvas.drawCircle(c, c, c - dp(5), paint); paint.setStyle(Paint.Style.FILL);
            path.reset(); path.moveTo(c - dp(4), c - dp(8)); path.lineTo(c + dp(8), c); path.lineTo(c - dp(4), c + dp(8)); path.close(); canvas.drawPath(path, paint);
        }
    }
}
