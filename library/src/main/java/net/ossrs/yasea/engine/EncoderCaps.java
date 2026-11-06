package net.ossrs.yasea.engine;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.util.Log;

/** What this device's hardware encoders can really do (not what we wish they could). */
public final class EncoderCaps {
    private static final String TAG = "EncoderCaps";

    private EncoderCaps() {
    }

    /** True if any encoder for {@code mime} accepts this size at this frame rate. */
    public static boolean supports(String mime, int w, int h, int fps) {
        // codecs describe sizes landscape-first
        int lw = Math.max(w, h);
        int lh = Math.min(w, h);
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) {
                continue;
            }
            for (String t : info.getSupportedTypes()) {
                if (!t.equalsIgnoreCase(mime)) {
                    continue;
                }
                try {
                    MediaCodecInfo.VideoCapabilities vc =
                        info.getCapabilitiesForType(t).getVideoCapabilities();
                    if (vc != null && vc.areSizeAndRateSupported(lw, lh, fps)) {
                        return true;
                    }
                } catch (RuntimeException e) {
                    Log.w(TAG, "query failed for " + info.getName(), e);
                }
            }
        }
        return false;
    }

    /** Largest supported width x height for a codec (landscape), or {0,0}. */
    public static int[] maxSize(String mime) {
        int bw = 0, bh = 0;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) {
                continue;
            }
            for (String t : info.getSupportedTypes()) {
                if (!t.equalsIgnoreCase(mime)) {
                    continue;
                }
                try {
                    MediaCodecInfo.VideoCapabilities vc =
                        info.getCapabilitiesForType(t).getVideoCapabilities();
                    if (vc == null) {
                        continue;
                    }
                    int w = vc.getSupportedWidths().getUpper();
                    int h = vc.getSupportedHeightsFor(w).getUpper();
                    if ((long) w * h > (long) bw * bh) {
                        bw = w;
                        bh = h;
                    }
                } catch (RuntimeException e) {
                    Log.w(TAG, "query failed for " + info.getName(), e);
                }
            }
        }
        return new int[]{bw, bh};
    }
}
