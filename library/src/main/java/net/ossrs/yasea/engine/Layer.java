package net.ossrs.yasea.engine;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * One element of the scene (a video source, text or image). Position and size are
 * normalized to the output frame with the origin at the top-left. Fields are volatile:
 * the UI thread edits them while the GPU thread draws.
 */
public abstract class Layer {
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    public final int id = NEXT_ID.getAndIncrement();
    public volatile String name = "Layer";
    public volatile boolean visible = true;
    public volatile float x = 0f, y = 0f, w = 1f, h = 1f;
    public volatile float opacity = 1f;

    public abstract String kind();

    /** Keep at least a sliver of the layer inside the frame so it can always be grabbed. */
    public void clampToFrame() {
        w = Math.max(0.03f, Math.min(w, 4f));
        h = Math.max(0.03f, Math.min(h, 4f));
        x = Math.max(-w + 0.05f, Math.min(x, 0.95f));
        y = Math.max(-h + 0.05f, Math.min(y, 0.95f));
    }

    public void setRect(float x, float y, float w, float h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }
}
