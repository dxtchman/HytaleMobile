// Copyright (C) 2026 Hyprism Launcher
// SPDX-License-Identifier: GPL-3.0-only

package org.hyprism.game;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * The phone options, inside the game: a native panel drawn over it, opened from the gear in the touch
 * controls' top bar. The game's own settings screen is the client's and cannot take new entries, so
 * the options that only exist on the phone live here instead of in the launcher.
 */
final class GameSettingsPanel extends FrameLayout {
    private static final int BG = 0xFF16181D, TEXT = 0xFFE9ECF2, HINT = 0xFF9AA0AB, ACCENT = 0xFFF2B33D,
        CHIP = 0xFF262A32, LINE = 0xFF2C3038;

    private final HytaleActivity activity;
    private final GamepadOverlay pad;
    private final LinearLayout card;
    private final TextView restartNote;
    private final Button restartButton;
    private final TextView[] chips = new TextView[GameSettings.RESOLUTIONS.length];
    private final TextView[] rateChips = new TextView[GameSettings.REFRESH_RATES.length + 1];
    private final int[] rateValues = new int[GameSettings.REFRESH_RATES.length + 1];
    private int rateCount;

    GameSettingsPanel(HytaleActivity activity, GamepadOverlay pad) {
        super(activity);
        this.activity = activity;
        this.pad = pad;
        setVisibility(GONE);
        setClickable(true); // swallows every touch, so nothing reaches the game or the controls
        setOnClickListener(v -> hide()); // a tap outside the card closes it
        setBackgroundColor(0xB3000000);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad16 = dp(20);
        card.setPadding(pad16, pad16, pad16, pad16);
        card.setBackground(round(BG, dp(16)));
        scroll.addView(card, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER);
        lp.topMargin = lp.bottomMargin = dp(12);
        addView(scroll, lp);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Phone settings", 20, TEXT, true);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button close = button("Close", false);
        close.setOnClickListener(v -> hide());
        header.addView(close);
        card.addView(header);

        // 3D resolution
        LinearLayout chipRow = new LinearLayout(activity);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < chips.length; i++) {
            int height = GameSettings.RESOLUTIONS[i];
            TextView chip = text(height == 0 ? "Game" : height + "p", 14, TEXT, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(10), dp(8), dp(10), dp(8));
            chip.setOnClickListener(v -> {
                GameSettings.setRenderHeight(activity, height);
                paintChips();
                refreshRestart();
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            cp.leftMargin = i == 0 ? 0 : dp(6);
            chipRow.addView(chip, cp);
            chips[i] = chip;
        }
        row("3D resolution",
            "Draws the world at this height and stretches it to the screen; the interface stays sharp. Lower is lighter. \"Game\" uses the game's own render scale.",
            chipRow, true);

        // Refresh rate: only the rates the display has a mode for, plus the system's own choice.
        LinearLayout rateRow = new LinearLayout(activity);
        rateRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int target : GameSettings.REFRESH_RATES) {
            android.view.Display.Mode mode = HytaleActivity.modeNear(activity, target);
            if (mode != null)
                addRateChip(rateRow, target, Math.round(mode.getRefreshRate()) + " Hz");
        }
        addRateChip(rateRow, 0, "System");
        row("Refresh rate",
            rateCount > 2
                ? "The game runs at the screen's rate. Higher is smoother but heats the phone and uses more battery; lower the 3D resolution if it cannot keep up. Battery mode overrides this."
                : "This screen only offers 60 Hz right now. On Samsung phones, set Settings > Display > Motion smoothness to Adaptive for higher rates.",
            rateRow, true);

        row("Battery mode",
            "Plays at about 45 FPS (30 if the screen has no such mode) instead of the refresh rate above: runs cooler and uses less battery.",
            toggle(GameSettings.batterySaver(activity), on -> {
                GameSettings.setBatterySaver(activity, on);
                refreshRestart();
            }), false);

        row("Lighter textures",
            "Loads large textures at half size: less video memory, slightly less sharp up close. Helps on servers with many mod textures.",
            toggle(GameSettings.halfTextures(activity), on -> {
                GameSettings.setHalfTextures(activity, on);
                refreshRestart();
            }), false);

        row("Pre-generate new worlds",
            "Before you play a new world, generates 1 km around you so exploring later is lighter. Takes a few minutes and can be skipped.",
            toggle(GameSettings.pregenerate(activity), on -> GameSettings.setPregenerate(activity, on)), false);

        row("Tap to act (Bedrock style)",
            "The crosshair stays in the middle. Tap anywhere to use or place at the crosshair; hold to break or attack; drag to move the camera. The attack button is hidden, the block button stays. The stick sits in the lower left corner.",
            toggle(GameSettings.touchAim(activity), on -> {
                GameSettings.setTouchAim(activity, on);
                pad.reloadSettings();
            }), false);

        SeekBar size = new SeekBar(activity);
        size.setMax(100); // 60% to 160%
        size.setProgress(Math.round((GameSettings.controlSize(activity) - 0.6f) * 100));
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser)
                    return;
                GameSettings.setControlSize(activity, 0.6f + progress / 100f);
                pad.reloadSettings();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        row("Control size", "Makes every on-screen button bigger or smaller at once.", size, true);

        Button edit = button("Control position and opacity…", true);
        edit.setOnClickListener(v -> {
            hide();
            pad.startEditor();
        });
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        ep.topMargin = dp(14);
        card.addView(edit, ep);

        restartNote = text("Resolution, battery mode and textures apply after restarting the game.", 13, ACCENT, false);
        restartNote.setPadding(0, dp(14), 0, dp(6));
        card.addView(restartNote);
        restartButton = button("Restart the game now", true);
        restartButton.setOnClickListener(v -> activity.restartGame());
        card.addView(restartButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        paintChips();
        paintRateChips();
        refreshRestart();
    }

    void show() {
        paintChips();
        paintRateChips();
        refreshRestart();
        pad.panelOpened();
        setVisibility(VISIBLE);
    }

    void hide() {
        setVisibility(GONE);
        pad.panelClosed();
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    private void refreshRestart() {
        boolean needed = GameSettings.restartNeeded(activity);
        restartNote.setText(needed
            ? "You changed something that applies after restarting the game."
            : "Resolution, battery mode and textures apply after restarting the game.");
        restartNote.setTextColor(needed ? ACCENT : HINT);
        restartButton.setVisibility(needed ? VISIBLE : GONE);
    }

    private void addRateChip(LinearLayout rowView, int hz, String label) {
        int index = rateCount++;
        TextView chip = text(label, 14, TEXT, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(10), dp(8), dp(10), dp(8));
        chip.setOnClickListener(v -> {
            GameSettings.setRefreshRate(activity, hz);
            activity.applyRefreshRate();
            paintRateChips();
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        cp.leftMargin = index == 0 ? 0 : dp(6);
        rowView.addView(chip, cp);
        rateChips[index] = chip;
        rateValues[index] = hz;
    }

    private void paintRateChips() {
        int selected = GameSettings.refreshRate(activity);
        for (int i = 0; i < rateCount; i++) {
            boolean on = rateValues[i] == selected;
            rateChips[i].setBackground(round(on ? ACCENT : CHIP, dp(8)));
            rateChips[i].setTextColor(on ? 0xFF141409 : TEXT);
        }
    }

    private void paintChips() {
        int selected = GameSettings.renderHeight(activity);
        for (int i = 0; i < chips.length; i++) {
            boolean on = GameSettings.RESOLUTIONS[i] == selected;
            chips[i].setBackground(round(on ? ACCENT : CHIP, dp(8)));
            chips[i].setTextColor(on ? 0xFF141409 : TEXT);
        }
    }

    // ---- small view helpers ---------------------------------------------------------------------

    private interface OnChange {
        void changed(boolean on);
    }

    // A button that reads "On" or "Off": the framework Switch is nearly invisible on the dark card.
    private TextView toggle(boolean on, OnChange change) {
        TextView t = text("", 15, TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(120));
        t.setPadding(dp(18), dp(10), dp(18), dp(10));
        boolean[] state = { on };
        Runnable paint = () -> {
            t.setText(state[0] ? "On" : "Off");
            t.setTextColor(state[0] ? 0xFF141409 : TEXT);
            t.setBackground(round(state[0] ? ACCENT : CHIP, dp(10)));
        };
        paint.run();
        t.setOnClickListener(v -> {
            state[0] = !state[0];
            paint.run();
            change.changed(state[0]);
        });
        return t;
    }

    /** A label and hint with the control below (wide controls) or beside (switches). */
    private void row(String label, String hint, View control, boolean below) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(below ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        row.setGravity(below ? Gravity.START : Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(14), 0, dp(14));
        LinearLayout words = new LinearLayout(activity);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(label, 16, TEXT, true));
        TextView h = text(hint, 12, HINT, false);
        h.setPadding(0, dp(2), 0, below ? dp(8) : 0);
        words.addView(h);
        if (below) {
            row.addView(words);
            row.addView(control, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.leftMargin = dp(16);
            row.addView(control, cp);
        }
        View line = new View(activity);
        line.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.topMargin = dp(8);
        card.addView(line, lp);
        card.addView(row);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold)
            t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String s, boolean accent) {
        Button b = new Button(activity);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(accent ? 0xFF141409 : TEXT);
        b.setBackground(round(accent ? ACCENT : CHIP, dp(10)));
        return b;
    }

    private static GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
