package net.ossrs.yasea.demo.studio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import net.ossrs.yasea.engine.BitmapLayer;
import net.ossrs.yasea.engine.ImageLayer;
import net.ossrs.yasea.engine.Layer;
import net.ossrs.yasea.engine.SourceLayer;
import net.ossrs.yasea.engine.StreamEngine;
import net.ossrs.yasea.engine.TextLayer;

import java.util.List;

/**
 * Transparent overlay on top of the live preview. The preview shows exactly what is
 * streamed, and this view lets you edit it in place: tap to select a layer, drag to
 * move, pinch or drag a corner to resize, double-tap for its properties.
 */
public class SceneEditorView extends View {

    public interface Callback {
        void onLayerSelected(Layer layer);

        void onLayerProperties(Layer layer);

        void onLayerEdited();
    }

    private StreamEngine engine;
    private Callback callback;
    private Layer selected;
    private int outW = 1280, outH = 720;

    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint faint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF content = new RectF();
    private final RectF tmp = new RectF();

    private final GestureDetector taps;
    private final ScaleGestureDetector scaler;
    private float lastX, lastY;
    private boolean moving, resizing, scaling;
    private int corner = -1;
    private float anchorX, anchorY;           // fixed opposite corner while resizing (normalized)
    private long showAllUntil;
    private boolean editable = true;

    public void setEditable(boolean e) {
        editable = e;
        if (!e) {
            selected = null;
        }
        invalidate();
    }

    public SceneEditorView(Context c) {
        this(c, null);
    }

    public SceneEditorView(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(2.5f * d);
        outline.setColor(0xFF6C5CE7);
        faint.setStyle(Paint.Style.STROKE);
        faint.setStrokeWidth(1f * d);
        faint.setColor(0x66FFFFFF);
        handle.setStyle(Paint.Style.FILL);
        handle.setColor(0xFFFFFFFF);
        labelPaint.setTextSize(12 * d);
        labelPaint.setColor(0xFFFFFFFF);
        labelPaint.setShadowLayer(3 * d, 0, 0, 0xFF000000);

        taps = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                Layer l = hit(e.getX(), e.getY());
                if (l != null && callback != null) {
                    select(l);
                    callback.onLayerProperties(l);
                }
                return true;
            }
        });
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                scaling = selected != null;
                return scaling;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                if (selected != null) {
                    scaleLayer(selected, d.getScaleFactor());
                    invalidate();
                }
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                scaling = false;
            }
        });
    }

    public void bind(StreamEngine engine, Callback cb) {
        this.engine = engine;
        this.callback = cb;
        invalidate();
    }

    public void setOutputSize(int w, int h) {
        outW = Math.max(1, w);
        outH = Math.max(1, h);
        invalidate();
    }

    public void select(Layer l) {
        selected = l;
        showAllUntil = System.currentTimeMillis() + 1500;
        if (callback != null) {
            callback.onLayerSelected(l);
        }
        invalidate();
    }

    public Layer getSelected() {
        return selected;
    }

    // ------------------------------------------------------------ geometry

    /** The rectangle the output frame occupies inside this view (same letterbox as the GPU preview). */
    private void computeContent() {
        float vw = getWidth(), vh = getHeight();
        float oa = (float) outW / outH;
        float w, h;
        if (vw / vh > oa) {
            h = vh;
            w = vh * oa;
        } else {
            w = vw;
            h = vw / oa;
        }
        content.set((vw - w) / 2f, (vh - h) / 2f, (vw + w) / 2f, (vh + h) / 2f);
    }

    private void layerRect(Layer l, RectF out) {
        out.set(content.left + l.x * content.width(), content.top + l.y * content.height(),
            content.left + (l.x + l.w) * content.width(), content.top + (l.y + l.h) * content.height());
    }

    private Layer hit(float px, float py) {
        computeContent();
        List<Layer> ls = engine.getLayers();
        for (int i = ls.size() - 1; i >= 0; i--) {   // topmost first
            Layer l = ls.get(i);
            if (!l.visible) {
                continue;
            }
            layerRect(l, tmp);
            if (tmp.contains(px, py)) {
                return l;
            }
        }
        return null;
    }

    private int cornerAt(float px, float py) {
        if (selected == null) {
            return -1;
        }
        computeContent();
        layerRect(selected, tmp);
        float slop = 28 * getResources().getDisplayMetrics().density;
        float[][] cs = {{tmp.left, tmp.top}, {tmp.right, tmp.top}, {tmp.right, tmp.bottom}, {tmp.left, tmp.bottom}};
        for (int i = 0; i < 4; i++) {
            if (Math.hypot(px - cs[i][0], py - cs[i][1]) < slop) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------ editing

    private void scaleLayer(Layer l, float f) {
        f = Math.max(0.5f, Math.min(f, 2f));
        engine.markManualLayout();
        float cx = l.x + l.w / 2f, cy = l.y + l.h / 2f;
        if (l instanceof TextLayer) {
            TextLayer t = (TextLayer) l;
            t.sizeFrac = Math.max(0.02f, Math.min(0.4f, t.sizeFrac * f));
            t.recompute(outW, outH);
        } else if (l instanceof BitmapLayer && ((BitmapLayer) l).keepAspect()) {
            l.w = Math.max(0.03f, Math.min(3f, l.w * f));
            ((BitmapLayer) l).onOutputSize(outW, outH);
        } else {
            l.w *= f;
            l.h *= f;
        }
        l.x = cx - l.w / 2f;
        l.y = cy - l.h / 2f;
        l.clampToFrame();
    }

    private void resizeFromCorner(Layer l, float nx, float ny) {
        engine.markManualLayout();
        float x0 = Math.min(anchorX, nx), x1 = Math.max(anchorX, nx);
        float y0 = Math.min(anchorY, ny), y1 = Math.max(anchorY, ny);
        float w = Math.max(0.03f, x1 - x0), h = Math.max(0.03f, y1 - y0);
        if (l instanceof TextLayer) {
            TextLayer t = (TextLayer) l;
            float ratio = h / Math.max(0.001f, l.h);
            t.sizeFrac = Math.max(0.02f, Math.min(0.4f, t.sizeFrac * ratio));
            t.recompute(outW, outH);
            return;
        }
        if (l instanceof BitmapLayer && ((BitmapLayer) l).keepAspect()) {
            l.w = w;
            ((BitmapLayer) l).onOutputSize(outW, outH);
            l.x = nx < anchorX ? anchorX - l.w : anchorX;
            l.y = ny < anchorY ? anchorY - l.h : anchorY;
            return;
        }
        l.setRect(x0, y0, w, h);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (engine == null || !editable) {
            return false;
        }
        taps.onTouchEvent(e);
        scaler.onTouchEvent(e);
        computeContent();
        float nx = (e.getX() - content.left) / content.width();
        float ny = (e.getY() - content.top) / content.height();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX();
                lastY = e.getY();
                corner = cornerAt(e.getX(), e.getY());
                if (corner >= 0) {
                    resizing = true;
                    // opposite corner stays put
                    Layer l = selected;
                    float[][] cs = {{l.x, l.y}, {l.x + l.w, l.y}, {l.x + l.w, l.y + l.h}, {l.x, l.y + l.h}};
                    anchorX = cs[(corner + 2) % 4][0];
                    anchorY = cs[(corner + 2) % 4][1];
                } else {
                    Layer l = hit(e.getX(), e.getY());
                    if (l != selected) {
                        select(l);
                    }
                    moving = selected != null;
                }
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (scaling || e.getPointerCount() > 1) {
                    return true;
                }
                if (resizing && selected != null) {
                    resizeFromCorner(selected, nx, ny);
                } else if (moving && selected != null) {
                    engine.markManualLayout();
                    selected.x += (e.getX() - lastX) / content.width();
                    selected.y += (e.getY() - lastY) / content.height();
                    selected.clampToFrame();
                }
                lastX = e.getX();
                lastY = e.getY();
                invalidate();
                return true;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (e.getActionMasked() != MotionEvent.ACTION_POINTER_UP) {
                    if ((moving || resizing || scaling) && callback != null) {
                        callback.onLayerEdited();
                    }
                    moving = resizing = false;
                }
                lastX = e.getX();
                lastY = e.getY();
                return true;
            default:
                return true;
        }
    }

    // ------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        if (engine == null) {
            return;
        }
        computeContent();
        boolean all = System.currentTimeMillis() < showAllUntil;
        for (Layer l : engine.getLayers()) {
            if (!l.visible) {
                continue;
            }
            layerRect(l, tmp);
            if (l == selected) {
                c.drawRect(tmp, outline);
                float r = 7 * getResources().getDisplayMetrics().density;
                c.drawCircle(tmp.left, tmp.top, r, handle);
                c.drawCircle(tmp.right, tmp.top, r, handle);
                c.drawCircle(tmp.right, tmp.bottom, r, handle);
                c.drawCircle(tmp.left, tmp.bottom, r, handle);
                c.drawText(l.name + (l instanceof SourceLayer ? "" : ""), tmp.left + 8, tmp.top + 18 * getResources().getDisplayMetrics().density, labelPaint);
            } else if (all) {
                c.drawRect(tmp, faint);
            }
        }
        if (all) {
            postInvalidateDelayed(200);
        }
    }
}
