package net.ossrs.yasea.engine;

/**
 * How many sources are shown and where. Rects are normalized (0..1) with the
 * origin at the top-left, in slot order. The first slot is the "main" source.
 */
public enum LayoutMode {
    SINGLE(1),
    PIP(2),          // slot 0 full frame, slot 1 small inset bottom-right
    SPLIT_H(2),      // side by side
    SPLIT_V(2),      // stacked
    GRID_2X2(4);

    public final int slots;

    LayoutMode(int slots) {
        this.slots = slots;
    }

    /** Returns {x, y, w, h} per slot, normalized, origin top-left. */
    public float[][] rects() {
        switch (this) {
            case PIP:
                return new float[][]{
                    {0f, 0f, 1f, 1f},
                    {0.66f, 0.64f, 0.30f, 0.30f}
                };
            case SPLIT_H:
                return new float[][]{{0f, 0f, 0.5f, 1f}, {0.5f, 0f, 0.5f, 1f}};
            case SPLIT_V:
                return new float[][]{{0f, 0f, 1f, 0.5f}, {0f, 0.5f, 1f, 0.5f}};
            case GRID_2X2:
                return new float[][]{
                    {0f, 0f, 0.5f, 0.5f}, {0.5f, 0f, 0.5f, 0.5f},
                    {0f, 0.5f, 0.5f, 0.5f}, {0.5f, 0.5f, 0.5f, 0.5f}
                };
            case SINGLE:
            default:
                return new float[][]{{0f, 0f, 1f, 1f}};
        }
    }
}
