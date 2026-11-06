package net.ossrs.yasea.demo.studio;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import net.ossrs.yasea.engine.Camera2Source;
import net.ossrs.yasea.engine.ImageLayer;
import net.ossrs.yasea.engine.Layer;
import net.ossrs.yasea.engine.SourceLayer;
import net.ossrs.yasea.engine.StreamEngine;
import net.ossrs.yasea.engine.TextLayer;
import net.ossrs.yasea.engine.VideoSource;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Saves and restores the whole scene (layers, geometry, text styling, logos) in SharedPreferences. */
public final class SceneStore {
    private static final String TAG = "SceneStore";
    private static final String KEY = "scene_v1";

    private SceneStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("streammaster", Context.MODE_PRIVATE);
    }

    /** Write an overlay picture to app storage and return its path (null on failure). */
    public static String persistImage(Context c, Bitmap bmp) {
        try {
            File dir = new File(c.getFilesDir(), "overlays");
            dir.mkdirs();
            File f = new File(dir, UUID.randomUUID() + ".png");
            try (FileOutputStream out = new FileOutputStream(f)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            return f.getAbsolutePath();
        } catch (IOException e) {
            Log.w(TAG, "could not save overlay image", e);
            return null;
        }
    }

    public static void save(Context c, StreamEngine engine) {
        String json = snapshot(engine);
        if (json != null) {
            sp(c).edit().putString(KEY, json).apply();
        }
    }

    /** The current scene as JSON (screen capture is not included: it needs a fresh permission). */
    public static String snapshot(StreamEngine engine) {
        try {
            JSONObject root = new JSONObject();
            root.put("manual", engine.isManualLayout());
            JSONArray arr = new JSONArray();
            for (Layer l : engine.getLayers()) {
                JSONObject o = new JSONObject();
                o.put("name", l.name);
                o.put("x", l.x).put("y", l.y).put("w", l.w).put("h", l.h);
                o.put("opacity", l.opacity).put("visible", l.visible);
                if (l instanceof SourceLayer) {
                    SourceLayer sl = (SourceLayer) l;
                    if (sl.source.id.equals("screen")) {
                        continue; // screen capture needs a fresh permission grant every time
                    }
                    o.put("type", "source").put("key", sl.source.id);
                    o.put("fit", sl.fit).put("flip", sl.flip);
                    o.put("cl", sl.cropL).put("ct", sl.cropT).put("cr", sl.cropR).put("cb", sl.cropB);
                } else if (l instanceof TextLayer) {
                    TextLayer t = (TextLayer) l;
                    o.put("type", "text").put("text", t.text);
                    o.put("color", t.textColor).put("bg", t.bgColor).put("size", t.sizeFrac);
                    o.put("bold", t.bold).put("marquee", t.marquee).put("speed", t.speed).put("shadow", t.shadow);
                } else if (l instanceof ImageLayer) {
                    ImageLayer il = (ImageLayer) l;
                    if (il.path == null) {
                        continue;
                    }
                    o.put("type", "image").put("path", il.path);
                } else {
                    continue;
                }
                arr.put(o);
            }
            root.put("layers", arr);
            return root.toString();
        } catch (JSONException e) {
            Log.w(TAG, "save failed", e);
            return null;
        }
    }

    public static boolean hasSaved(Context c) {
        return sp(c).getString(KEY, null) != null;
    }

    /**
     * Rebuild the saved scene into the (empty) engine. Cameras are re-opened, text and
     * images recreated. Returns true if at least one layer was restored.
     */
    public static boolean restore(Context c, StreamEngine engine, int camW, int camH, int fps, int rotation) {
        return restoreFrom(c, sp(c).getString(KEY, null), engine, camW, camH, fps, rotation);
    }

    public static boolean restoreFrom(Context c, String json, StreamEngine engine, int camW, int camH,
                                      int fps, int rotation) {
        if (json == null) {
            return false;
        }
        try {
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.getJSONArray("layers");
            List<Camera2Source.Info> cams = Camera2Source.listCameras(c);
            List<Layer> ordered = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Layer made = null;
                switch (o.getString("type")) {
                    case "source": {
                        Camera2Source.Info info = null;
                        for (Camera2Source.Info ci : cams) {
                            if (ci.key().equals(o.getString("key"))) {
                                info = ci;
                            }
                        }
                        if (info == null) {
                            break; // that camera is gone
                        }
                        VideoSource src = new Camera2Source(c, info, camW, camH, fps, rotation);
                        engine.addSource(src);
                        SourceLayer sl = engine.layerFor(src);
                        if (sl != null) {
                            sl.fit = o.optInt("fit", sl.fit);
                            sl.flip = o.optBoolean("flip", false);
                            sl.cropL = (float) o.optDouble("cl", 0);
                            sl.cropT = (float) o.optDouble("ct", 0);
                            sl.cropR = (float) o.optDouble("cr", 0);
                            sl.cropB = (float) o.optDouble("cb", 0);
                            made = sl;
                        }
                        break;
                    }
                    case "text": {
                        TextLayer t = new TextLayer(o.optString("text", ""));
                        t.textColor = o.optInt("color", t.textColor);
                        t.bgColor = o.optInt("bg", t.bgColor);
                        t.sizeFrac = (float) o.optDouble("size", t.sizeFrac);
                        t.bold = o.optBoolean("bold", true);
                        t.marquee = o.optBoolean("marquee", false);
                        t.speed = (float) o.optDouble("speed", t.speed);
                        t.shadow = o.optBoolean("shadow", true);
                        made = t;
                        break;
                    }
                    case "image": {
                        Bitmap bmp = BitmapFactory.decodeFile(o.getString("path"));
                        if (bmp != null) {
                            ImageLayer il = new ImageLayer(bmp, o.optString("name", "Logo"));
                            il.path = o.getString("path");
                            made = il;
                        }
                        break;
                    }
                    default:
                        break;
                }
                if (made == null) {
                    continue;
                }
                made.name = o.optString("name", made.name);
                made.setRect((float) o.optDouble("x", 0), (float) o.optDouble("y", 0),
                    (float) o.optDouble("w", 1), (float) o.optDouble("h", 1));
                made.opacity = (float) o.optDouble("opacity", 1);
                made.visible = o.optBoolean("visible", true);
                if (!(made instanceof SourceLayer)) {
                    engine.addLayer(made);
                    if (made instanceof TextLayer) {
                        // addLayer recomputed the box from the text; restore the saved placement
                        made.setRect((float) o.optDouble("x", 0), (float) o.optDouble("y", 0),
                            (float) o.optDouble("w", made.w), (float) o.optDouble("h", made.h));
                    }
                }
                ordered.add(made);
            }
            if (root.optBoolean("manual", false)) {
                engine.markManualLayout();
            }
            engine.setLayerOrder(ordered);
            return !ordered.isEmpty();
        } catch (JSONException | RuntimeException e) {
            Log.w(TAG, "restore failed", e);
            return false;
        }
    }
}
