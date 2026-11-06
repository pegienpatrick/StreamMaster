package net.ossrs.yasea.engine;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.util.Log;

/**
 * Minimal EGL14 wrapper (GLES 2). One context is shared by every output surface
 * (encoder, preview) so the compositor draws once per output with the same textures.
 */
public final class EglCore {
    private static final String TAG = "EglCore";
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLConfig config;

    public EglCore() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("unable to get EGL display");
        }
        int[] ver = new int[2];
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) {
            throw new RuntimeException("unable to initialize EGL");
        }
        int[] attribs = {
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] n = new int[1];
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0) || n[0] == 0) {
            throw new RuntimeException("no recordable EGL config");
        }
        config = configs[0];
        int[] ctxAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        check("eglCreateContext");
    }

    public EGLSurface createWindowSurface(Object surface) {
        int[] attribs = {EGL14.EGL_NONE};
        EGLSurface s = EGL14.eglCreateWindowSurface(display, config, surface, attribs, 0);
        check("eglCreateWindowSurface");
        return s;
    }

    public EGLSurface createPbuffer(int w, int h) {
        int[] attribs = {EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE};
        EGLSurface s = EGL14.eglCreatePbufferSurface(display, config, attribs, 0);
        check("eglCreatePbufferSurface");
        return s;
    }

    public void makeCurrent(EGLSurface s) {
        if (!EGL14.eglMakeCurrent(display, s, s, context)) {
            throw new RuntimeException("eglMakeCurrent failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
        }
    }

    public void makeNothingCurrent() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
    }

    public boolean swapBuffers(EGLSurface s) {
        return EGL14.eglSwapBuffers(display, s);
    }

    public void setPresentationTime(EGLSurface s, long nanos) {
        EGLExt.eglPresentationTimeANDROID(display, s, nanos);
    }

    public void releaseSurface(EGLSurface s) {
        if (s != null && s != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, s);
        }
    }

    public void release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            makeNothingCurrent();
            EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(display);
        }
        display = EGL14.EGL_NO_DISPLAY;
        context = EGL14.EGL_NO_CONTEXT;
    }

    private static void check(String op) {
        int err = EGL14.eglGetError();
        if (err != EGL14.EGL_SUCCESS) {
            Log.e(TAG, op + ": EGL error 0x" + Integer.toHexString(err));
            throw new RuntimeException(op + ": EGL error 0x" + Integer.toHexString(err));
        }
    }
}
