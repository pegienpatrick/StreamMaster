package net.ossrs.yasea.engine;

import android.graphics.Bitmap;

/** A logo / watermark / overlay picture. */
public final class ImageLayer extends BitmapLayer {
    private final Bitmap bitmap;
    /** File the picture was saved to, so the scene can be restored. */
    public volatile String path;

    public ImageLayer(Bitmap bitmap, String name) {
        this.bitmap = bitmap;
        this.name = name;
        this.opacity = 0.9f;
        this.w = 0.18f;
        this.x = 0.78f;
        this.y = 0.05f;
    }

    @Override
    public String kind() {
        return "image";
    }

    @Override
    public boolean keepAspect() {
        return true;
    }

    /** Height that keeps the picture's proportions for the current width and output size. */
    @Override
    public void onOutputSize(int outW, int outH) {
        h = w * outW / outH * bitmap.getHeight() / bitmap.getWidth();
        super.onOutputSize(outW, outH);
    }

    @Override
    protected Bitmap renderBitmap(int outW, int outH) {
        return bitmap;
    }
}
