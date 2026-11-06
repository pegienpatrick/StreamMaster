package net.ossrs.yasea.demo.studio;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import net.ossrs.yasea.demo.R;
import net.ossrs.yasea.engine.Camera2Source;
import net.ossrs.yasea.engine.EncoderCaps;
import net.ossrs.yasea.engine.MediaEncoders;
import net.ossrs.yasea.engine.ImageLayer;
import net.ossrs.yasea.engine.Layer;
import net.ossrs.yasea.engine.TextLayer;
import android.net.Uri;
import net.ossrs.yasea.engine.StreamEngine;
import net.ossrs.yasea.engine.VideoSource;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Main studio screen: live preview of the composite plus the controls. */
public class StudioActivity extends AppCompatActivity implements StreamEngine.Listener {

    private static final int REQ_PERMS = 1;
    private static final int REQ_PROJECTION = 2;
    private static final int REQ_IMAGE = 3;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable saveScene = new Runnable() {
        @Override
        public void run() {
            if (engine != null) {
                SceneStore.save(StudioActivity.this, engine);
            }
        }
    };

    /** Debounced: persists the scene shortly after the last edit. */
    private void scheduleSave() {
        ui.removeCallbacks(saveScene);
        ui.postDelayed(saveScene, 500);
    }

    private Prefs prefs;
    private StreamService service;
    private StreamEngine engine;
    private boolean bound;
    private SurfaceView preview;
    private Surface previewSurface;

    private TextView chipLive, chipRec, chipStats, banner;
    private ProgressBar micMeter;
    private ImageButton btnMic, btnRecord;
    private MaterialButton btnLive;
    private View blackout, bottomBar, topBar;

    private SceneEditorView editor;
    private View layerBar;
    private boolean inBlackout;
    private int blackoutTaps;
    private long lastTapMs;
    private boolean muted;
    private android.view.OrientationEventListener orientationListener;
    private int physicalRotation = -1;

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        applyOrientationLock();
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_studio);

        preview = findViewById(R.id.preview);
        editor = findViewById(R.id.editor);
        layerBar = findViewById(R.id.layerBar);
        findViewById(R.id.lbFront).setOnClickListener(v -> reorder(0));
        findViewById(R.id.lbForward).setOnClickListener(v -> reorder(1));
        findViewById(R.id.lbBackward).setOnClickListener(v -> reorder(2));
        findViewById(R.id.lbBack).setOnClickListener(v -> reorder(3));
        findViewById(R.id.lbEdit).setOnClickListener(v -> openLayers(editor.getSelected()));
        findViewById(R.id.lbDelete).setOnClickListener(v -> {
            Layer sel = editor.getSelected();
            if (sel != null && engine != null) {
                engine.removeLayer(sel);
                editor.select(null);
            }
        });
        chipLive = findViewById(R.id.chipLive);
        chipRec = findViewById(R.id.chipRec);
        chipStats = findViewById(R.id.chipStats);
        banner = findViewById(R.id.banner);
        micMeter = findViewById(R.id.micMeter);
        btnMic = findViewById(R.id.btnMic);
        btnRecord = findViewById(R.id.btnRecord);
        btnLive = findViewById(R.id.btnLive);
        blackout = findViewById(R.id.blackout);
        bottomBar = findViewById(R.id.bottomBar);
        topBar = findViewById(R.id.topBar);

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            topBar.setPadding(bars.left + dp(12), bars.top + dp(12), bars.right + dp(12), dp(12));
            ((android.widget.FrameLayout.LayoutParams) bottomBar.getLayoutParams()).bottomMargin = bars.bottom + dp(20);
            bottomBar.requestLayout();
            return insets;
        });

        preview.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                previewSurface = holder.getSurface();
                if (engine != null && !inBlackout) {
                    engine.attachPreview(previewSurface);
                }
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int w, int h) {
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                if (engine != null) {
                    engine.detachPreview();
                }
                previewSurface = null;
            }
        });

        findViewById(R.id.btnSources).setOnClickListener(v -> showSources());
        findViewById(R.id.btnLayers).setOnClickListener(v -> openLayers(null));
        findViewById(R.id.btnText).setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
            .setTitle("Add to the stream")
            .setItems(new String[]{"Title / watermark text", "Scrolling ticker (marquee)", "Logo / image"},
                (d, which) -> {
                    if (engine == null) {
                        return;
                    }
                    if (which == 0) {
                        layerHost.addTextRequested(false);
                    } else if (which == 1) {
                        layerHost.addTextRequested(true);
                    } else {
                        layerHost.addImageRequested();
                    }
                })
            .show());
        findViewById(R.id.btnFlip).setOnClickListener(v -> flipCamera());
        findViewById(R.id.btnSettings).setOnClickListener(v -> showSettings());
        findViewById(R.id.btnPip).setOnClickListener(v -> enterPip());
        findViewById(R.id.btnKiosk).setOnClickListener(v -> showKioskQuick());
        findViewById(R.id.btnBlackout).setOnClickListener(v -> enterBlackout());
        btnMic.setOnClickListener(v -> toggleMute());
        btnRecord.setOnClickListener(v -> toggleRecord());
        btnLive.setOnClickListener(v -> toggleLive());
        blackout.setOnClickListener(v -> onBlackoutTap());
        findViewById(R.id.kioskExit).setOnClickListener(v -> exitKioskWithPin());

        requestPermissionsIfNeeded();
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, StreamService.class), conn, Context.BIND_AUTO_CREATE);
        startOrientationTracking();
        ui.post(tick);
    }

    @Override
    protected void onStop() {
        ui.removeCallbacks(tick);
        saveScene.run();
        if (orientationListener != null) {
            orientationListener.disable();
        }
        if (bound) {
            if (engine != null) {
                engine.removeListener(this);
                engine.detachPreview();
            }
            unbindService(conn);
            bound = false;
            engine = null;
            service = null;
        }
        super.onStop();
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((StreamService.LocalBinder) binder).getService();
            engine = service.getEngine();
            bound = true;
            engine.addListener(StudioActivity.this);
            editor.bind(engine, new SceneEditorView.Callback() {
                @Override
                public void onLayerSelected(Layer layer) {
                    layerBar.setVisibility(layer != null && !kioskActive && !inPip ? View.VISIBLE : View.GONE);
                }

                @Override
                public void onLayerProperties(Layer layer) {
                    openLayers(layer);
                }

                @Override
                public void onLayerEdited() {
                    scheduleSave();
                }
            });
            onEngineReady();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            engine = null;
            service = null;
            bound = false;
        }
    };

    private void onEngineReady() {
        if (!engine.isStreaming() && !engine.isRecording()) {
            engine.setVideoConfig(prefs.width(), prefs.height(), prefs.fps(), prefs.effectiveBitrate());
        }
        engine.setAdaptiveBitrate(prefs.adaptive());
        engine.onDisplayRotation(contentRotationDegrees());
        if (previewSurface != null && !inBlackout) {
            engine.attachPreview(previewSurface);
        }
        if (engine.getLayers().isEmpty()) {
            int[] req = cameraRequest();
            boolean restored = hasCamera() && SceneStore.restore(this, engine, req[0], req[1],
                prefs.fps(), contentRotationDegrees());
            if (!restored && engine.getSources().isEmpty() && hasCamera()) {
                addDefaultCamera();
            }
        }
        applyLayout();
        refreshButtons();
        if (prefs.kiosk() && !kioskActive) {
            enterKiosk();
        }
        updatePipParams();
        if (prefs.kiosk() && prefs.kioskAutoStream() && !autoStartDone && !engine.isStreaming()
            && hasCamera()) {
            autoStartDone = true;
            ui.postDelayed(this::toggleLive, 1500); // let the camera settle first
        }
        if (prefs.kiosk() && prefs.kioskAutoRecord() && !autoRecordDone && !engine.isRecording()
            && hasCamera()) {
            autoRecordDone = true;
            ui.postDelayed(() -> {
                if (engine != null && !engine.isRecording()) {
                    toggleRecord();
                }
            }, 2000);
        }
    }

    // ------------------------------------------------------------ permissions

    private boolean hasCamera() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissionsIfNeeded() {
        List<String> need = new ArrayList<>();
        String[] want = Build.VERSION.SDK_INT >= 33
            ? new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}
            : new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        for (String p : want) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                need.add(p);
            }
        }
        if (Build.VERSION.SDK_INT <= 28
            && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!need.isEmpty()) {
            ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_PERMS) {
            if (!hasCamera()) {
                showBanner("Camera permission is needed for camera sources. Screen sharing still works.");
            } else if (engine != null && engine.getSources().isEmpty()) {
                addDefaultCamera();
                applyLayout();
            }
        }
    }

    // ------------------------------------------------------------ sources & layout

    private void addDefaultCamera() {
        List<Camera2Source.Info> cams = Camera2Source.listCameras(this);
        if (cams.isEmpty()) {
            showBanner("No camera found on this device");
            return;
        }
        Camera2Source.Info pick = cams.get(0);
        for (Camera2Source.Info c : cams) {
            if (c.facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                pick = c;
                break;
            }
        }
        addCamera(pick);
    }

    private Camera2Source makeCamera(Camera2Source.Info info) {
        int[] req = cameraRequest();
        return new Camera2Source(this, info, req[0], req[1], prefs.fps(), contentRotationDegrees());
    }

    private void addCamera(Camera2Source.Info info) {
        engine.addSource(makeCamera(info));
    }

    /** Switch the main camera to the next one (back, front, other lenses...), closing the old one first. */
    private void flipCamera() {
        if (engine == null || !hasCamera()) {
            return;
        }
        final List<Camera2Source.Info> all = Camera2Source.listCameras(this);
        VideoSource current = null;
        for (VideoSource v : engine.getSources()) {
            if (v instanceof Camera2Source) {
                current = v;
                break;
            }
        }
        if (all.size() < 2 && current != null) {
            showBanner("This device has only one camera");
            return;
        }
        int idx = -1;
        for (int i = 0; i < all.size(); i++) {
            if (current != null && all.get(i).key().equals(current.id)) {
                idx = i;
            }
        }
        // next camera that is not already shown as another source
        Camera2Source.Info next = null;
        for (int step = 1; step <= all.size(); step++) {
            Camera2Source.Info c = all.get((idx + step + all.size()) % all.size());
            if (engine.findSource(c.key()) == null) {
                next = c;
                break;
            }
        }
        if (next == null) {
            return;
        }
        final Camera2Source.Info target = next;
        final VideoSource old = current;
        new Thread(() -> {
            if (old != null) {
                engine.replaceSource(old, makeCamera(target));
            } else {
                engine.addSource(makeCamera(target));
            }
            ui.post(() -> showBanner("Camera: " + target.label));
        }, "flip-camera").start();
    }

    /** Camera capture size to ask for: the output size, landscape-oriented (the camera picks the nearest it has). */
    private int[] cameraRequest() {
        return new int[]{Math.max(prefs.width(), prefs.height()), Math.min(prefs.width(), prefs.height())};
    }

    private void showSources() {
        if (engine == null) {
            return;
        }
        final List<String> ids = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        final List<Camera2Source.Info> infos = new ArrayList<>();
        if (hasCamera()) {
            for (Camera2Source.Info c : Camera2Source.listCameras(this)) {
                ids.add(c.key());
                labels.add(c.label);
                infos.add(c);
            }
        }
        ids.add("screen");
        labels.add("Screen share");
        infos.add(null);
        final boolean[] checked = new boolean[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            checked[i] = engine.findSource(ids.get(i)) != null;
        }
        final boolean[] original = checked.clone();
        new MaterialAlertDialogBuilder(this)
            .setTitle("Video sources")
            .setMultiChoiceItems(labels.toArray(new String[0]), checked, (d, which, on) -> checked[which] = on)
            .setPositiveButton("Apply", (d, w) -> {
                int count = 0;
                List<Camera2Source.Info> wantCams = new ArrayList<>();
                for (int i = 0; i < checked.length; i++) {
                    if (checked[i]) {
                        count++;
                        if (infos.get(i) != null) {
                            wantCams.add(infos.get(i));
                        }
                    }
                }
                if (count > 4) {
                    showBanner("At most 4 sources can be shown at once");
                    return;
                }
                if (!Camera2Source.canRunTogether(this, wantCams)) {
                    showBanner("This phone cannot run those cameras at the same time. "
                        + "Pick one camera (use the flip button to switch), or add Screen share as the second source.");
                    return;
                }
                for (int i = 0; i < ids.size(); i++) {
                    if (checked[i] == original[i]) {
                        continue;
                    }
                    String id = ids.get(i);
                    if (!checked[i]) {
                        VideoSource s = engine.findSource(id);
                        if (s != null) {
                            engine.removeSource(s);
                        }
                    } else if (id.equals("screen")) {
                        requestScreenCapture();
                    } else {
                        addCamera(infos.get(i));
                    }
                }
                ui.postDelayed(this::applyLayout, 150);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void requestScreenCapture() {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_IMAGE) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                addImageFrom(data.getData());
            }
            return;
        }
        if (req == REQ_PROJECTION) {
            if (result == RESULT_OK && data != null && service != null) {
                if (!service.startScreenShare(result, data)) {
                    showBanner("Could not start screen sharing");
                }
                ui.postDelayed(this::applyLayout, 300);
            } else {
                showBanner("Screen sharing was cancelled");
            }
        }
    }

    /** Scene changed: refresh the editing overlay. */
    private void applyLayout() {
        if (editor != null) {
            editor.setOutputSize(engine != null ? engine.getOutputWidth() : 1280,
                engine != null ? engine.getOutputHeight() : 720);
            editor.invalidate();
        }
    }

    /** 0 = to front, 1 = forward, 2 = backward, 3 = to back. */
    private void reorder(int action) {
        Layer sel = editor.getSelected();
        if (sel == null || engine == null) {
            return;
        }
        switch (action) {
            case 0:
                engine.bringToFront(sel);
                break;
            case 1:
                engine.moveLayer(sel, 1);
                break;
            case 2:
                engine.moveLayer(sel, -1);
                break;
            default:
                engine.sendToBack(sel);
        }
        List<Layer> all = engine.getLayers();
        showBanner(sel.name + ": layer " + (all.indexOf(sel) + 1) + " of " + all.size()
            + (all.indexOf(sel) == all.size() - 1 ? " (front)" : all.indexOf(sel) == 0 ? " (back)" : ""));
    }

    // ---- scene editor / layers panel ----

    private void openLayers(Layer select) {
        if (engine == null) {
            return;
        }
        new LayerPanel(this, engine, layerHost).show(select != null ? select : editor.getSelected());
    }

    private final LayerPanel.Host layerHost = new LayerPanel.Host() {
        @Override
        public void addSourceRequested() {
            showSources();
        }

        @Override
        public void addTextRequested(boolean ticker) {
            TextLayer t;
            if (ticker) {
                t = new TextLayer("Welcome to the stream  \u2022  Follow and share  \u2022  Thanks for watching");
                t.marquee = true;
                t.sizeFrac = 0.05f;
                t.bgColor = 0xCC000000;
                t.name = "Ticker";
                t.x = 0f;
                t.w = 1f;
            } else {
                t = new TextLayer("My Stream");
                t.sizeFrac = 0.08f;
                t.name = "My Stream";
            }
            engine.addLayer(t);
            if (ticker) {
                t.y = 1f - t.h - 0.03f;
            }
            engine.markManualLayout();
            editor.select(t);
            openLayers(t);
        }

        @Override
        public void addImageRequested() {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            startActivityForResult(i, REQ_IMAGE);
        }

        @Override
        public void layerSelected(Layer l) {
            editor.select(l);
        }

        @Override
        public void panelClosed() {
            scheduleSave();
        }
    };

    private void addImageFrom(Uri uri) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                android.graphics.BitmapFactory.decodeStream(in, null, o);
            }
            o.inJustDecodeBounds = false;
            int longest = Math.max(o.outWidth, o.outHeight);
            o.inSampleSize = Math.max(1, longest / 1024);
            android.graphics.Bitmap bmp;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                bmp = android.graphics.BitmapFactory.decodeStream(in, null, o);
            }
            if (bmp == null) {
                showBanner("Could not read that image");
                return;
            }
            ImageLayer l = new ImageLayer(bmp, "Logo");
            l.path = SceneStore.persistImage(this, bmp);
            engine.addLayer(l);
            engine.markManualLayout();
            editor.select(l);
            openLayers(l);
        } catch (java.io.IOException | RuntimeException e) {
            showBanner("Could not load image: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ actions

    private void toggleLive() {
        if (engine == null) {
            return;
        }
        if (engine.isStreaming()) {
            engine.stopStream();
            return;
        }
        String url = prefs.url().trim();
        if (!(url.startsWith("rtmp://") || url.startsWith("rtmps://"))) {
            showBanner("Enter your rtmp:// or rtmps:// stream URL first");
            showSettings();
            return;
        }
        if (!hasCamera() && engine.getSources().isEmpty()) {
            showBanner("Add a video source first");
            return;
        }
        service.promote(engine.findSource("screen") != null);
        engine.setVideoConfig(prefs.width(), prefs.height(), prefs.fps(), prefs.effectiveBitrate());
        engine.startStream(url);
    }

    private void toggleRecord() {
        if (engine == null) {
            return;
        }
        if (engine.isRecording()) {
            engine.stopRecord();
            return;
        }
        if (engine.getSources().isEmpty()) {
            showBanner("Add a video source first");
            return;
        }
        service.promote(engine.findSource("screen") != null);
        if (!engine.isStreaming()) {
            engine.setVideoConfig(prefs.width(), prefs.height(), prefs.fps(), prefs.effectiveBitrate());
        }
        File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir != null) {
            dir.mkdirs();
        }
        String name = "rec_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        if (!engine.startRecord(new File(dir, name))) {
            showBanner("Could not start recording");
        }
    }

    private void toggleMute() {
        if (engine == null) {
            return;
        }
        muted = !muted;
        engine.setMuted(muted);
        btnMic.setImageResource(muted ? R.drawable.ic_mic_off : R.drawable.ic_mic);
    }

    // ------------------------------------------------------------ settings

    private void showSettings() {
        View v = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        final EditText url = v.findViewById(R.id.setUrl);
        final Spinner quality = v.findViewById(R.id.setQuality);
        final Spinner fps = v.findViewById(R.id.setFps);
        final Spinner orient = v.findViewById(R.id.setOrientation);
        final EditText bitrate = v.findViewById(R.id.setBitrate);
        final MaterialSwitch adaptive = v.findViewById(R.id.setAdaptive);

        final int[] fVals = {24, 30, 60};
        final int[] allTiers = {480, 720, 1080, 1440, 2160, 4320, 8640};
        final String[] allNames = {"480p", "720p HD", "1080p Full HD", "1440p QHD", "4K UHD (2160p)",
            "8K (4320p)", "16K (8640p)"};
        final List<Integer> qList = new ArrayList<>();
        List<String> qNames = new ArrayList<>();
        int checkFps = prefs.fps();
        for (int i = 0; i < allTiers.length; i++) {
            int shortSide = allTiers[i];
            int longSide = Math.round(shortSide * 16f / 9f) & ~1;
            boolean ok = EncoderCaps.supports(MediaEncoders.VCODEC, longSide, shortSide, checkFps);
            // Every tier is offered; the ones this phone's H.264 encoder cannot do are labelled,
            // and trying one reports the encoder's error instead of silently disappearing.
            qList.add(shortSide);
            qNames.add(allNames[i] + (ok ? "" : "  (not supported by this phone)"));
        }
        int[] h264 = EncoderCaps.maxSize(MediaEncoders.VCODEC);
        int[] hevc = EncoderCaps.maxSize("video/hevc");
        ((TextView) v.findViewById(R.id.capNote)).setText(String.format(Locale.US,
            "This phone's encoder limit: H.264 up to %dx%d, HEVC up to %dx%d. "
                + "Streaming and recording use H.264.", h264[0], h264[1], hevc[0], hevc[1]));
        final int[] qVals = new int[qList.size()];
        for (int i = 0; i < qVals.length; i++) {
            qVals[i] = qList.get(i);
        }
        quality.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, qNames));
        fps.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"24 fps", "30 fps", "60 fps"}));
        orient.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Landscape", "Portrait"}));

        url.setText(prefs.url());
        for (int i = 0; i < qVals.length; i++) {
            if (qVals[i] == prefs.quality()) {
                quality.setSelection(i);
            }
        }
        for (int i = 0; i < fVals.length; i++) {
            if (fVals[i] == prefs.fps()) {
                fps.setSelection(i);
            }
        }
        orient.setSelection(prefs.portrait() ? 1 : 0);
        bitrate.setText(String.valueOf(prefs.bitrateKbps()));
        adaptive.setChecked(prefs.adaptive());
        final MaterialSwitch kioskSw = v.findViewById(R.id.setKiosk);
        final EditText kioskPin = v.findViewById(R.id.setKioskPin);
        final MaterialSwitch kioskAuto = v.findViewById(R.id.setKioskAuto);
        final MaterialSwitch kioskBoot = v.findViewById(R.id.setKioskBoot);
        final MaterialSwitch kioskRec = v.findViewById(R.id.setKioskRec);
        kioskRec.setChecked(prefs.kioskAutoRecord());
        final MaterialSwitch kioskDark = v.findViewById(R.id.setKioskDark);
        kioskDark.setChecked(prefs.kioskDark());
        v.findViewById(R.id.btnKioskHelp).setOnClickListener(x -> showKioskExitHelp());
        final MaterialSwitch pipSw = v.findViewById(R.id.setPip);
        v.findViewById(R.id.btnProfiles).setOnClickListener(x -> showProfiles());
        pipSw.setChecked(prefs.autoPip());
        kioskSw.setChecked(prefs.kiosk());
        kioskPin.setText(prefs.kioskPin());
        kioskAuto.setChecked(prefs.kioskAutoStream());
        kioskBoot.setChecked(prefs.kioskOnBoot());

        new MaterialAlertDialogBuilder(this)
            .setTitle("Settings")
            .setView(v)
            .setPositiveButton("Save", (d, w) -> {
                prefs.url(url.getText().toString().trim());
                prefs.quality(qVals[quality.getSelectedItemPosition()]);
                prefs.fps(fVals[fps.getSelectedItemPosition()]);
                boolean portrait = orient.getSelectedItemPosition() == 1;
                boolean orientationChanged = portrait != prefs.portrait();
                prefs.portrait(portrait);
                try {
                    prefs.bitrateKbps(Integer.parseInt(bitrate.getText().toString().trim()));
                } catch (NumberFormatException e) {
                    prefs.bitrateKbps(0);
                }
                prefs.adaptive(adaptive.isChecked());
                String pin = kioskPin.getText().toString().trim();
                boolean wantKiosk = kioskSw.isChecked();
                if (wantKiosk && pin.length() < 4) {
                    wantKiosk = false;
                    showBanner("Kiosk mode needs an exit PIN of at least 4 digits");
                }
                prefs.kioskPin(pin);
                prefs.kioskAutoStream(kioskAuto.isChecked());
                prefs.kioskAutoRecord(kioskRec.isChecked());
                prefs.kioskDark(kioskDark.isChecked());
                prefs.autoPip(pipSw.isChecked());
                updatePipParams();
                prefs.kioskOnBoot(kioskBoot.isChecked());
                setHomeAliasEnabled(kioskBoot.isChecked());
                prefs.kiosk(wantKiosk);
                if (engine != null) {
                    engine.setAdaptiveBitrate(prefs.adaptive());
                    if (engine.isStreaming() || engine.isRecording()) {
                        showBanner("Video settings apply the next time you start");
                    } else {
                        engine.setVideoConfig(prefs.width(), prefs.height(), prefs.fps(), prefs.effectiveBitrate());
                    }
                }
                if (orientationChanged) {
                    applyOrientationLock();
                }
                if (prefs.kiosk()) {
                    enterKiosk();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ------------------------------------------------------------ profiles

    private ProfileStore profiles;

    private void showProfiles() {
        if (engine == null) {
            return;
        }
        if (profiles == null) {
            profiles = new ProfileStore(this);
        }
        final List<String> names = profiles.names();
        List<String> items = new ArrayList<>();
        for (String n : names) {
            items.add(n.equals(profiles.active()) ? n + "   (active)" : n);
        }
        items.add("+  Save current settings and scene as a new profile");
        items.add("\u2193  Import a profile from the clipboard");
        new MaterialAlertDialogBuilder(this)
            .setTitle("Profiles")
            .setItems(items.toArray(new String[0]), (d, which) -> {
                if (which < names.size()) {
                    profileActions(names.get(which));
                } else if (which == names.size()) {
                    askProfileName(null);
                } else {
                    importProfile();
                }
            })
            .setNegativeButton("Close", null)
            .show();
    }

    private void askProfileName(String existing) {
        final EditText name = new EditText(this);
        name.setHint("Profile name");
        name.setText(existing != null ? existing : "");
        new MaterialAlertDialogBuilder(this)
            .setTitle(existing == null ? "Save profile" : "Overwrite profile")
            .setView(name)
            .setPositiveButton("Save", (d, w) -> {
                String n = name.getText().toString().trim();
                if (n.isEmpty()) {
                    showBanner("Give the profile a name");
                    return;
                }
                SceneStore.save(this, engine);
                showBanner(profiles.save(n, prefs, engine) ? "Saved profile \"" + n + "\"" : "Could not save profile");
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void profileActions(final String name) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(name)
            .setItems(new String[]{"Load", "Overwrite with current", "Export / share", "Delete"}, (d, which) -> {
                switch (which) {
                    case 0:
                        loadProfile(name);
                        break;
                    case 1:
                        askProfileName(name);
                        break;
                    case 2: {
                        String text = profiles.export(name);
                        if (text != null) {
                            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, text);
                            startActivity(Intent.createChooser(send, "Share profile (stream URL not included)"));
                        }
                        break;
                    }
                    default:
                        profiles.delete(name);
                        showBanner("Deleted \"" + name + "\"");
                }
            })
            .show();
    }

    private void loadProfile(String name) {
        if (engine.isStreaming() || engine.isRecording()) {
            showBanner("Stop streaming and recording before loading a profile");
            return;
        }
        String scene = profiles.load(name, prefs);
        applyOrientationLock();
        engine.setAdaptiveBitrate(prefs.adaptive());
        engine.setVideoConfig(prefs.width(), prefs.height(), prefs.fps(), prefs.effectiveBitrate());
        setHomeAliasEnabled(prefs.kioskOnBoot());
        final int[] req = cameraRequest();
        final String sceneJson = scene;
        // rebuild off the UI thread: closing cameras blocks briefly
        new Thread(() -> {
            engine.clearScene();
            boolean ok = SceneStore.restoreFrom(this, sceneJson, engine, req[0], req[1], prefs.fps(),
                contentRotationDegrees());
            ui.post(() -> {
                if (!ok && engine.getSources().isEmpty() && hasCamera()) {
                    addDefaultCamera();
                }
                updatePipParams();
                showBanner("Loaded profile \"" + name + "\"");
            });
        }, "load-profile").start();
    }

    private void importProfile() {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        CharSequence text = null;
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
            text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        }
        String imported = text == null ? null : profiles.importText(text.toString());
        showBanner(imported != null ? "Imported \"" + imported + "\"" : "Clipboard does not contain a StreamMaster profile");
    }

    // ------------------------------------------------------------ picture-in-picture

    private boolean inPip;

    private boolean canPip() {
        return Build.VERSION.SDK_INT >= 26 && !kioskActive && !inBlackout && engine != null
            && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    @androidx.annotation.RequiresApi(26)
    private android.app.PictureInPictureParams pipParams() {
        int w = engine != null ? engine.getOutputWidth() : 16;
        int h = engine != null ? engine.getOutputHeight() : 9;
        // the system only accepts aspect ratios between 1:2.39 and 2.39:1
        float a = Math.max(1f / 2.39f, Math.min(2.39f, (float) w / h));
        android.app.PictureInPictureParams.Builder b = new android.app.PictureInPictureParams.Builder()
            .setAspectRatio(new android.util.Rational(Math.round(a * 1000), 1000));
        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(prefs.autoPip() && canPip()); // smooth gesture-nav transition
        }
        return b.build();
    }

    /** Keep the system's auto-enter flag and aspect ratio in step with the current state. */
    private void updatePipParams() {
        if (canPip() || inPip) {
            try {
                setPictureInPictureParams(pipParams());
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void enterPip() {
        if (!canPip()) {
            showBanner("Picture-in-picture is not available right now");
            return;
        }
        try {
            enterPictureInPictureMode(pipParams());
        } catch (RuntimeException e) {
            showBanner("Picture-in-picture failed: " + e.getMessage());
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Android 12+ enters through setAutoEnterEnabled; older versions need this hook
        if (Build.VERSION.SDK_INT < 31 && prefs.autoPip() && canPip()) {
            enterPip();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPip, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPip, newConfig);
        inPip = isInPip;
        int vis = isInPip ? View.GONE : View.VISIBLE;
        topBar.setVisibility(vis);
        bottomBar.setVisibility(kioskActive ? View.GONE : vis);
        editor.setEditable(!isInPip && !kioskActive);
        if (isInPip) {
            layerBar.setVisibility(View.GONE);
        }
        banner.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------ kiosk mode

    private boolean kioskActive;
    private boolean autoStartDone;
    private boolean autoRecordDone;

    /**
     * Lock the device to this app: immersive full screen, controls hidden, Back/Home
     * blocked through lock-task (screen pinning) and a hidden PIN-protected exit.
     * With device-owner status (see AdminReceiver) the pinning is silent and complete.
     */
    private void enterKiosk() {
        kioskActive = true;
        topBar.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        editor.setEditable(false);
        layerBar.setVisibility(View.GONE);
        findViewById(R.id.kioskExit).setVisibility(View.VISIBLE);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.hide(WindowInsetsCompat.Type.systemBars());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        showBanner("Kiosk on. To exit, tap the lock button (top-left) and enter your PIN.");
        if (prefs.kioskDark()) {
            ui.postDelayed(() -> {
                if (kioskActive && !inBlackout) {
                    enterBlackout();
                }
            }, 5000);   // give the reminder time to be read
        }
        try {
            android.app.admin.DevicePolicyManager dpm =
                (android.app.admin.DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(this, AdminReceiver.class);
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                dpm.setLockTaskPackages(admin, new String[]{getPackageName()});
            }
            startLockTask();
        } catch (RuntimeException e) {
            showBanner("Screen pinning unavailable: " + e.getMessage());
        }
    }

    /** One-step kiosk: choose what runs automatically, set the exit PIN, go. */
    private void showKioskQuick() {
        if (engine == null) {
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);
        final EditText pin = new EditText(this);
        pin.setHint("Exit PIN (4+ digits)");
        pin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setText(prefs.kioskPin());
        box.addView(pin);
        final MaterialSwitch rec = new MaterialSwitch(this);
        rec.setText("Record");
        rec.setChecked(prefs.kioskAutoRecord());
        final MaterialSwitch live = new MaterialSwitch(this);
        live.setText("Stream live");
        live.setChecked(prefs.kioskAutoStream());
        final MaterialSwitch dark = new MaterialSwitch(this);
        dark.setText("Darken the screen");
        dark.setChecked(prefs.kioskDark());
        box.addView(rec);
        box.addView(live);
        box.addView(dark);
        new MaterialAlertDialogBuilder(this)
            .setTitle("Start kiosk mode")
            .setMessage("Locks the screen to this app. To leave: tap the lock button in the top-left corner and enter the PIN.")
            .setNeutralButton("All ways to exit", (d, w) -> showKioskExitHelp())
            .setView(box)
            .setPositiveButton("Start", (d, w) -> {
                String p = pin.getText().toString().trim();
                if (p.length() < 4) {
                    showBanner("Choose an exit PIN of at least 4 digits");
                    return;
                }
                if (live.isChecked() && !(prefs.url().startsWith("rtmp://") || prefs.url().startsWith("rtmps://"))) {
                    showBanner("Set your stream URL in Settings first, or turn off Stream live");
                    return;
                }
                prefs.kioskPin(p);
                prefs.kioskAutoRecord(rec.isChecked());
                prefs.kioskAutoStream(live.isChecked());
                prefs.kioskDark(dark.isChecked());
                prefs.kiosk(true);
                enterKiosk();
                // start what was asked for right away (and again automatically on every relaunch)
                autoRecordDone = autoStartDone = true;
                if (rec.isChecked() && !engine.isRecording()) {
                    toggleRecord();
                }
                if (live.isChecked() && !engine.isStreaming()) {
                    toggleLive();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void exitKioskWithPin() {
        final EditText pin = new EditText(this);
        pin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setHint("PIN");
        new MaterialAlertDialogBuilder(this)
            .setTitle("Exit kiosk mode")
            .setView(pin)
            .setPositiveButton("Exit", (d, w) -> {
                if (pin.getText().toString().equals(prefs.kioskPin())) {
                    exitKiosk();
                } else {
                    showBanner("Wrong PIN");
                }
            })
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Forgot PIN?", (d, w) -> showKioskExitHelp())
            .show();
    }

    /** Every way to leave kiosk mode, including when the PIN is lost. */
    private void showKioskExitHelp() {
        new MaterialAlertDialogBuilder(this)
            .setTitle("Ways to exit kiosk mode")
            .setMessage(
                "1. In the app (normal way)\n"
                + "Tap the lock button in the top-left corner, then enter your exit PIN. "
                + "While the screen is darkened the button is faint but still works; triple-tap first if you want to see it.\n\n"
                + "2. Android screen pinning (when the device is not locked down by an admin)\n"
                + "Hold Back + Overview together (3-button navigation), or swipe up and hold (gesture navigation). "
                + "Android may ask for your phone's lock-screen PIN. This releases the pin, then close or switch the app.\n\n"
                + "3. From a computer (USB or wireless debugging)\n"
                + "adb shell am force-stop net.ossrs.yasea.demo\n"
                + "Or reset kiosk and the PIN: adb shell pm clear net.ossrs.yasea.demo (also erases profiles and settings).\n\n"
                + "4. If StreamMaster is the device owner (set up with dpm)\n"
                + "Remove it with: adb shell dpm remove-active-admin net.ossrs.yasea.demo/.studio.AdminReceiver\n\n"
                + "5. Last resort: restart the phone, then uninstall or clear the app's data in Android Settings. "
                + "If 'Start on device boot' is on, the app returns to kiosk after the restart.")
            .setPositiveButton("OK", null)
            .show();
    }

    private void exitKiosk() {
        kioskActive = false;
        ui.removeCallbacks(redarken);
        if (inBlackout) {
            exitBlackout();
        }
        prefs.kiosk(false);
        try {
            stopLockTask();
        } catch (RuntimeException ignored) {
        }
        topBar.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.VISIBLE);
        editor.setEditable(true);
        findViewById(R.id.kioskExit).setVisibility(View.GONE);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
            .show(WindowInsetsCompat.Type.systemBars());
    }

    private void setHomeAliasEnabled(boolean on) {
        getPackageManager().setComponentEnabledSetting(
            new ComponentName(this, "net.ossrs.yasea.demo.studio.KioskHome"),
            on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP);
    }

    // ------------------------------------------------------------ black screen mode

    private void enterBlackout() {
        if (engine == null) {
            return;
        }
        inBlackout = true;
        blackoutTaps = 0;
        engine.detachPreview();                       // stop drawing the preview: saves GPU + battery
        blackout.setVisibility(View.VISIBLE);
        findViewById(R.id.kioskExit).setAlpha(0.18f);   // still tappable, barely visible
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0f;                     // BRIGHTNESS_OVERRIDE_OFF
        getWindow().setAttributes(lp);
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.hide(WindowInsetsCompat.Type.systemBars());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private final Runnable redarken = () -> {
        if (kioskActive && prefs.kioskDark() && !inBlackout) {
            enterBlackout();
        }
    };

    private void onBlackoutTap() {
        long now = SystemClock.uptimeMillis();
        blackoutTaps = (now - lastTapMs < 800) ? blackoutTaps + 1 : 1;
        lastTapMs = now;
        if (blackoutTaps >= 3) {
            exitBlackout();
        }
    }

    private void exitBlackout() {
        inBlackout = false;
        blackout.setVisibility(View.GONE);
        findViewById(R.id.kioskExit).setAlpha(0.75f);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
        if (!kioskActive) {
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .show(WindowInsetsCompat.Type.systemBars());
        } else if (prefs.kioskDark()) {
            // peek only: dim again after 30 s without a tap
            ui.removeCallbacks(redarken);
            ui.postDelayed(redarken, 30000);
        }
        if (engine != null && previewSurface != null) {
            engine.attachPreview(previewSurface);
        }
    }

    @Override
    public void onBackPressed() {
        if (inBlackout || kioskActive) {
            return; // only the triple tap / PIN can leave these modes
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------ orientation

    private void applyOrientationLock() {
        setRequestedOrientation(prefs.portrait()
            ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    /**
     * Rotation used to orient the camera content. Follows how the phone is physically
     * held (accelerometer), so the stream is upright even when the screen is locked to
     * one orientation; falls back to the display rotation until a reading arrives.
     */
    private int contentRotationDegrees() {
        return physicalRotation >= 0 ? physicalRotation : displayRotationDegrees();
    }

    private void startOrientationTracking() {
        if (orientationListener == null) {
            orientationListener = new android.view.OrientationEventListener(this) {
                @Override
                public void onOrientationChanged(int angle) {
                    if (angle == ORIENTATION_UNKNOWN) {
                        return; // flat on a table: keep the last known orientation
                    }
                    // listener angle is the device's clockwise rotation; Surface rotation is the opposite sense
                    int d = ((360 - ((angle + 45) / 90) * 90) % 360);
                    if (d != physicalRotation) {
                        physicalRotation = d;
                        android.util.Log.i("StudioActivity", "physical rotation=" + d + " display=" + displayRotationDegrees());
                        if (engine != null) {
                            engine.onDisplayRotation(d);
                        }
                    }
                }
            };
        }
        if (orientationListener.canDetectOrientation()) {
            orientationListener.enable();
        }
    }

    private int displayRotationDegrees() {
        int r = getWindowManager().getDefaultDisplay().getRotation();
        switch (r) {
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            default:
                return 0;
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (engine != null) {
            engine.onDisplayRotation(contentRotationDegrees());
        }
    }

    // ------------------------------------------------------------ status UI

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshStats();
            ui.postDelayed(this, 500);
        }
    };

    private void refreshStats() {
        if (engine == null) {
            return;
        }
        StreamEngine.Stats s = engine.getStats();
        chipStats.setText(String.format(Locale.US, "%dx%d  %d fps  %.1f Mbps%s",
            engine.getOutputWidth(), engine.getOutputHeight(), s.renderFps,
            s.videoBitrateKbps / 1000f,
            engine.isStreaming() && s.cachedFrames > 2 ? "  ⚠ " + s.cachedFrames + " queued" : ""));
        if (engine.isStreaming()) {
            chipLive.setVisibility(View.VISIBLE);
            String st = engine.getStreamState() == StreamEngine.StreamState.LIVE
                ? "LIVE " : engine.getStreamState() == StreamEngine.StreamState.IDLE ? "" : "CONNECTING ";
            chipLive.setText(st + clock(s.streamElapsedMs));
        } else {
            chipLive.setVisibility(View.GONE);
        }
        if (engine.isRecording()) {
            chipRec.setVisibility(View.VISIBLE);
            chipRec.setText("● REC " + clock(s.recordElapsedMs) + "  " + mb(s.recordBytes));
        } else {
            chipRec.setVisibility(View.GONE);
        }
        micMeter.setProgress(s.micMuted ? 0 : Math.min(100, (int) (s.micLevel * 160)));
    }

    private void refreshButtons() {
        if (engine == null) {
            return;
        }
        btnLive.setText(engine.isStreaming() ? "STOP" : "GO LIVE");
        btnRecord.setImageResource(engine.isRecording() ? R.drawable.ic_record_stop : R.drawable.ic_record);
    }

    private static String clock(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    private static String mb(long bytes) {
        return String.format(Locale.US, "%.1f MB", bytes / 1048576f);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void showBanner(String msg) {
        banner.setText(msg);
        banner.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideBanner);
        ui.postDelayed(hideBanner, 4000);
    }

    private final Runnable hideBanner = () -> banner.setVisibility(View.GONE);

    // ------------------------------------------------------------ engine callbacks

    @Override
    public void onStreamState(StreamEngine.StreamState state, String message) {
        refreshButtons();
        updatePipParams();
        if (message != null && state != StreamEngine.StreamState.LIVE) {
            showBanner(message);
        }
    }

    @Override
    public void onRecordState(boolean recording, String path, String message) {
        refreshButtons();
        if (message != null) {
            showBanner(message);
        } else if (!recording) {
            showBanner("Recording saved to Movies/StreamMaster");
        }
    }

    @Override
    public void onEngineMessage(String message) {
        showBanner(message);
    }

    @Override
    public void onSourcesChanged() {
        applyLayout();
        scheduleSave();
    }
}
