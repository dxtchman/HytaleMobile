// Copyright (C) 2026 Hyprism Launcher
// SPDX-License-Identifier: GPL-3.0-only

package org.hyprism.game;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * The launcher's Play button lands here, in the game's process: it prepares the world server (see
 * {@link ServerSetup}), which takes a moment only after the app or the game was updated, and then
 * opens {@link HytaleActivity} with the launcher's arguments.
 */
public class SetupActivity extends Activity {
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setBackgroundColor(Color.rgb(16, 18, 24));
        ProgressBar spinner = new ProgressBar(this);
        layout.addView(spinner);
        status = new TextView(this);
        status.setTextColor(Color.rgb(220, 224, 232));
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setPadding(48, 32, 48, 0);
        status.setText("Preparing the game…");
        layout.addView(status);
        setContentView(layout);
        hideSystemBars();

        String gameDir = getIntent() != null ? getIntent().getStringExtra(HytaleActivity.GAME_DIR_EXTRA) : null;
        ServerSetup setup = new ServerSetup(this, message -> runOnUiThread(() -> status.setText(message)), gameDir);
        if (!setup.client().isFile()) {
            spinner.setVisibility(ProgressBar.GONE);
            status.setText("Hytale is not installed yet.\nOpen the launcher and tap Install.");
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                setup.run();
                applyRenderHeight();
                java.io.File world = worldToPregenerate();
                runOnUiThread(() -> {
                    if (world != null)
                        pregenerate(world);
                    else
                        startGame();
                });
            } catch (Exception e) {
                Log.e("HytaleSetup", "setup failed", e);
                runOnUiThread(() -> {
                    spinner.setVisibility(ProgressBar.GONE);
                    status.setText("Could not prepare the game's world server:\n" + e.getMessage());
                });
            }
        }, "HytaleSetup");
        worker.start();
    }

    /** The height the 3D view is drawn at comes from the in-game panel (0: the game's own render scale). */
    private static final java.util.regex.Pattern RENDER_SCALE =
        java.util.regex.Pattern.compile("(\"RenderScale\"\\s*:\\s*)(\\d+)");

    /**
     * Sets the game's RenderScale so its 3D view is drawn at the chosen height (the game takes values
     * below the 50% its menu offers), keeping the player's own value to put back when the launcher
     * setting returns to the game's.
     */
    private void applyRenderHeight() {
        int height = GameSettings.renderHeight(this);
        java.io.File settings = new java.io.File(getExternalFilesDir(null), "UserData/Settings.json");
        java.io.File saved = new java.io.File(getFilesDir(), "renderscale.player");
        try {
            if (!settings.isFile())
                return;
            String json = new String(java.nio.file.Files.readAllBytes(settings.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher m = RENDER_SCALE.matcher(json);
            if (!m.find())
                return;
            int current = Integer.parseInt(m.group(2));
            int wanted;
            if (height > 0) {
                android.graphics.Point size = new android.graphics.Point();
                getWindowManager().getDefaultDisplay().getRealSize(size);
                int screen = Math.min(size.x, size.y);
                wanted = Math.max(10, Math.min(100, Math.round(100f * height / screen)));
                if (!saved.exists())
                    java.nio.file.Files.write(saved.toPath(), String.valueOf(current).getBytes());
            } else if (saved.exists()) {
                wanted = Integer.parseInt(new String(java.nio.file.Files.readAllBytes(saved.toPath())).trim());
                saved.delete();
            } else {
                return;
            }
            if (wanted == current)
                return;
            json = m.replaceFirst("$1" + wanted);
            java.nio.file.Files.write(settings.toPath(), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Log.i("HytaleSetup", "render scale " + current + "% -> " + wanted + "%");
        } catch (Exception e) {
            Log.w("HytaleSetup", "could not set the render scale: " + e);
        }
    }

    /**
     * A world created since the setting was on that has not been pre-generated (or skipped). The first
     * time, every existing world is marked done: only new worlds are pre-generated.
     */
    private java.io.File worldToPregenerate() {
        if (!GameSettings.pregenerate(this))
            return null;
        java.io.File[] saves = new java.io.File(getExternalFilesDir(null), "UserData/Saves").listFiles();
        if (saves == null)
            return null;
        java.io.File baseline = new java.io.File(getFilesDir(), "pregenerate.baseline");
        boolean first = !baseline.exists();
        java.io.File pick = null;
        for (java.io.File save : saves) {
            boolean played = new java.io.File(save, "universe/worlds/default/config.json").isFile()
                && new java.io.File(save, "universe/players").isDirectory();
            if (!played || new java.io.File(save, PregenerateActivity.MARKER).exists())
                continue;
            if (first)
                PregenerateActivity.mark(save);
            else if (pick == null || save.lastModified() > pick.lastModified())
                pick = save;
        }
        if (first)
            try {
                baseline.createNewFile();
            } catch (java.io.IOException e) {
                Log.w("HytaleSetup", "could not record the pre-generation baseline: " + e);
            }
        return pick;
    }

    private void pregenerate(java.io.File world) {
        Intent pregen = new Intent(this, PregenerateActivity.class);
        if (getIntent() != null && getIntent().getExtras() != null)
            pregen.putExtras(getIntent().getExtras());
        pregen.putExtra(PregenerateActivity.AUTO_EXTRA, true);
        pregen.putExtra(PregenerateActivity.WORLD_EXTRA, world.getPath());
        startActivity(pregen);
        finish();
    }

    private void startGame() {
        Intent game = new Intent(this, HytaleActivity.class);
        if (getIntent() != null && getIntent().getExtras() != null)
            game.putExtras(getIntent().getExtras());
        game.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(game);
        finish();
    }

    private void hideSystemBars() {
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller == null)
            return;
        controller.hide(WindowInsets.Type.systemBars());
        controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }
}
