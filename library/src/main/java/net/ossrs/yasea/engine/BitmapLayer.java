package net.ossrs.yasea.engine;

import android.graphics.Bitmap;

/** A layer drawn from a bitmap (text, logo). The GPU thread uploads it when it changes. */
public abstract class BitmapLayer extends Layer {
    // GPU-thread state
    int texId;
    int texW, texH;
    volatile boolean dirty = true;

    /** Render the bitmap for the current output size. Called on the GPU thread when dirty. */
    protected abstract Bitmap renderBitmap(int outW, int outH);

    /** Scrolling ticker: the bitmap travels across the box instead of filling it. */
    public boolean isMarquee() {
        return false;
    }

    /** Marquee speed in output widths per second. */
    public float marqueeSpeed() {
        return 0.12f;
    }

    /** Solid bar drawn behind a marquee across the whole box (ARGB, 0 = none). */
    public int barColor() {
        return 0;
    }

    /** Output size or content changed: recompute the box and re-render. */
    public void invalidate() {
        dirty = true;
    }

    /** Called by the engine when the output size is known/changes. */
    public void onOutputSize(int outW, int outH) {
        dirty = true;
    }

    private int sizedFor = -1;

    /** GPU thread: make sure the layer knows the output size before its first render. */
    final void onOutputSizeIfNeeded(int outW, int outH) {
        int key = outW * 31 + outH;
        if (key != sizedFor) {
            sizedFor = key;
            onOutputSize(outW, outH);
        }
    }

    /** Keep aspect when scaling (images); text/boxes override to false. */
    public boolean keepAspect() {
        return false;
    }
}
