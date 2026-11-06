package net.ossrs.yasea.engine;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

/** Device screen (MediaProjection) as a compositor source. */
public final class ScreenSource extends VideoSource {

    private final Context context;
    private final MediaProjection projection;
    private final MediaProjection.Callback callback;
    private VirtualDisplay virtualDisplay;
    private int dpi;
    private int lastRotation = -1;

    public ScreenSource(Context ctx, MediaProjection projection) {
        super("screen");
        this.context = ctx.getApplicationContext();
        this.projection = projection;
        this.contain = true; // never crop the user's screen
        this.callback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Listener l = listener;
                if (l != null) {
                    l.onSourceError(ScreenSource.this, "Screen sharing was stopped");
                }
            }
        };
    }

    @Override
    public String getLabel() {
        return "Screen";
    }

    private int[] captureSize() {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        Display d = wm.getDefaultDisplay();
        d.getRealMetrics(dm);
        dpi = dm.densityDpi;
        lastRotation = d.getRotation();
        // Scale so the long side is <= 3840, even dimensions (encoder friendly).
        float scale = Math.min(1f, 3840f / Math.max(dm.widthPixels, dm.heightPixels));
        int w = Math.max(2, Math.round(dm.widthPixels * scale) & ~1);
        int h = Math.max(2, Math.round(dm.heightPixels * scale) & ~1);
        return new int[]{w, h};
    }

    @Override
    public void start() {
        int[] size = captureSize();
        width = size[0];
        height = size[1];
        surfaceTexture.setDefaultBufferSize(width, height);
        try {
            // Android 14 requires a callback before creating the virtual display.
            projection.registerCallback(callback, null);
            virtualDisplay = projection.createVirtualDisplay("StreamMasterScreen",
                width, height, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null);
        } catch (RuntimeException e) {
            Listener l = listener;
            if (l != null) {
                l.onSourceError(this, "Screen capture failed: " + e.getMessage());
            }
        }
    }

    @Override
    public void onDisplayRotation(int degrees) {
        if (virtualDisplay == null || surfaceTexture == null) {
            return;
        }
        int[] size = captureSize();
        if (size[0] != width || size[1] != height) {
            width = size[0];
            height = size[1];
            surfaceTexture.setDefaultBufferSize(width, height);
            virtualDisplay.resize(width, height, dpi);
        }
    }

    @Override
    public void stop() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        try {
            projection.unregisterCallback(callback);
            projection.stop();
        } catch (RuntimeException ignored) {
        }
    }
}
