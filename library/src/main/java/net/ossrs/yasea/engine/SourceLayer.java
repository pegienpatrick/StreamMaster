package net.ossrs.yasea.engine;

/** A live video source (camera, screen) placed in the scene. */
public final class SourceLayer extends Layer {
    /** Fill the box, cropping the overflow. */
    public static final int FILL = 0;
    /** Show the whole image, letterboxed inside the box. */
    public static final int FIT = 1;
    /** Stretch to the box ignoring aspect ratio. */
    public static final int STRETCH = 2;

    public final VideoSource source;
    public volatile int fit = FILL;
    /** User crop as fractions of the (upright) image removed from each side. */
    public volatile float cropL, cropT, cropR, cropB;
    /** Extra horizontal flip on top of the source's own mirroring. */
    public volatile boolean flip;

    public SourceLayer(VideoSource source) {
        this.source = source;
        this.name = source.getLabel();
        this.fit = source.isContain() ? FIT : FILL;
    }

    @Override
    public String kind() {
        return "source";
    }

    public void resetCrop() {
        cropL = cropT = cropR = cropB = 0f;
    }
}
