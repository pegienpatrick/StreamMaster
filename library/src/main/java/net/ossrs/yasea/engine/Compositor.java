package net.ossrs.yasea.engine;

import android.opengl.EGL14;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * GPU compositor. Latches the newest frame of every {@link VideoSource}, draws
 * them into the tiles of the current {@link LayoutMode}, and presents the result to
 * the encoder's input surface (zero CPU copies) and, optionally, a preview surface.
 * Runs at a fixed frame rate so the stream is steady even for static content.
 */
public final class Compositor {
    private static final String TAG = "Compositor";

    public interface ErrorListener {
        void onCompositorError(Throwable t);
    }

    private static final String VERTEX =
        "attribute vec4 aPos;\n" +
        "attribute vec2 aTex;\n" +
        "uniform mat4 uTex;\n" +
        "varying vec2 vTex;\n" +
        "void main(){ gl_Position = aPos; vTex = (uTex * vec4(aTex,0.0,1.0)).xy; }\n";
    private static final String FRAGMENT_OES =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "varying vec2 vTex;\n" +
        "uniform samplerExternalOES uSampler;\n" +
        "uniform vec4 uColor;\n" +
        "void main(){ gl_FragColor = texture2D(uSampler, vTex) * uColor; }\n";
    private static final String FRAGMENT_2D =
        "precision mediump float;\n" +
        "varying vec2 vTex;\n" +
        "uniform sampler2D uSampler;\n" +
        "uniform vec4 uColor;\n" +
        "void main(){ gl_FragColor = texture2D(uSampler, vTex) * uColor; }\n";

    private static final float[] QUAD = {
        -1f, -1f, 0f, 0f,
         1f, -1f, 1f, 0f,
        -1f,  1f, 0f, 1f,
         1f,  1f, 1f, 1f,
    };

    private final HandlerThread thread = new HandlerThread("Compositor", android.os.Process.THREAD_PRIORITY_DISPLAY);
    private final Handler handler;
    private volatile ErrorListener errorListener;

    // ---- GL-thread state ----
    private EglCore egl;
    private EGLSurface idleSurface;
    private EGLSurface encSurface;
    private EGLSurface prevSurface;
    private Surface encSurfaceRef;
    private Surface prevSurfaceRef;
    private int progOes, prog2d;
    private int aPosO, aTexO, uTexO, uColorO;
    private int aPos2, aTex2, uTex2, uColor2;
    private int whiteTex;
    private volatile List<Layer> layers = new ArrayList<>();
    private long startMs = SystemClock.uptimeMillis();
    private FloatBuffer quad;
    private final List<VideoSource> sources = new ArrayList<>();
    private int outW = 1280, outH = 720;
    private long frameIntervalNs = 1_000_000_000L / 30;
    private long nextFrameNs;
    private boolean running;
    private final float[] texMatrix = new float[16];
    private final float[] tmpA = new float[16];
    private final float[] tmpB = new float[16];
    private final int[] qs = new int[1];

    // ---- stats ----
    private volatile int fpsMeasured;
    private int framesThisSecond;
    private long secondStartMs;
    private volatile long totalFrames;

    public Compositor() {
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    egl = new EglCore();
                    idleSurface = egl.createPbuffer(1, 1);
                    egl.makeCurrent(idleSurface);
                    initProgram();
                } catch (Throwable t) {
                    fail(t);
                }
            }
        });
    }

    public void setErrorListener(ErrorListener l) {
        errorListener = l;
    }

    // ================= public API (any thread) =================

    public void setOutputSize(final int w, final int h) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                outW = w;
                outH = h;
            }
        });
    }

    public void setFps(final int fps) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                frameIntervalNs = 1_000_000_000L / Math.max(1, fps);
            }
        });
    }

    public void addSource(final VideoSource s) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (sources.contains(s)) {
                        return;
                    }
                    s.initGl();
                    sources.add(s);
                    s.start();
                } catch (Throwable t) {
                    fail(t);
                }
            }
        });
    }

    public void removeSource(final VideoSource s) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (sources.remove(s)) {
                    s.stop();
                    s.releaseGl();
                }
            }
        });
    }

    /** Replace the drawn scene (bottom to top). Property edits on the layers are seen live. */
    public void setLayers(List<Layer> list) {
        layers = new ArrayList<>(list);
    }

    public void setEncoderSurface(final Surface s) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (encSurface != null) {
                        egl.makeCurrent(idleSurface);
                        egl.releaseSurface(encSurface);
                        encSurface = null;
                    }
                    encSurfaceRef = s;
                    if (s != null) {
                        encSurface = egl.createWindowSurface(s);
                    }
                } catch (Throwable t) {
                    fail(t);
                }
            }
        });
    }

    public void setPreviewSurface(final Surface s) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (prevSurface != null) {
                        egl.makeCurrent(idleSurface);
                        egl.releaseSurface(prevSurface);
                        prevSurface = null;
                    }
                    prevSurfaceRef = s;
                    if (s != null) {
                        prevSurface = egl.createWindowSurface(s);
                    }
                } catch (Throwable t) {
                    fail(t);
                }
            }
        });
    }

    /** Synchronously detach the preview (call from surfaceDestroyed so the surface is free on return). */
    public void clearPreviewSurfaceBlocking() {
        final Object lock = new Object();
        final boolean[] done = {false};
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (prevSurface != null) {
                        egl.makeCurrent(idleSurface);
                        egl.releaseSurface(prevSurface);
                        prevSurface = null;
                    }
                    prevSurfaceRef = null;
                } catch (Throwable t) {
                    Log.w(TAG, "clear preview", t);
                }
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        });
        synchronized (lock) {
            long end = SystemClock.uptimeMillis() + 1000;
            while (!done[0]) {
                long left = end - SystemClock.uptimeMillis();
                if (left <= 0) {
                    break;
                }
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /** Block until everything queued so far has run on the GL thread (max ~1s). */
    public void flush() {
        final Object lock = new Object();
        final boolean[] done = {false};
        handler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        });
        synchronized (lock) {
            long end = SystemClock.uptimeMillis() + 1000;
            while (!done[0]) {
                long left = end - SystemClock.uptimeMillis();
                if (left <= 0) {
                    break;
                }
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    public void start() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (running) {
                    return;
                }
                running = true;
                nextFrameNs = System.nanoTime();
                secondStartMs = SystemClock.uptimeMillis();
                frameTick.run();
            }
        });
    }

    public void stopLoop() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                running = false;
                handler.removeCallbacks(frameTick);
            }
        });
    }

    public void release() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                running = false;
                handler.removeCallbacks(frameTick);
                try {
                    for (VideoSource s : new ArrayList<>(sources)) {
                        s.stop();
                        s.releaseGl();
                    }
                    sources.clear();
                    if (egl != null) {
                        egl.makeCurrent(idleSurface);
                        egl.releaseSurface(encSurface);
                        egl.releaseSurface(prevSurface);
                        egl.releaseSurface(idleSurface);
                        egl.release();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "release", t);
                }
                thread.quitSafely();
            }
        });
    }

    public int getMeasuredFps() {
        return fpsMeasured;
    }

    public long getTotalFrames() {
        return totalFrames;
    }

    // ================= GL thread =================

    private final Runnable frameTick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            try {
                renderFrame();
            } catch (Throwable t) {
                fail(t);
                return;
            }
            nextFrameNs += frameIntervalNs;
            long now = System.nanoTime();
            if (nextFrameNs < now - frameIntervalNs) {
                nextFrameNs = now; // fell badly behind: resync instead of bursting
            }
            long delayMs = Math.max(0, (nextFrameNs - now) / 1_000_000L);
            handler.postDelayed(this, delayMs);
        }
    };

    private void renderFrame() {
        for (VideoSource s : sources) {
            s.latchFrame();
        }
        if (encSurface == null && prevSurface == null) {
            return;
        }
        if (encSurface != null) {
            egl.makeCurrent(encSurface);
            drawScene(0, 0, outW, outH);
            egl.setPresentationTime(encSurface, System.nanoTime());
            egl.swapBuffers(encSurface);
        }
        if (prevSurface != null) {
            egl.makeCurrent(prevSurface);
            EGL14.eglQuerySurface(EGL14.eglGetCurrentDisplay(), prevSurface, EGL14.EGL_WIDTH, qs, 0);
            int sw = qs[0];
            EGL14.eglQuerySurface(EGL14.eglGetCurrentDisplay(), prevSurface, EGL14.EGL_HEIGHT, qs, 0);
            int sh = qs[0];
            if (sw > 0 && sh > 0) {
                // fit the output aspect inside the preview surface (letterbox)
                float oa = (float) outW / outH;
                float sa = (float) sw / sh;
                int vw, vh;
                if (sa > oa) {
                    vh = sh;
                    vw = Math.round(sh * oa);
                } else {
                    vw = sw;
                    vh = Math.round(sw / oa);
                }
                GLES20.glViewport(0, 0, sw, sh);
                GLES20.glClearColor(0f, 0f, 0f, 1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                drawScene((sw - vw) / 2, (sh - vh) / 2, vw, vh);
                egl.swapBuffers(prevSurface);
            }
        }
        egl.makeCurrent(idleSurface);

        totalFrames++;
        framesThisSecond++;
        long nowMs = SystemClock.uptimeMillis();
        if (nowMs - secondStartMs >= 1000) {
            fpsMeasured = framesThisSecond;
            framesThisSecond = 0;
            secondStartMs = nowMs;
        }
    }

    /** Draw the whole scene into the area (ox, oy, w, h) of the current surface. */
    private void drawScene(int ox, int oy, int w, int h) {
        GLES20.glViewport(ox, oy, w, h);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glScissor(ox, oy, w, h);
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA); // premultiplied

        for (Layer l : layers) {
            if (!l.visible || l.opacity <= 0.001f) {
                continue;
            }
            int lx = ox + Math.round(l.x * w);
            int lw = Math.round(l.w * w);
            int lh = Math.round(l.h * h);
            int ly = oy + h - Math.round(l.y * h) - lh;   // GL origin is bottom-left
            if (lw < 1 || lh < 1) {
                continue;
            }
            // never draw outside the frame
            int cx0 = Math.max(lx, ox), cy0 = Math.max(ly, oy);
            int cx1 = Math.min(lx + lw, ox + w), cy1 = Math.min(ly + lh, oy + h);
            if (cx1 <= cx0 || cy1 <= cy0) {
                continue;
            }
            GLES20.glScissor(cx0, cy0, cx1 - cx0, cy1 - cy0);
            if (l instanceof SourceLayer) {
                drawSource((SourceLayer) l, lx, ly, lw, lh);
            } else if (l instanceof BitmapLayer) {
                drawBitmapLayer((BitmapLayer) l, ox, oy, w, h, lx, ly, lw, lh, cx0, cy0, cx1, cy1);
            }
        }
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
    }

    private void bindQuad(int aPos, int aTex) {
        quad.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aPos);
        quad.position(2);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aTex);
    }

    private void drawSource(SourceLayer l, int tx, int ty, int tw, int th) {
        VideoSource s = l.source;
        if (!s.hasFrame()) {
            return;
        }
        int rot = s.getRotationDegrees();
        boolean swap = (rot % 180) != 0;
        float baseW = swap ? s.getBaseHeight() : s.getBaseWidth();
        float baseH = swap ? s.getBaseWidth() : s.getBaseHeight();
        float cl = clamp01(l.cropL), ct = clamp01(l.cropT), cr = clamp01(l.cropR), cb = clamp01(l.cropB);
        float rw = Math.max(0.02f, 1f - cl - cr);
        float rh = Math.max(0.02f, 1f - ct - cb);
        // aspect of the visible (cropped) region in the upright image
        float sa = (baseW * rw) / (baseH * rh);
        float ta = (float) tw / th;
        float fx = 1f, fy = 1f;
        int vx = tx, vy = ty, vw = tw, vh = th;
        switch (l.fit) {
            case SourceLayer.FIT:
                if (sa > ta) {
                    vh = Math.round(tw / sa);
                    vy = ty + (th - vh) / 2;
                } else {
                    vw = Math.round(th * sa);
                    vx = tx + (tw - vw) / 2;
                }
                break;
            case SourceLayer.STRETCH:
                break;
            case SourceLayer.FILL:
            default:
                if (sa > ta) {
                    fx = ta / sa;
                } else {
                    fy = sa / ta;
                }
        }
        boolean mirror = s.isMirrored() ^ l.flip;
        // texMatrix = SurfaceTexture * Rotate * Region * Fill * Mirror   (applied right to left)
        Matrix.setIdentityM(tmpA, 0);
        if (mirror) {
            Matrix.translateM(tmpA, 0, 0.5f, 0.5f, 0f);
            Matrix.scaleM(tmpA, 0, -1f, 1f, 1f);
            Matrix.translateM(tmpA, 0, -0.5f, -0.5f, 0f);
        }
        Matrix.setIdentityM(tmpB, 0);
        Matrix.translateM(tmpB, 0, 0.5f, 0.5f, 0f);
        Matrix.scaleM(tmpB, 0, fx, fy, 1f);
        Matrix.translateM(tmpB, 0, -0.5f, -0.5f, 0f);          // Fill
        Matrix.multiplyMM(texMatrix, 0, tmpB, 0, tmpA, 0);
        Matrix.setIdentityM(tmpA, 0);
        Matrix.translateM(tmpA, 0, cl, cb, 0f);                // v is up: bottom inset is cropB
        Matrix.scaleM(tmpA, 0, rw, rh, 1f);                    // Region
        Matrix.multiplyMM(tmpB, 0, tmpA, 0, texMatrix, 0);
        Matrix.setIdentityM(tmpA, 0);
        Matrix.translateM(tmpA, 0, 0.5f, 0.5f, 0f);
        Matrix.rotateM(tmpA, 0, rot, 0f, 0f, 1f);
        Matrix.translateM(tmpA, 0, -0.5f, -0.5f, 0f);          // Rotate
        Matrix.multiplyMM(texMatrix, 0, tmpA, 0, tmpB, 0);
        Matrix.multiplyMM(tmpB, 0, s.transform(), 0, texMatrix, 0);

        GLES20.glUseProgram(progOes);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        bindQuad(aPosO, aTexO);
        GLES20.glViewport(vx, vy, vw, vh);
        GLES20.glUniformMatrix4fv(uTexO, 1, false, tmpB, 0);
        float a = l.opacity;
        GLES20.glUniform4f(uColorO, a, a, a, a);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, s.textureId);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void drawBitmapLayer(BitmapLayer l, int ox, int oy, int w, int h,
                                 int lx, int ly, int lw, int lh,
                                 int cx0, int cy0, int cx1, int cy1) {
        if (l.dirty || l.texId == 0) {
            uploadBitmap(l);
        }
        if (l.texId == 0) {
            return;
        }
        GLES20.glUseProgram(prog2d);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        bindQuad(aPos2, aTex2);
        // A bitmap's first row is its TOP but texture row 0 is drawn at the quad's bottom:
        // flip v (v' = 1 - v) so text and logos are not upside-down.
        Matrix.setIdentityM(tmpB, 0);
        Matrix.translateM(tmpB, 0, 0f, 1f, 0f);
        Matrix.scaleM(tmpB, 0, 1f, -1f, 1f);
        GLES20.glUniformMatrix4fv(uTex2, 1, false, tmpB, 0);
        float a = l.opacity;

        int bar = l.barColor();
        if ((bar >>> 24) > 0) {
            float ba = ((bar >>> 24) & 0xff) / 255f * a;
            GLES20.glViewport(lx, ly, lw, lh);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, whiteTex);
            GLES20.glUniform4f(uColor2, ((bar >> 16) & 0xff) / 255f * ba, ((bar >> 8) & 0xff) / 255f * ba,
                (bar & 0xff) / 255f * ba, ba);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, l.texId);
        GLES20.glUniform4f(uColor2, a, a, a, a);
        if (l.isMarquee()) {
            // the text strip travels right-to-left across the box, clipped to it
            float scale = (float) w / outW;
            int stripW = Math.max(1, Math.round(l.texW * scale));
            float cycle = lw + stripW;
            float travelled = (SystemClock.uptimeMillis() - startMs) / 1000f * l.marqueeSpeed() * w;
            int px = lx + lw - Math.round(travelled % cycle);
            GLES20.glViewport(px, ly, stripW, lh);
        } else {
            GLES20.glViewport(lx, ly, lw, lh);
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void uploadBitmap(BitmapLayer l) {
        l.onOutputSizeIfNeeded(outW, outH);
        android.graphics.Bitmap bmp;
        try {
            bmp = l.renderBitmap(outW, outH);
        } catch (RuntimeException e) {
            Log.w(TAG, "layer render failed", e);
            l.dirty = false;
            return;
        }
        if (bmp == null) {
            l.dirty = false;
            return;
        }
        if (l.texId == 0) {
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            l.texId = t[0];
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, l.texId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        l.texW = bmp.getWidth();
        l.texH = bmp.getHeight();
        if (!(l instanceof ImageLayer)) {
            bmp.recycle();
        }
        l.dirty = false;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : v > 0.95f ? 0.95f : v;
    }

    private void initProgram() {
        progOes = link(VERTEX, FRAGMENT_OES);
        aPosO = GLES20.glGetAttribLocation(progOes, "aPos");
        aTexO = GLES20.glGetAttribLocation(progOes, "aTex");
        uTexO = GLES20.glGetUniformLocation(progOes, "uTex");
        uColorO = GLES20.glGetUniformLocation(progOes, "uColor");
        prog2d = link(VERTEX, FRAGMENT_2D);
        aPos2 = GLES20.glGetAttribLocation(prog2d, "aPos");
        aTex2 = GLES20.glGetAttribLocation(prog2d, "aTex");
        uTex2 = GLES20.glGetUniformLocation(prog2d, "uTex");
        uColor2 = GLES20.glGetUniformLocation(prog2d, "uColor");
        quad = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(QUAD).position(0);
        // 1x1 white texture: lets solid bars share the textured-quad path
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        whiteTex = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, whiteTex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255}));
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
    }

    private static int link(String vsSrc, String fsSrc) {
        int vs = compile(GLES20.GL_VERTEX_SHADER, vsSrc);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, fsSrc);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            throw new RuntimeException("link failed: " + GLES20.glGetProgramInfoLog(p));
        }
        return p;
    }

    private static int compile(int type, String src) {
        int sh = GLES20.glCreateShader(type);
        GLES20.glShaderSource(sh, src);
        GLES20.glCompileShader(sh);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            throw new RuntimeException("shader compile failed: " + GLES20.glGetShaderInfoLog(sh));
        }
        return sh;
    }

    private void fail(Throwable t) {
        Log.e(TAG, "compositor failure", t);
        ErrorListener l = errorListener;
        if (l != null) {
            l.onCompositorError(t);
        }
    }
}
