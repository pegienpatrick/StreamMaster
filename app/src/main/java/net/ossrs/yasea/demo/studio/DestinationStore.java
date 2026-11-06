package net.ossrs.yasea.demo.studio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Saved streaming destinations. Stored in the app's private preferences (the manifest
 * disables cloud backup so stream keys are not copied off the phone).
 */
public final class DestinationStore {
    private static final String KEY = "destinations_v1";
    private static final String ACTIVE = "destination_active";

    private final SharedPreferences sp;

    public DestinationStore(Context c) {
        sp = c.getSharedPreferences("streammaster", Context.MODE_PRIVATE);
    }

    /** One-time: turn the old single "stream URL" setting into a Custom destination. */
    public void migrateLegacy(Prefs prefs) {
        if (!list().isEmpty()) {
            return;
        }
        String old = prefs.url();
        if (old != null && (old.startsWith("rtmp://") || old.startsWith("rtmps://"))) {
            Destination d = new Destination();
            d.name = "My stream";
            d.platform = Destination.Platform.CUSTOM;
            d.server = old;
            save(d);
            setActive(d.id);
        }
    }

    public List<Destination> list() {
        List<Destination> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Destination d = new Destination();
                d.id = o.getString("id");
                d.name = o.optString("name", "Stream");
                d.platform = Destination.Platform.of(o.optString("platform"));
                d.server = o.optString("server", "");
                d.key = o.optString("key", "");
                out.add(d);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public void save(Destination d) {
        if (d.id == null) {
            d.id = UUID.randomUUID().toString();
        }
        List<Destination> all = list();
        boolean found = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(d.id)) {
                all.set(i, d);
                found = true;
            }
        }
        if (!found) {
            all.add(d);
        }
        write(all);
        if (active() == null) {
            setActive(d.id);
        }
    }

    public void delete(String id) {
        List<Destination> all = list();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).id.equals(id)) {
                all.remove(i);
            }
        }
        write(all);
        if (id.equals(sp.getString(ACTIVE, null))) {
            sp.edit().remove(ACTIVE).apply();
        }
    }

    private void write(List<Destination> all) {
        JSONArray a = new JSONArray();
        try {
            for (Destination d : all) {
                a.put(new JSONObject().put("id", d.id).put("name", d.name).put("platform", d.platform.name())
                    .put("server", d.server).put("key", d.key));
            }
        } catch (JSONException ignored) {
        }
        sp.edit().putString(KEY, a.toString()).apply();
    }

    public void setActive(String id) {
        sp.edit().putString(ACTIVE, id).apply();
    }

    /** The selected destination, or the first one, or null. */
    public Destination active() {
        String id = sp.getString(ACTIVE, null);
        List<Destination> all = list();
        for (Destination d : all) {
            if (d.id.equals(id)) {
                return d;
            }
        }
        return all.isEmpty() ? null : all.get(0);
    }

    /** Full publish URL of the active destination, or null if none/unusable. */
    public String activeUrl() {
        Destination d = active();
        return d == null ? null : d.url();
    }
}
