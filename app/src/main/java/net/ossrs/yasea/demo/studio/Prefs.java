package net.ossrs.yasea.demo.studio;

import android.content.Context;
import android.content.SharedPreferences;

/** User settings, persisted. */
public final class Prefs {
    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences("streammaster", Context.MODE_PRIVATE);
    }

    public String url() {
        return sp.getString("url", "");
    }

    public void url(String v) {
        sp.edit().putString("url", v).apply();
    }

    /** Output short side in pixels: 480, 720, 1080, 1440, 2160 (4K), 4320 (8K). */
    public int quality() {
        return sp.getInt("quality", 720);
    }

    public void quality(int v) {
        sp.edit().putInt("quality", v).apply();
    }

    public int fps() {
        return sp.getInt("fps", 30);
    }

    public void fps(int v) {
        sp.edit().putInt("fps", v).apply();
    }

    public static final int ORIENT_AUTO = 0;
    public static final int ORIENT_LANDSCAPE = 1;
    public static final int ORIENT_PORTRAIT = 2;

    /** AUTO follows how the phone is held (output flips with it while idle); the others lock it. */
    public int orientMode() {
        return sp.getInt("orient", ORIENT_AUTO);
    }

    public void orientMode(int v) {
        sp.edit().putInt("orient", v).apply();
    }

    /** Runtime state for AUTO: true when the phone is currently held upright. */
    public volatile boolean resolvedPortrait;

    public boolean portrait() {
        int m = orientMode();
        return m == ORIENT_PORTRAIT || (m == ORIENT_AUTO && resolvedPortrait);
    }

    /** 0 = automatic from resolution and fps. */
    public int bitrateKbps() {
        return sp.getInt("bitrate", 0);
    }

    public void bitrateKbps(int v) {
        sp.edit().putInt("bitrate", v).apply();
    }

    public boolean adaptive() {
        return sp.getBoolean("adaptive", true);
    }

    public void adaptive(boolean v) {
        sp.edit().putBoolean("adaptive", v).apply();
    }

    public int width() {
        int short_ = quality();
        int long_ = Math.round(short_ * 16f / 9f) & ~1;
        return portrait() ? short_ : long_;
    }

    public int height() {
        int short_ = quality();
        int long_ = Math.round(short_ * 16f / 9f) & ~1;
        return portrait() ? long_ : short_;
    }

    public int effectiveBitrate() {
        if (bitrateKbps() > 0) {
            return bitrateKbps() * 1000;
        }
        // ~0.07 bits/pixel/frame for CBR H.264 live, clamped
        long b = (long) (width() * (long) height() * fps() * 0.07);
        return (int) Math.max(1_000_000L, Math.min(b, 60_000_000L));
    }

    // ---- kiosk ----
    public boolean kiosk() {
        return sp.getBoolean("kiosk", false);
    }

    public void kiosk(boolean v) {
        sp.edit().putBoolean("kiosk", v).apply();
    }

    public String kioskPin() {
        return sp.getString("kioskPin", "");
    }

    public void kioskPin(String v) {
        sp.edit().putString("kioskPin", v).apply();
    }

    public boolean kioskAutoStream() {
        return sp.getBoolean("kioskAutoStream", false);
    }

    public void kioskAutoStream(boolean v) {
        sp.edit().putBoolean("kioskAutoStream", v).apply();
    }

    public boolean kioskOnBoot() {
        return sp.getBoolean("kioskOnBoot", false);
    }

    public void kioskOnBoot(boolean v) {
        sp.edit().putBoolean("kioskOnBoot", v).apply();
    }

    public boolean kioskAutoRecord() {
        return sp.getBoolean("kioskAutoRecord", false);
    }

    public void kioskAutoRecord(boolean v) {
        sp.edit().putBoolean("kioskAutoRecord", v).apply();
    }

    public boolean autoPip() {
        return sp.getBoolean("autoPip", true);
    }

    public void autoPip(boolean v) {
        sp.edit().putBoolean("autoPip", v).apply();
    }

    /** Settings that make up a profile. The kiosk switch, its PIN and (optionally) the stream URL stay out. */
    public org.json.JSONObject toJson(boolean includeUrl) {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            if (includeUrl) {
                o.put("url", url());
            }
            o.put("quality", quality()).put("fps", fps()).put("orient", orientMode());
            o.put("bitrate", bitrateKbps()).put("adaptive", adaptive()).put("autoPip", autoPip());
            o.put("kioskAutoStream", kioskAutoStream()).put("kioskAutoRecord", kioskAutoRecord());
            o.put("kioskOnBoot", kioskOnBoot()).put("kioskDark", kioskDark());
        } catch (org.json.JSONException ignored) {
        }
        return o;
    }

    public void fromJson(org.json.JSONObject o) {
        if (o.has("url")) {
            url(o.optString("url", url()));
        }
        quality(o.optInt("quality", quality()));
        fps(o.optInt("fps", fps()));
        orientMode(o.optInt("orient", orientMode()));
        bitrateKbps(o.optInt("bitrate", bitrateKbps()));
        adaptive(o.optBoolean("adaptive", adaptive()));
        autoPip(o.optBoolean("autoPip", autoPip()));
        kioskAutoStream(o.optBoolean("kioskAutoStream", kioskAutoStream()));
        kioskAutoRecord(o.optBoolean("kioskAutoRecord", kioskAutoRecord()));
        kioskOnBoot(o.optBoolean("kioskOnBoot", kioskOnBoot()));
        kioskDark(o.optBoolean("kioskDark", kioskDark()));
    }

    /** Kiosk: keep the screen dark (stream/record continue); a triple tap peeks, then it dims again. */
    public boolean kioskDark() {
        return sp.getBoolean("kioskDark", false);
    }

    public void kioskDark(boolean v) {
        sp.edit().putBoolean("kioskDark", v).apply();
    }
}
