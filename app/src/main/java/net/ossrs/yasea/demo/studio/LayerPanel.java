package net.ossrs.yasea.demo.studio;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import net.ossrs.yasea.engine.BitmapLayer;
import net.ossrs.yasea.engine.ImageLayer;
import net.ossrs.yasea.engine.Layer;
import net.ossrs.yasea.engine.LayoutMode;
import net.ossrs.yasea.engine.SourceLayer;
import net.ossrs.yasea.engine.StreamEngine;
import net.ossrs.yasea.engine.TextLayer;

import java.util.List;

/** Bottom sheet to manage the scene: add things, reorder, and edit the selected layer. */
public final class LayerPanel {

    public interface Host {
        void addSourceRequested();

        void addTextRequested(boolean ticker);

        void addImageRequested();

        void layerSelected(Layer l);

        void panelClosed();
    }

    private final Activity activity;
    private final StreamEngine engine;
    private final Host host;
    private BottomSheetDialog dialog;
    private LinearLayout list;
    private LinearLayout props;
    private Layer selected;
    private final float d;

    public LayerPanel(Activity a, StreamEngine e, Host h) {
        activity = a;
        engine = e;
        host = h;
        d = a.getResources().getDisplayMetrics().density;
    }

    public void show(Layer select) {
        selected = select;
        dialog = new BottomSheetDialog(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(20));

        TextView title = text("Scene", 18, true);
        root.addView(title);

        // ---- add row
        HorizontalScrollView hs = new HorizontalScrollView(activity);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout add = row();
        add.addView(chip("+ Camera / screen", v -> {
            dialog.dismiss();
            host.addSourceRequested();
        }));
        add.addView(chip("+ Title", v -> {
            dialog.dismiss();
            host.addTextRequested(false);
        }));
        add.addView(chip("+ Ticker", v -> {
            dialog.dismiss();
            host.addTextRequested(true);
        }));
        add.addView(chip("+ Logo / image", v -> {
            dialog.dismiss();
            host.addImageRequested();
        }));
        hs.addView(add);
        root.addView(hs);

        // ---- presets
        root.addView(label("Arrange cameras"));
        HorizontalScrollView hp = new HorizontalScrollView(activity);
        hp.setHorizontalScrollBarEnabled(false);
        LinearLayout pre = row();
        String[] names = {"Single", "Picture-in-picture", "Side by side", "Stacked", "2 x 2 grid"};
        LayoutMode[] modes = {LayoutMode.SINGLE, LayoutMode.PIP, LayoutMode.SPLIT_H, LayoutMode.SPLIT_V, LayoutMode.GRID_2X2};
        for (int i = 0; i < names.length; i++) {
            final LayoutMode m = modes[i];
            pre.addView(chip(names[i], v -> {
                engine.setLayout(m);
                rebuildList();
            }));
        }
        pre.addView(chip("Swap main", v -> {
            engine.swapMain();
            rebuildList();
        }));
        hp.addView(pre);
        root.addView(hp);

        // ---- layers (top first)
        root.addView(label("Layers (top to bottom)"));
        list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        props = new LinearLayout(activity);
        props.setOrientation(LinearLayout.VERTICAL);
        root.addView(props);

        android.widget.ScrollView sv = new android.widget.ScrollView(activity);
        sv.addView(root);
        dialog.setContentView(sv);
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        dialog.getBehavior().setSkipCollapsed(true);
        rebuildList();
        dialog.setOnDismissListener(d -> host.panelClosed());
        dialog.show();
    }

    private void rebuildList() {
        list.removeAllViews();
        List<Layer> ls = engine.getLayers();
        if (ls.isEmpty()) {
            list.addView(text("Nothing here yet. Add a camera, title or logo.", 13, false));
        }
        for (int i = ls.size() - 1; i >= 0; i--) {
            final Layer l = ls.get(i);
            LinearLayout r = row();
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(4), dp(4), dp(4), dp(4));
            if (l == selected) {
                r.setBackgroundColor(0x336C5CE7);
            }
            CheckBox cb = new CheckBox(activity);
            cb.setChecked(l.visible);
            cb.setOnCheckedChangeListener((b, on) -> {
                l.visible = on;
                engine.commitScene();
            });
            r.addView(cb);
            TextView t = text(icon(l) + "  " + l.name, 15, l == selected);
            t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            r.addView(t);
            r.setOnClickListener(v -> {
                selected = l;
                host.layerSelected(l);
                rebuildList();
            });
            list.addView(r);
        }
        buildProps();
    }

    private static String icon(Layer l) {
        switch (l.kind()) {
            case "source":
                return "🎥";
            case "text":
                return ((TextLayer) l).marquee ? "➡" : "T";
            default:
                return "🖼";
        }
    }

    // ------------------------------------------------------------ properties of the selected layer

    private void buildProps() {
        props.removeAllViews();
        final Layer l = selected;
        if (l == null || !engine.getLayers().contains(l)) {
            return;
        }
        props.addView(label("Edit: " + l.name));

        // order + delete
        LinearLayout ord = row();
        ord.addView(chip("Bring to front", v -> {
            engine.bringToFront(l);
            rebuildList();
        }));
        ord.addView(chip("Forward", v -> {
            engine.moveLayer(l, 1);
            rebuildList();
        }));
        ord.addView(chip("Backward", v -> {
            engine.moveLayer(l, -1);
            rebuildList();
        }));
        ord.addView(chip("Send to back", v -> {
            engine.sendToBack(l);
            rebuildList();
        }));
        ord.addView(chip("Delete", v -> {
            engine.removeLayer(l);
            selected = null;
            host.layerSelected(null);
            rebuildList();
        }));
        HorizontalScrollView ho = new HorizontalScrollView(activity);
        ho.setHorizontalScrollBarEnabled(false);
        ho.addView(ord);
        props.addView(ho);
        if (l instanceof SourceLayer) {
            buildSourceProps((SourceLayer) l);
        }

        props.addView(slider("Opacity", (int) (l.opacity * 100), 100, v -> l.opacity = v / 100f));

        if (l instanceof TextLayer) {
            buildTextProps((TextLayer) l);
        } else if (l instanceof ImageLayer) {
            props.addView(slider("Size", (int) (l.w * 100), 100, v -> {
                engine.markManualLayout();
                l.w = Math.max(0.03f, v / 100f);
                ((BitmapLayer) l).onOutputSize(engine.getOutputWidth(), engine.getOutputHeight());
            }));
        }
    }

    private void buildSourceProps(final SourceLayer l) {
        props.addView(label("How the picture fills its box"));
        LinearLayout fit = row();
        fit.addView(chip("Crop to fill", l.fit == SourceLayer.FILL, v -> {
            l.fit = SourceLayer.FILL;
            buildProps();
        }));
        fit.addView(chip("Fit whole image", l.fit == SourceLayer.FIT, v -> {
            l.fit = SourceLayer.FIT;
            buildProps();
        }));
        fit.addView(chip("Stretch", l.fit == SourceLayer.STRETCH, v -> {
            l.fit = SourceLayer.STRETCH;
            buildProps();
        }));
        HorizontalScrollView hf = new HorizontalScrollView(activity);
        hf.setHorizontalScrollBarEnabled(false);
        hf.addView(fit);
        props.addView(hf);

        LinearLayout size = row();
        size.addView(chip("Full frame", v -> {
            engine.markManualLayout();
            l.setRect(0, 0, 1, 1);
        }));
        size.addView(chip("Actual size 1:1", v -> {
            engine.markManualLayout();
            float ow = engine.getOutputWidth(), oh = engine.getOutputHeight();
            boolean swap = l.source.getRotationDegrees() % 180 != 0;
            float bw = swap ? l.source.getBaseHeight() : l.source.getBaseWidth();
            float bh = swap ? l.source.getBaseWidth() : l.source.getBaseHeight();
            float w = bw / ow, h = bh / oh;
            l.setRect((1 - w) / 2f, (1 - h) / 2f, w, h);
            l.fit = SourceLayer.STRETCH;
        }));
        size.addView(chip("Mirror", v -> l.flip = !l.flip));
        size.addView(chip("Reset crop", v -> {
            l.resetCrop();
            buildProps();
        }));
        props.addView(size);

        props.addView(label("Crop"));
        props.addView(slider("Left", (int) (l.cropL * 100), 60, v -> l.cropL = v / 100f));
        props.addView(slider("Top", (int) (l.cropT * 100), 60, v -> l.cropT = v / 100f));
        props.addView(slider("Right", (int) (l.cropR * 100), 60, v -> l.cropR = v / 100f));
        props.addView(slider("Bottom", (int) (l.cropB * 100), 60, v -> l.cropB = v / 100f));
    }

    private void buildTextProps(final TextLayer t) {
        EditText et = new EditText(activity);
        et.setText(t.text);
        et.setHint("Text");
        et.setSingleLine(false);
        et.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                t.text = s.toString();
                t.name = s.length() == 0 ? "Text" : s.toString().split("\n")[0];
                t.recompute(engine.getOutputWidth(), engine.getOutputHeight());
            }
        });
        props.addView(et);

        props.addView(slider("Text size", (int) (t.sizeFrac * 400), 100, v -> {
            t.sizeFrac = Math.max(0.02f, v / 400f);
            t.recompute(engine.getOutputWidth(), engine.getOutputHeight());
        }));

        props.addView(label("Text colour"));
        LinearLayout tc = row();
        int[] cols = {Color.WHITE, 0xFFFFE14D, 0xFFFF4D6A, 0xFF4DD2FF, 0xFF2ED47A, Color.BLACK};
        for (final int c : cols) {
            tc.addView(swatch(c, v -> {
                t.textColor = c;
                t.invalidate();
            }));
        }
        props.addView(tc);

        props.addView(label(t.marquee ? "Bar colour" : "Background"));
        LinearLayout bc = row();
        int[] bgs = {0, 0x99000000, 0xCCFF3B58, 0xCC6C5CE7, 0xCC1E7A4F, 0xFFFFFFFF};
        for (final int c : bgs) {
            bc.addView(swatch(c, v -> {
                t.bgColor = c;
                t.invalidate();
            }));
        }
        props.addView(bc);

        MaterialSwitch bold = sw("Bold", t.bold, on -> {
            t.bold = on;
            t.recompute(engine.getOutputWidth(), engine.getOutputHeight());
        });
        props.addView(bold);
        MaterialSwitch mq = sw("Scrolling ticker (marquee)", t.marquee, on -> {
            t.marquee = on;
            if (on) {
                t.x = 0f;
                t.w = 1f;
                t.y = 0.9f;
            }
            t.recompute(engine.getOutputWidth(), engine.getOutputHeight());
            buildProps();
        });
        props.addView(mq);
        if (t.marquee) {
            props.addView(slider("Scroll speed", (int) (t.speed * 400), 100, v -> t.speed = Math.max(0.02f, v / 400f)));
        }
    }

    // ------------------------------------------------------------ small view helpers

    private interface IntCb {
        void on(int v);
    }

    private interface BoolCb {
        void on(boolean v);
    }

    private View slider(String name, int value, int max, final IntCb cb) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = text(name, 13, false);
        l.setLayoutParams(new LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));
        r.addView(l);
        SeekBar sb = new SeekBar(activity);
        sb.setMax(max);
        sb.setProgress(Math.min(max, Math.max(0, value)));
        sb.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) {
                    cb.on(p);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        r.addView(sb);
        return r;
    }

    private MaterialSwitch sw(String label, boolean on, final BoolCb cb) {
        MaterialSwitch s = new MaterialSwitch(activity);
        s.setText(label);
        s.setChecked(on);
        s.setOnCheckedChangeListener((b, v) -> cb.on(v));
        return s;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(activity);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(0xFFECEEF4);
        if (bold) {
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 12, false);
        t.setTextColor(0xFF9AA1B2);
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    private View chip(String label, View.OnClickListener l) {
        return chip(label, false, l);
    }

    /** Highlighted (filled) when it is the active choice. */
    private View chip(String label, boolean active, View.OnClickListener l) {
        MaterialButton b = new MaterialButton(activity, null, active
            ? com.google.android.material.R.attr.materialButtonStyle
            : com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private View swatch(int color, View.OnClickListener l) {
        View v = new View(activity);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor((color >>> 24) == 0 ? 0x00000000 : color);
        g.setStroke(dp(2), (color >>> 24) == 0 ? 0xFFFF4D6A : 0xFF9AA1B2);
        v.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
        lp.rightMargin = dp(10);
        v.setLayoutParams(lp);
        v.setOnClickListener(l);
        return v;
    }

    private int dp(int v) {
        return Math.round(v * d);
    }
}
