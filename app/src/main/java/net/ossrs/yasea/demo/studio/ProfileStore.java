package net.ossrs.yasea.demo.studio;

import android.content.Context;
import android.content.SharedPreferences;

import net.ossrs.yasea.engine.StreamEngine;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Named profiles: a snapshot of the settings plus the whole scene (cameras, text, logos,
 * layout). Stored as JSON in SharedPreferences; can be exported and imported as text.
 */
public final class ProfileStore {
    private static final String KEY = "profiles_v1";
    private static final String ACTIVE = "profile_active";

    private final SharedPreferences sp;

    public ProfileStore(Context c) {
        sp = c.getSharedPreferences("streammaster", Context.MODE_PRIVATE);
    }

    private JSONObject all() {
        try {
            return new JSONObject(sp.getString(KEY, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public List<String> names() {
        List<String> out = new ArrayList<>();
        Iterator<String> it = all().keys();
        while (it.hasNext()) {
            out.add(it.next());
        }
        java.util.Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public String active() {
        return sp.getString(ACTIVE, null);
    }

    /** Save (or overwrite) a profile from the current settings and live scene. */
    public boolean save(String name, Prefs prefs, StreamEngine engine) {
        try {
            JSONObject all = all();
            JSONObject p = new JSONObject();
            p.put("settings", prefs.toJson(true));
            String scene = SceneStore.snapshot(engine);
            p.put("scene", scene == null ? JSONObject.NULL : new JSONObject(scene));
            p.put("saved", System.currentTimeMillis());
            all.put(name, p);
            sp.edit().putString(KEY, all.toString()).putString(ACTIVE, name).apply();
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    public void delete(String name) {
        JSONObject all = all();
        all.remove(name);
        SharedPreferences.Editor e = sp.edit().putString(KEY, all.toString());
        if (name.equals(active())) {
            e.remove(ACTIVE);
        }
        e.apply();
    }

    /** Apply a profile's settings; returns its scene JSON (or null) for the caller to rebuild. */
    public String load(String name, Prefs prefs) {
        try {
            JSONObject p = all().getJSONObject(name);
            prefs.fromJson(p.getJSONObject("settings"));
            sp.edit().putString(ACTIVE, name).apply();
            JSONObject scene = p.optJSONObject("scene");
            return scene == null ? null : scene.toString();
        } catch (JSONException e) {
            return null;
        }
    }

    /** One profile as shareable text. The stream URL (it contains your key) is left out. */
    public String export(String name) {
        try {
            JSONObject p = new JSONObject(all().getJSONObject(name).toString());
            p.getJSONObject("settings").remove("url");
            JSONObject wrap = new JSONObject();
            wrap.put("streammaster_profile", name);
            wrap.put("data", p);
            return wrap.toString(2);
        } catch (JSONException e) {
            return null;
        }
    }

    /** Import text produced by {@link #export}; returns the profile name or null. */
    public String importText(String text) {
        try {
            JSONObject wrap = new JSONObject(text.trim());
            String name = wrap.getString("streammaster_profile");
            JSONObject data = wrap.getJSONObject("data");
            JSONObject all = all();
            String unique = name;
            for (int i = 2; all.has(unique); i++) {
                unique = name + " (" + i + ")";
            }
            all.put(unique, data);
            sp.edit().putString(KEY, all.toString()).apply();
            return unique;
        } catch (JSONException e) {
            return null;
        }
    }
}
