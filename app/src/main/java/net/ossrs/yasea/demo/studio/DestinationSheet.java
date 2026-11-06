package net.ossrs.yasea.demo.studio;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/** "Go live to..." sheet: pick a platform, set the server and your stream key, manage several destinations. */
public final class DestinationSheet {

    public interface Host {
        void destinationChanged();

        void goLiveRequested();
    }

    private final Activity activity;
    private final DestinationStore store;
    private final Host host;
    private final float d;
    private BottomSheetDialog sheet;
    private LinearLayout listBox;

    public DestinationSheet(Activity a, DestinationStore s, Host h) {
        activity = a;
        store = s;
        host = h;
        d = a.getResources().getDisplayMetrics().density;
    }

    // ------------------------------------------------------------ main sheet

    public void show() {
        sheet = new BottomSheetDialog(activity);
        LinearLayout root = col();
        root.setPadding(dp(18), dp(14), dp(18), dp(22));

        root.addView(text("Stream destination", 19, true, 0xFFECEEF4));
        root.addView(text("Choose where your stream goes. Your key stays on this phone.", 12, false, 0xFF9AA1B2));

        // platform tiles
        LinearLayout tiles = new LinearLayout(activity);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        tiles.setPadding(0, dp(14), 0, dp(6));
        for (final Destination.Platform p : Destination.Platform.values()) {
            tiles.addView(tile(p));
        }
        root.addView(tiles);

        root.addView(text("Saved destinations", 12, false, 0xFF9AA1B2));
        listBox = col();
        root.addView(listBox);

        MaterialButton add = new MaterialButton(activity, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle);
        add.setText("+ Add destination");
        add.setAllCaps(false);
        add.setOnClickListener(v -> edit(null, Destination.Platform.CUSTOM));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(10);
        root.addView(add, alp);

        ScrollView sv = new ScrollView(activity);
        sv.addView(root);
        sheet.setContentView(sv);
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        sheet.getBehavior().setSkipCollapsed(true);
        rebuild();
        sheet.show();
    }

    private void rebuild() {
        listBox.removeAllViews();
        List<Destination> all = store.list();
        Destination active = store.active();
        if (all.isEmpty()) {
            listBox.addView(text("Nothing saved yet. Tap a platform above to add your stream key.", 13, false, 0xFFECEEF4));
        }
        for (final Destination x : all) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(10), dp(6), dp(10));
            boolean isActive = active != null && active.id.equals(x.id);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14));
            bg.setColor(isActive ? 0x336C5CE7 : 0x14FFFFFF);
            if (isActive) {
                bg.setStroke(dp(2), 0xFF6C5CE7);
            }
            row.setBackground(bg);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(8);
            row.setLayoutParams(rlp);

            View dot = new View(activity);
            GradientDrawable dg = new GradientDrawable();
            dg.setShape(GradientDrawable.OVAL);
            dg.setColor(x.platform.color);
            dot.setBackground(dg);
            row.addView(dot, new LinearLayout.LayoutParams(dp(14), dp(14)));

            LinearLayout mid = col();
            mid.setPadding(dp(12), 0, dp(8), 0);
            mid.addView(text(x.name + (isActive ? "   ✓ selected" : ""), 15, true, 0xFFECEEF4));
            boolean ready = x.url() != null;
            mid.addView(text(x.platform.label + " · " + (x.host().isEmpty() ? "no server" : x.host())
                + " · " + x.maskedKey() + (ready ? "" : "  ⚠ incomplete"), 12, false,
                ready ? 0xFF9AA1B2 : 0xFFFFB020));
            row.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            MaterialButton editBtn = new MaterialButton(activity, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
            editBtn.setText("Edit");
            editBtn.setAllCaps(false);
            editBtn.setOnClickListener(v -> edit(x, x.platform));
            row.addView(editBtn);

            row.setOnClickListener(v -> {
                store.setActive(x.id);
                host.destinationChanged();
                rebuild();
            });
            listBox.addView(row);
        }

        // big action: go live with the selected one
        Destination a = store.active();
        if (a != null && a.url() != null) {
            MaterialButton go = new MaterialButton(activity);
            go.setText("Go live to " + a.name);
            go.setAllCaps(false);
            go.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFF3B58));
            go.setOnClickListener(v -> {
                sheet.dismiss();
                host.goLiveRequested();
            });
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
            glp.topMargin = dp(16);
            listBox.addView(go, glp);
        }
    }

    private View tile(final Destination.Platform p) {
        MaterialButton b = new MaterialButton(activity);
        b.setText(p.label);
        b.setAllCaps(false);
        b.setTextColor(p == Destination.Platform.KICK ? Color.BLACK : Color.WHITE);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(p.color));
        b.setCornerRadius(dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> {
            // use the saved destination for this platform if there is one, otherwise add it
            for (Destination x : store.list()) {
                if (x.platform == p) {
                    store.setActive(x.id);
                    host.destinationChanged();
                    rebuild();
                    return;
                }
            }
            edit(null, p);
        });
        return b;
    }

    // ------------------------------------------------------------ editor

    private Destination editing;
    private Destination.Platform editPlatform;

    private void edit(Destination existing, Destination.Platform preset) {
        editing = existing;
        editPlatform = existing != null ? existing.platform : preset;

        final LinearLayout box = col();
        box.setPadding(dp(22), dp(10), dp(22), 0);

        final EditText name = field("Name (for you)", InputType.TYPE_CLASS_TEXT);
        final EditText server = field("Server URL (rtmp:// or rtmps://)",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText key = field("Stream key", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final TextView help = text("", 12, false, 0xFF9AA1B2);
        final LinearLayout serverChips = new LinearLayout(activity);
        serverChips.setOrientation(LinearLayout.HORIZONTAL);

        if (existing != null) {
            name.setText(existing.name);
            server.setText(existing.server);
            key.setText(existing.key);
        }

        // platform chips
        final LinearLayout plat = new LinearLayout(activity);
        plat.setOrientation(LinearLayout.HORIZONTAL);
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            plat.removeAllViews();
            for (final Destination.Platform p : Destination.Platform.values()) {
                MaterialButton c = new MaterialButton(activity, null, p == editPlatform
                    ? com.google.android.material.R.attr.materialButtonStyle
                    : com.google.android.material.R.attr.materialButtonOutlinedStyle);
                c.setText(p.label);
                c.setAllCaps(false);
                if (p == editPlatform) {
                    c.setBackgroundTintList(android.content.res.ColorStateList.valueOf(p.color));
                    c.setTextColor(p == Destination.Platform.KICK ? Color.BLACK : Color.WHITE);
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(6);
                c.setLayoutParams(lp);
                c.setOnClickListener(v -> {
                    editPlatform = p;
                    if (server.getText().length() == 0 && p.servers.length > 0) {
                        server.setText(p.servers[0]);
                    }
                    if (name.getText().length() == 0) {
                        name.setText(p.label);
                    }
                    refresh[0].run();
                });
                plat.addView(c);
            }
            help.setText("Where to find your key: " + editPlatform.keyHelp);
            serverChips.removeAllViews();
            for (final String s : editPlatform.servers) {
                MaterialButton c = new MaterialButton(activity, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
                c.setText(s.startsWith("rtmps") ? "Secure (RTMPS)" : "Standard (RTMP)");
                c.setAllCaps(false);
                c.setOnClickListener(v -> server.setText(s));
                serverChips.addView(c);
            }
            if (editPlatform.servers.length == 0 && editPlatform == Destination.Platform.KICK) {
                server.setHint("Paste the Stream URL from your Kick dashboard");
            }
        };

        HorizontalScrollView hs = new HorizontalScrollView(activity);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(plat);
        box.addView(hs);
        box.addView(name);
        box.addView(server);
        HorizontalScrollView hs2 = new HorizontalScrollView(activity);
        hs2.setHorizontalScrollBarEnabled(false);
        hs2.addView(serverChips);
        box.addView(hs2);
        box.addView(key);

        // show / paste row
        LinearLayout tools = new LinearLayout(activity);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        final MaterialButton show = new MaterialButton(activity, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle);
        show.setText("Show key");
        show.setAllCaps(false);
        show.setOnClickListener(v -> {
            boolean hidden = (key.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0;
            key.setInputType(InputType.TYPE_CLASS_TEXT | (hidden
                ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            key.setSelection(key.getText().length());
            show.setText(hidden ? "Hide key" : "Show key");
        });
        MaterialButton paste = new MaterialButton(activity, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle);
        paste.setText("Paste");
        paste.setAllCaps(false);
        paste.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = cm == null ? null : cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                key.setText(clip.getItemAt(0).coerceToText(activity).toString().trim());
                key.setSelection(key.getText().length());
            }
        });
        tools.addView(show);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = dp(8);
        tools.addView(paste, plp);
        box.addView(tools);
        box.addView(help);
        box.addView(text("Never share your stream key. Anyone who has it can stream to your channel.", 11, false, 0xFFFFB020));

        refresh[0].run();
        if (existing == null && server.getText().length() == 0 && editPlatform.servers.length > 0) {
            server.setText(editPlatform.servers[0]);
            name.setText(editPlatform.label);
        }

        ScrollView sv = new ScrollView(activity);
        sv.addView(box);
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(activity)
            .setTitle(existing == null ? "Add destination" : "Edit destination")
            .setView(sv)
            .setPositiveButton("Save", (dlg, w) -> {
                String srv = server.getText().toString().trim();
                if (!(srv.startsWith("rtmp://") || srv.startsWith("rtmps://"))) {
                    toast("The server must start with rtmp:// or rtmps://");
                    return;
                }
                if (editPlatform != Destination.Platform.CUSTOM && key.getText().toString().trim().isEmpty()) {
                    toast("Enter your stream key to use " + editPlatform.label);
                    return;
                }
                Destination x = editing != null ? editing : new Destination();
                x.platform = editPlatform;
                x.name = name.getText().toString().trim().isEmpty() ? editPlatform.label : name.getText().toString().trim();
                x.server = srv;
                x.key = key.getText().toString().trim();
                store.save(x);
                store.setActive(x.id);
                host.destinationChanged();
                if (listBox != null) {
                    rebuild();
                }
            })
            .setNegativeButton("Cancel", null);
        if (existing != null) {
            b.setNeutralButton("Delete", (dlg, w) -> new MaterialAlertDialogBuilder(activity)
                .setTitle("Delete \"" + existing.name + "\"?")
                .setMessage("Its stream key is removed from this phone.")
                .setPositiveButton("Delete", (d2, w2) -> {
                    store.delete(existing.id);
                    host.destinationChanged();
                    if (listBox != null) {
                        rebuild();
                    }
                })
                .setNegativeButton("Keep", null)
                .show());
        }
        b.show();
    }

    // ------------------------------------------------------------ small helpers

    private void toast(String s) {
        android.widget.Toast.makeText(activity, s, android.widget.Toast.LENGTH_LONG).show();
    }

    private EditText field(String hint, int type) {
        EditText e = new EditText(activity);
        e.setHint(hint);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        e.setLayoutParams(lp);
        return e;
    }

    private LinearLayout col() {
        LinearLayout l = new LinearLayout(activity);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView text(String s, int sp, boolean bold, int color) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        t.setLayoutParams(lp);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * d);
    }
}
