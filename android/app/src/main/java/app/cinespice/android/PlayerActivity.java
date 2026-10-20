package app.cinespice.android;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import org.videolan.libvlc.*;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.util.VLCVideoLayout;
import java.io.*;
import java.util.*;

public final class PlayerActivity extends androidx.appcompat.app.AppCompatActivity {
    private Telegram telegram;
    private Library library;
    private Video video;
    private LibVLC vlc;
    private MediaPlayer player;
    private RangeServer bridge;
    private FrameLayout root;
    private LinearLayout controls;
    private FrameLayout optionsOverlay;
    private int pictureChoice;
    private TextView message, time, remaining, speedLabel, pictureLabel;
    private ProgressBar spinner;
    private SeekBar seek;
    private Button play, unlock, downloadFallback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean released;
    private boolean locked, dragging, positioned, resumeApplied, playbackError, resumeAfterPause, foreground, landscape, edges;
    private long initialPosition, lastTime = -1, lastMovement, lastSaved, openingStarted;
    private long session;
    private int importType;
    private final List<File> externalFiles = new ArrayList<>();
    private final Runnable hide = () -> { if (!released && optionsOverlay == null && !playbackError && player != null && player.isPlaying()) controls.setVisibility(View.GONE); };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (released || player == null) return;
            if (telegram.generation != session || !telegram.stage.equals("ready")) { finish(); return; }
            long position = player.getTime(), duration = player.getLength();
            if (!positioned && SystemClock.elapsedRealtime() - openingStarted > 45000) showPlaybackError();
            if (positioned && !resumeApplied && player.isSeekable()) {
                resumeApplied = true; if (initialPosition >= 5000) player.setTime(initialPosition);
            }
            if (!dragging) seek.setProgress(duration > 0 ? (int) (position * 1000 / duration) : 0);
            seek.setEnabled(player.isSeekable());
            time.setText(timelineTime(position));
            remaining.setText("−" + timelineTime(Math.max(0, duration - position)));
            speedLabel.setText(String.format(Locale.ROOT, "%g×", (double) player.getRate()));
            play.setText(player.isPlaying() ? "Ⅱ" : "▶");
            play.setContentDescription(player.isPlaying() ? "Pause" : "Play");
            if (position != lastTime) { lastMovement = SystemClock.elapsedRealtime(); lastTime = position; }
            boolean stalled = player.isPlaying() && SystemClock.elapsedRealtime() - lastMovement >= 2000;
            spinner.setVisibility(!playbackError && (!positioned || stalled) ? View.VISIBLE : View.GONE);
            if (positioned && !playbackError) message.setText(stalled ? "Buffering…" : "");
            if (SystemClock.elapsedRealtime() - lastSaved >= 5000) save();
            handler.postDelayed(this, 500);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        telegram = ((CinespiceApp) getApplication()).telegram;
        session = getIntent().getLongExtra("generation", -1);
        try {
            telegram.check(session); library = telegram.requireLibrary();
            video = new Video(new JSONObject(Objects.requireNonNull(getIntent().getStringExtra("video"))));
            initialPosition = state != null ? state.getLong("position") : getIntent().getBooleanExtra("resume", true) ? library.position(video.key()) : 0;
            landscape = state != null && state.getBoolean("landscape");
            setRequestedOrientation(landscape ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            layout(); start();
        } catch (Exception | LinkageError e) {
            new AlertDialog.Builder(this).setTitle("Could not open video").setMessage(e.getMessage()).setPositiveButton("Back", (d, w) -> finish()).setOnCancelListener(d -> finish()).show();
        }
    }
    private void layout() {
        root = new FrameLayout(this); root.setBackgroundColor(android.graphics.Color.BLACK); setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(edges ? 0 : insets.getSystemWindowInsetLeft(), edges ? 0 : insets.getSystemWindowInsetTop(), edges ? 0 : insets.getSystemWindowInsetRight(), edges ? 0 : insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        }); root.requestApplyInsets();
        controls = Ui.column(this); controls.setPadding(0, 0, 0, 0); controls.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        LinearLayout top = Ui.row(this); top.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4)); top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xD9000000, 0xA6000000, 0x00000000}));
        top.addView(playerIcon("⌄", "Close player", 22, this::finish));
        LinearLayout title = Ui.column(this); title.setPadding(Ui.dp(this, 4), 0, 0, 0);
        TextView name = Ui.text(this, video.name, 14, Ui.TEXT); name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)); name.setMaxLines(1); name.setEllipsize(android.text.TextUtils.TruncateAt.END); name.setPadding(0, 0, 0, 0); title.addView(name);
        TextView brand = Ui.text(this, "CINESPICE", 9, Ui.ACCENT); brand.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)); brand.setLetterSpacing(.12f); brand.setPadding(0, Ui.dp(this, 2), 0, 0); title.addView(brand);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(playerIcon("▣", "Lock controls", 18, () -> { locked = true; controls.setVisibility(View.GONE); unlock.setVisibility(View.VISIBLE); }));
        controls.addView(top, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout spacer = new LinearLayout(this); controls.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bottom = Ui.column(this); bottom.setPadding(Ui.dp(this, 12), Ui.dp(this, 22), Ui.dp(this, 12), Ui.dp(this, 8)); bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xF0000000, 0xD9000000, 0x00000000}));
        LinearLayout status = Ui.row(this); status.setPadding(0, 0, 0, Ui.dp(this, 2));
        spinner = new ProgressBar(this); spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT)); status.addView(spinner, new LinearLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, 16)));
        message = Ui.text(this, "Opening media…", 11, Ui.ACCENT); message.setPadding(Ui.dp(this, 7), 0, 0, 0); status.addView(message, new LinearLayout.LayoutParams(0, -2, 1)); bottom.addView(status);
        downloadFallback = Ui.primary(this, "Download to try local playback", this::downloadAndClose);
        downloadFallback.setVisibility(View.GONE); bottom.addView(downloadFallback);
        LinearLayout timeline = Ui.row(this); time = Ui.text(this, "0:00", 11, 0xFFEAE6E3); time.setTypeface(android.graphics.Typeface.MONOSPACE); time.setPadding(0, 0, Ui.dp(this, 8), 0); timeline.addView(time);
        seek = new SeekBar(this); seek.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT)); seek.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT)); seek.setMax(1000); seek.setContentDescription("Video timeline"); timeline.addView(seek, new LinearLayout.LayoutParams(0, Ui.dp(this, 34), 1));
        remaining = Ui.text(this, "−0:00", 11, 0xFFEAE6E3); remaining.setTypeface(android.graphics.Typeface.MONOSPACE); remaining.setGravity(Gravity.RIGHT); remaining.setPadding(Ui.dp(this, 8), 0, 0, 0); timeline.addView(remaining); bottom.addView(timeline);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) { dragging = true; handler.removeCallbacks(hide); }
            public void onStopTrackingTouch(SeekBar bar) { if (player != null && player.isSeekable()) player.setTime(player.getLength() * bar.getProgress() / 1000); dragging = false; reveal(); }
            public void onProgressChanged(SeekBar bar, int progress, boolean user) { if (user && player != null) { long pos = player.getLength() * progress / 1000; time.setText(timelineTime(pos)); remaining.setText("−" + timelineTime(Math.max(0, player.getLength() - pos))); } }
        });
        LinearLayout transport = Ui.row(this); transport.setGravity(Gravity.CENTER); transport.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 4));
        transport.addView(playerIcon("↶10", "Back ten seconds", 17, () -> skip(-10000)));
        play = playerIcon("Ⅱ", "Pause", 26, () -> { if (player != null) { if (player.isPlaying()) { player.pause(); save(); } else player.play(); play.setText(player.isPlaying() ? "Ⅱ" : "▶"); play.setContentDescription(player.isPlaying() ? "Pause" : "Play"); reveal(); } });
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 58)); playParams.setMargins(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0); transport.addView(play, playParams);
        transport.addView(playerIcon("10↷", "Forward ten seconds", 17, () -> skip(10000))); bottom.addView(transport);
        LinearLayout menu = Ui.row(this); menu.setGravity(Gravity.CENTER); menu.setPadding(0, Ui.dp(this, 2), 0, 0);
        LinearLayout speedTool = playerTool("◴", "1×", "Playback speed", this::speed); speedLabel = (TextView) speedTool.getChildAt(1); menu.addView(speedTool, equalTool());
        menu.addView(playerTool("CC", "Tracks", "Audio and subtitles", () -> tracks(true)), equalTool());
        LinearLayout pictureTool = playerTool("▣", "Fit", "Picture options", this::picture); pictureLabel = (TextView) pictureTool.getChildAt(1); menu.addView(pictureTool, equalTool());
        menu.addView(playerTool("↻", "Rotate", "Rotate screen", () -> { landscape = !landscape; setRequestedOrientation(landscape ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT); reveal(); }), equalTool());
        menu.addView(playerTool("•••", "More", "More options", () -> options("More", new String[]{"Audio tracks", "Subtitles", "Download"}, -1, index -> {
            if (index == 0) { tracks(false); return; }
            if (index == 1) { tracks(true); return; }
            downloadAndClose();
        })), equalTool());
        controls.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
        unlock = Ui.primary(this, "Unlock", () -> { locked = false; unlock.setVisibility(View.GONE); reveal(); }); unlock.setVisibility(View.GONE);
    }
    private LinearLayout.LayoutParams equalTool() { return new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1); }
    private Button playerIcon(String glyph, String description, int size, Runnable action) {
        Button button = Ui.quiet(this, glyph, action); button.setTextSize(size); button.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)); button.setTextColor(Ui.TEXT); button.setMinHeight(Ui.dp(this, 48)); button.setMinimumHeight(Ui.dp(this, 48)); button.setPadding(0, 0, 0, 0); button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)); button.setContentDescription(description); button.setMaxLines(1); button.setGravity(Gravity.CENTER); return button;
    }
    private LinearLayout playerTool(String icon, String title, String description, Runnable action) {
        LinearLayout item = new LinearLayout(this); item.setOrientation(LinearLayout.VERTICAL); item.setGravity(Gravity.CENTER); item.setClickable(true); item.setFocusable(true); item.setContentDescription(description); item.setOnClickListener(v -> action.run());
        TextView glyph = Ui.text(this, icon, 17, Ui.TEXT); glyph.setGravity(Gravity.CENTER); glyph.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)); glyph.setPadding(0, 0, 0, 0); item.addView(glyph, new LinearLayout.LayoutParams(-1, Ui.dp(this, 24)));
        TextView label = Ui.text(this, title, 10, 0xFFEAE6E3); label.setGravity(Gravity.CENTER); label.setPadding(0, 0, 0, 0); label.setSingleLine(true); label.setEllipsize(android.text.TextUtils.TruncateAt.END); item.addView(label, new LinearLayout.LayoutParams(-1, Ui.dp(this, 18)));
        return item;
    }
    private String timelineTime(long milliseconds) { long seconds = Math.max(0, milliseconds / 1000); return seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60) : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60); }
    private void start() throws Exception {
        ArrayList<String> options = new ArrayList<>(List.of("--network-caching=1500", "--no-video-title-show", "--freetype-rel-fontsize=" + getPreferences(0).getInt("subtitleSize", 16)));
        vlc = new LibVLC(this, options); player = new MediaPlayer(vlc);
        VLCVideoLayout surface = new VLCVideoLayout(this); root.addView(surface, 0, new FrameLayout.LayoutParams(-1, -1));
        player.attachViews(surface, null, true, false);
        View gestures = new View(this); root.addView(gestures, new FrameLayout.LayoutParams(-1, -1));
        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) { gestures.performClick(); return true; }
            @Override public boolean onDoubleTap(MotionEvent event) { if (!locked) skip(event.getX() < root.getWidth() / 2f ? -10000 : 10000); return true; }
        });
        gestures.setContentDescription("Show or hide player controls");
        gestures.setOnClickListener(v -> { if (!locked) { if (controls.getVisibility() == View.VISIBLE) controls.setVisibility(View.GONE); else reveal(); } });
        gestures.setOnTouchListener((v, event) -> detector.onTouchEvent(event));
        root.addView(controls, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams corner = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END); root.addView(unlock, corner);
        String local = telegram.completedPath(video);
        Uri uri;
        if (!local.isEmpty()) uri = Uri.fromFile(new File(local));
        else { if (video.size <= 0) throw new IOException("Video size is unknown. Download first, then try again."); bridge = new RangeServer(telegram, video); uri = Uri.parse(bridge.url()); }
        Media media = new Media(vlc, uri); media.setHWDecoderEnabled(true, false); media.addOption(":network-caching=1500");
        player.setMedia(media); media.release();
        openingStarted = lastMovement = SystemClock.elapsedRealtime();
        player.setEventListener(event -> {
            if (released) return;
            if (event.type == MediaPlayer.Event.Playing) {
                if (!positioned) positioned = true;
                playbackError = false;
                if (!foreground) { resumeAfterPause = true; player.pause(); }
                message.setText(""); reveal();
            } else if (event.type == MediaPlayer.Event.EncounteredError) {
                showPlaybackError();
            } else if (event.type == MediaPlayer.Event.EndReached) {
                if (!positioned || player.getLength() <= 0) { showPlaybackError(); return; }
                library.progress(video, Math.max(0, player.getLength()), Math.max(0, player.getLength()), true); controls.setVisibility(View.VISIBLE); handler.removeCallbacks(hide);
            }
        });
        player.play(); handler.post(tick); reveal();
    }
    private void showPlaybackError() {
        spinner.setVisibility(View.GONE); positioned = true; playbackError = true;
        message.setText("VLC could not play this file.");
        downloadFallback.setVisibility(telegram.completedPath(video).isEmpty() ? View.VISIBLE : View.GONE); controls.setVisibility(View.VISIBLE); handler.removeCallbacks(hide);
    }
    private void downloadAndClose() {
        if (player != null) player.pause();
        if (bridge != null) { bridge.close(); bridge = null; }
        downloadFallback.setEnabled(false);
        telegram.io.execute(() -> {
            try { telegram.download(video); runOnUiThread(() -> { Toast.makeText(this, "Downloading. Reopen the file after download completes.", Toast.LENGTH_LONG).show(); finish(); }); }
            catch (Exception e) { runOnUiThread(() -> { message.setText(e.getMessage()); downloadFallback.setEnabled(true); }); }
        });
    }
    private void reveal() { if (locked) return; controls.setVisibility(View.VISIBLE); handler.removeCallbacks(hide); handler.postDelayed(hide, 4500); }
    private void skip(long delta) { if (player != null && player.isSeekable()) { player.setTime(Math.max(0, Math.min(player.getLength(), player.getTime() + delta))); reveal(); } }
    private void save() {
        if (player != null && positioned && player.getTime() >= 0 && library != null) library.progress(video, player.getTime(), player.getLength(), false);
        lastSaved = SystemClock.elapsedRealtime();
    }
    private void tracks(boolean subtitles) {
        if (player == null) return;
        handler.removeCallbacks(hide);
        MediaPlayer.TrackDescription[] tracks = subtitles ? player.getSpuTracks() : player.getAudioTracks();
        ArrayList<String> names = new ArrayList<>(); ArrayList<Integer> ids = new ArrayList<>();
        if (subtitles) { names.add("Off"); ids.add(-1); }
        if (tracks != null) for (MediaPlayer.TrackDescription track : tracks) { if (subtitles && track.id == -1) continue; names.add(track.name); ids.add(track.id); }
        names.add("Add external " + (subtitles ? "subtitles" : "audio") + "…"); names.add("Adjust delay…"); if (subtitles) names.add("Subtitle size…");
        int selected = ids.indexOf(subtitles ? player.getSpuTrack() : player.getAudioTrack());
        options(subtitles ? "Subtitles" : "Audio tracks", names.toArray(new String[0]), selected, index -> {
            if (index < ids.size()) { if (subtitles) player.setSpuTrack(ids.get(index)); else player.setAudioTrack(ids.get(index)); tracks(subtitles); }
            else if (index == ids.size()) { importType = subtitles ? IMedia.Slave.Type.Subtitle : IMedia.Slave.Type.Audio; startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 200); }
            else if (index == ids.size() + 1) delay(subtitles);
            else subtitleSize();
        });
    }
    private void delay(boolean subtitles) {
        LinearLayout panel = Ui.column(this); TextView label = Ui.text(this, "", 18, Ui.TEXT); panel.addView(label);
        Runnable update = () -> label.setText(String.format(Locale.ROOT, "Delay: %.2f seconds", (subtitles ? player.getSpuDelay() : player.getAudioDelay()) / 1000000.0)); update.run();
        LinearLayout buttons = Ui.row(this);
        for (int direction : new int[]{-1, 1}) Ui.weighted(buttons, Ui.button(this, direction < 0 ? "−0.25s" : "+0.25s", () -> {
            long value = Math.max(-30000000, Math.min(30000000, (subtitles ? player.getSpuDelay() : player.getAudioDelay()) + direction * 250000L));
            boolean changed = subtitles ? player.setSpuDelay(value) : player.setAudioDelay(value);
            if (!changed) Toast.makeText(this, "VLC could not change this delay.", Toast.LENGTH_SHORT).show(); update.run();
        })); panel.addView(buttons);
        panel(subtitles ? "Subtitle delay" : "Audio delay", panel);
    }
    private void subtitleSize() {
        String[] names = {"Smallest", "Small", "Normal", "Large", "Largest"}; int[] sizes = {20,18,16,12,6};
        int current = getPreferences(0).getInt("subtitleSize", 16), selected = 2; for (int i = 0; i < sizes.length; i++) if (sizes[i] == current) selected = i;
        options("Subtitle size · reopens video", names, selected, which -> {
            getPreferences(0).edit().putInt("subtitleSize", sizes[which]).apply(); save();
            getIntent().putExtra("resume", true); closeOptions(); recreate();
        });
    }
    private void speed() {
        float[] values = {.25f,.5f,.75f,1,1.25f,1.5f,1.75f,2,3,4}; String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++) names[i] = values[i] + "×";
        int selected = -1; for (int i = 0; i < values.length; i++) if (Math.abs(player.getRate() - values[i]) < .01f) selected = i;
        options("Playback speed", names, selected, index -> { player.setRate(values[index]); Toast.makeText(this, names[index], Toast.LENGTH_SHORT).show(); reveal(); });
    }
    private void picture() {
        options("Picture", new String[]{"Fit", "Fill / crop", "16:9", "4:3", "21:9", edges ? "Respect screen edges" : "Use screen edges"}, pictureChoice, index -> {
            if (index < 5) { pictureChoice = index; pictureLabel.setText(new String[]{"Fit", "Fill", "16:9", "4:3", "21:9"}[index]); }
            if (index == 5) { edges = !edges; getWindow().getDecorView().setSystemUiVisibility(edges ? View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY : View.SYSTEM_UI_FLAG_VISIBLE); root.requestApplyInsets(); }
            else if (index == 4) { player.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT); player.setAspectRatio("21:9"); }
            else { player.setAspectRatio(null); player.setVideoScale(new MediaPlayer.ScaleType[]{MediaPlayer.ScaleType.SURFACE_BEST_FIT, MediaPlayer.ScaleType.SURFACE_FILL, MediaPlayer.ScaleType.SURFACE_16_9, MediaPlayer.ScaleType.SURFACE_4_3}[index]); }
            reveal();
        });
    }
    private void options(String title, String[] labels, int selected, java.util.function.IntConsumer action) {
        LinearLayout content = Ui.column(this); content.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), Ui.dp(this, 8));
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            Button choice = Ui.quiet(this, (i == selected ? "✓ " : "") + labels[i], () -> { closeOptions(); action.accept(index); });
            choice.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); choice.setTextColor(i == selected ? 0xFFFF774A : Ui.TEXT);
            content.addView(choice, new LinearLayout.LayoutParams(-1, -2));
        }
        panel(title, content);
    }
    private void panel(String title, View content) {
        closeOptions(); handler.removeCallbacks(hide);
        optionsOverlay = new FrameLayout(this); optionsOverlay.setContentDescription("Player options");
        optionsOverlay.setOnClickListener(v -> closeOptions());
        LinearLayout panel = Ui.column(this); panel.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 12));
        panel.setOnClickListener(v -> { });
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(0xFF141414); background.setCornerRadius(Ui.dp(this, 16)); background.setStroke(Ui.dp(this, 1), 0xFFFF774A); panel.setBackground(background);
        LinearLayout heading = Ui.row(this); TextView label = Ui.text(this, title, 16, Ui.TEXT); Ui.weighted(heading, label);
        Button close = Ui.quiet(this, "×", this::closeOptions); close.setContentDescription("Close player options"); heading.addView(close); panel.addView(heading);
        final int maximum = (int) (getResources().getDisplayMetrics().heightPixels * (landscape ? .60f : .32f));
        ScrollView scroll = new ScrollView(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) { super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(maximum, View.MeasureSpec.AT_MOST)); }
        }; scroll.addView(content); panel.addView(scroll);
        int width = Math.min(getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 32), Ui.dp(this, landscape ? 340 : 420));
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(width, -2, Gravity.BOTTOM | (landscape ? Gravity.END : Gravity.CENTER_HORIZONTAL));
        position.setMargins(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16)); optionsOverlay.addView(panel, position);
        root.addView(optionsOverlay, new FrameLayout.LayoutParams(-1, -1));
    }
    private void closeOptions() { if (optionsOverlay != null) { root.removeView(optionsOverlay); optionsOverlay = null; reveal(); } }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 200 || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData(); int type = importType;
        message.setText("Importing external track…");
        telegram.io.execute(() -> {
            File copy = null;
            try {
                String name = "track";
                try (android.database.Cursor rows = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) { if (rows != null && rows.moveToFirst()) name = rows.getString(0); }
                String extension = name.contains(".") ? name.substring(name.lastIndexOf('.')).replaceAll("[^.a-zA-Z0-9]", "") : "";
                if (extension.length() > 12) extension = "";
                copy = File.createTempFile("track-", extension, getCacheDir());
                try (InputStream input = getContentResolver().openInputStream(uri); OutputStream output = new FileOutputStream(copy)) {
                    if (input == null) throw new IOException("Could not read track");
                    byte[] buffer = new byte[64 * 1024]; int count; long size = 0;
                    while ((count = input.read(buffer)) != -1) { if (released) throw new InterruptedIOException("Player closed"); size += count; if (size > 512L * 1024 * 1024) throw new IOException("Track exceeds 512 MB import limit"); output.write(buffer, 0, count); }
                }
                File file = copy;
                runOnUiThread(() -> {
                    if (released) { file.delete(); return; }
                    externalFiles.add(file);
                    boolean added = player.addSlave(type, Uri.fromFile(file), true); message.setText(added ? "Track added." : "VLC could not open this track."); reveal();
                });
            } catch (Exception e) { if (copy != null) copy.delete(); runOnUiThread(() -> { if (!released) message.setText("Import failed: " + e.getMessage()); }); }
        });
    }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); if (player != null) state.putLong("position", player.getTime()); state.putBoolean("landscape", landscape); }
    @Override protected void onPause() { foreground = false; if (player != null && !released) { resumeAfterPause = player.isPlaying(); player.pause(); save(); } super.onPause(); }
    @Override protected void onResume() { super.onResume(); foreground = true; if (player != null && resumeAfterPause && !released) { resumeAfterPause = false; player.play(); } }
    @Override protected void onDestroy() {
        released = true; handler.removeCallbacksAndMessages(null); save();
        if (bridge != null) bridge.close();
        if (player != null) { player.setEventListener(null); player.stop(); player.detachViews(); player.release(); }
        if (vlc != null) vlc.release();
        for (File file : externalFiles) file.delete();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); super.onDestroy();
    }
}
