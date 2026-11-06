package net.ossrs.yasea.engine;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.view.Surface;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A live video input drawn into one tile of the composite. Subclasses (camera,
 * screen, USB) feed {@link #getSurface()}; the GL thread latches new frames with
 * {@link #latchFrame()} and the compositor samples {@link #textureId}.
 */
public abstract class VideoSource {

    /** Listener for source lifecycle problems (camera lost, permission revoked...). */
    public interface Listener {
        void onSourceError(VideoSource source, String message);

        /** Non-fatal notice (for example the camera cannot reach the requested resolution). */
        void onSourceInfo(VideoSource source, String message);
    }

    public final String id;
    protected volatile Listener listener;

    protected int textureId;
    protected SurfaceTexture surfaceTexture;
    protected Surface surface;
    private final AtomicBoolean frameAvailable = new AtomicBoolean(false);
    private final float[] stMatrix = new float[16];
    private volatile boolean hasFrame = false;

    /** Size of the raw buffer the source produces (before rotation). */
    protected volatile int width = 1280;
    protected volatile int height = 720;
    /**
     * True when the SurfaceTexture transform already turns the raw buffer a quarter turn
     * (camera sensors mounted at 90/270 degrees): the upright base size is then height x width.
     */
    protected volatile boolean baseSwapped = false;
    /** Extra clockwise degrees (display/physical rotation) applied on top of the base image. */
    protected volatile int rotationDegrees = 0;
    /** Mirror horizontally after rotation (selfie view). */
    protected volatile boolean mirror = false;
    /** Fit the whole image inside the tile (letterbox) instead of filling it (crop). */
    protected volatile boolean contain = false;

    protected VideoSource(String id) {
        this.id = id;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** GL thread. Creates the OES texture + SurfaceTexture. */
    final void initGl() {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        textureId = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        surfaceTexture = new SurfaceTexture(textureId);
        surfaceTexture.setDefaultBufferSize(width, height);
        surfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture st) {
                frameAvailable.set(true);
            }
        });
        surface = new Surface(surfaceTexture);
    }

    /** GL thread. Latch the newest frame if one arrived. */
    final boolean latchFrame() {
        if (surfaceTexture != null && frameAvailable.getAndSet(false)) {
            try {
                surfaceTexture.updateTexImage();
                surfaceTexture.getTransformMatrix(stMatrix);
                hasFrame = true;
                return true;
            } catch (RuntimeException e) {
                // surface abandoned while the source was being torn down
                return false;
            }
        }
        return false;
    }

    final boolean hasFrame() {
        return hasFrame;
    }

    final float[] transform() {
        return stMatrix;
    }

    public Surface getSurface() {
        return surface;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /** Size of the image after the SurfaceTexture transform, before {@link #getRotationDegrees()}. */
    public int getBaseWidth() {
        return baseSwapped ? height : width;
    }

    public int getBaseHeight() {
        return baseSwapped ? width : height;
    }

    public int getRotationDegrees() {
        return rotationDegrees;
    }

    public boolean isContain() {
        return contain;
    }

    public boolean isMirrored() {
        return mirror;
    }

    public abstract String getLabel();

    /** Called once the GL objects exist and {@link #getSurface()} is valid. */
    public abstract void start();

    /** Release hardware (camera, projection). Safe to call more than once. */
    public abstract void stop();

    /** Display rotation changed (0/90/180/270); sources that care re-derive rotation. */
    public void onDisplayRotation(int degrees) {
    }

    /** GL thread. */
    final void releaseGl() {
        if (surface != null) {
            surface.release();
            surface = null;
        }
        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
            textureId = 0;
        }
        hasFrame = false;
    }
}
