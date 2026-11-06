package net.ossrs.yasea.engine;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

/** Title, lower-third, watermark text or scrolling marquee, rendered to a bitmap. */
public final class TextLayer extends BitmapLayer {
    public volatile String text = "Your title";
    public volatile int textColor = Color.WHITE;
    /** Box background (ARGB). For a marquee this is the bar behind the whole box. */
    public volatile int bgColor = 0x99000000;
    /** Text height as a fraction of the output height. */
    public volatile float sizeFrac = 0.07f;
    public volatile boolean bold = true;
    public volatile boolean marquee = false;
    /** Marquee speed in output widths per second. */
    public volatile float speed = 0.12f;
    public volatile boolean shadow = true;

    private volatile int lastOutW = 1280, lastOutH = 720;

    public TextLayer(String text) {
        this.text = text;
        this.name = "Text";
        x = 0.05f;
        y = 0.05f;
    }

    @Override
    public String kind() {
        return "text";
    }

    @Override
    public boolean isMarquee() {
        return marquee;
    }

    @Override
    public float marqueeSpeed() {
        return speed;
    }

    @Override
    public int barColor() {
        return marquee ? bgColor : 0;
    }

    private Paint paint(int outH) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTextSize(Math.max(8f, sizeFrac * outH));
        p.setTypeface(Typeface.create(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL));
        p.setColor(textColor);
        return p;
    }

    /** Recompute the box from the text, size and output resolution (UI thread). */
    public void recompute(int outW, int outH) {
        lastOutW = outW;
        lastOutH = outH;
        Paint p = paint(outH);
        float pad = p.getTextSize() * 0.4f;
        float tw = p.measureText(text) + 2 * pad;
        float th = p.getTextSize() * 1.5f;
        h = th / outH;
        if (marquee) {
            if (w < 0.2f) {
                w = 1f;   // a ticker is a bar: default to full width
            }
        } else {
            w = Math.min(4f, tw / outW);
        }
        invalidate();
    }

    @Override
    public void onOutputSize(int outW, int outH) {
        if (outW != lastOutW || outH != lastOutH) {
            recompute(outW, outH);
        }
        super.onOutputSize(outW, outH);
    }

    @Override
    protected Bitmap renderBitmap(int outW, int outH) {
        Paint p = paint(outH);
        float pad = p.getTextSize() * 0.4f;
        float tw = p.measureText(text) + 2 * pad;
        int bh = Math.max(2, Math.round(p.getTextSize() * 1.5f));
        int bw = marquee ? Math.max(2, Math.round(tw)) : Math.max(2, Math.round(Math.min(w * outW, 4096)));
        bw = Math.min(bw, 8192);
        Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        if (!marquee && Color.alpha(bgColor) > 0) {
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(bgColor);
            c.drawRoundRect(new RectF(0, 0, bw, bh), bh * 0.25f, bh * 0.25f, bg);
        }
        if (shadow) {
            p.setShadowLayer(p.getTextSize() * 0.08f, 0, p.getTextSize() * 0.04f, 0xCC000000);
        }
        Paint.FontMetrics fm = p.getFontMetrics();
        float baseline = bh / 2f - (fm.ascent + fm.descent) / 2f;
        c.drawText(text, pad, baseline, p);
        return bmp;
    }
}
