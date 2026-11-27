// Copyright (C) 2026 Hyprism Launcher
// SPDX-License-Identifier: GPL-3.0-only

package org.hyprism.game;

import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Toast;

import org.libsdl.app.SDLActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Hosts the macOS Hytale client, loaded natively by libmain.so. The Hyprism
 * launcher passes the authenticated client arguments in an intent extra.
 */
public class HytaleActivity extends SDLActivity {
    static final String ARGUMENTS_EXTRA = "org.hyprism.game.ARGUMENTS";
    /** The launcher instance to run; libmain.so reads it as HYPRISM_GAME_DIR (default: the files folder). */
    static final String GAME_DIR_EXTRA = "org.hyprism.game.GAME_DIR";
    private boolean batterySaver;
    private GameSettingsPanel settingsPanel;
    private static volatile boolean running;

    /** Whether the game is open, so the world must not be pre-generated now. */
    static boolean isRunning() {
        return running;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        String gameDir = getIntent() != null ? getIntent().getStringExtra(GAME_DIR_EXTRA) : null;
        try {
            if (gameDir != null)
                android.system.Os.setenv("HYPRISM_GAME_DIR", gameDir, true);
            else
                android.system.Os.unsetenv("HYPRISM_GAME_DIR");
        } catch (android.system.ErrnoException e) {
            Log.w("HytaleHost", "could not pass the game folder: " + e);
        }
        // The phone options come from the in-game panel (GameSettingsPanel).
        GameSettings.snapshot(this);
        batterySaver = GameSettings.batterySaver(this);
        setEnv("HYPRISM_TEXTURE_HALF", GameSettings.halfTextures(this) ? "1" : null);
        setEnv("HYPRISM_SWAP_INTERVAL", null);
        sizeServerForMemory();
        super.onCreate(savedInstanceState);
        running = true;
        clearLeftoverPregeneration();
        // Draw under the camera cutout instead of leaving a black bar.
        getWindow().getAttributes().layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setDecorFitsSystemWindows(false);
        tunePerformance();
        installServerLauncher();

        // On-screen gamepad over the game; the saved on/off state is restored
        // once the client has had time to initialise SDL.
        GamepadOverlay gamepad = new GamepadOverlay(this);
        addContentView(gamepad, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        gamepad.postDelayed(gamepad::restore, 15000);

        // The settings panel sits above the controls; the gear in their top bar opens it.
        settingsPanel = new GameSettingsPanel(this, gamepad);
        addContentView(settingsPanel, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        gamepad.setOnOpenSettings(settingsPanel::show);
    }

    /**
     * Gives the local world server more room on phones with more memory, for worlds with many mods: the
     * heap ceiling (the wrapper's default is 1536M) and the JIT code cache, which a large mod set can
     * fill, after which the server's new code stops being compiled. The heap still starts at 512M and
     * only grows when the server needs it. env.txt, read later by the loader, wins over these values.
     */
    private void sizeServerForMemory() {
        android.app.ActivityManager.MemoryInfo memory = new android.app.ActivityManager.MemoryInfo();
        android.app.ActivityManager manager = getSystemService(android.app.ActivityManager.class);
        if (manager != null)
            manager.getMemoryInfo(memory);
        long gib = 1L << 30;
        String heap = null, opts = null;
        if (memory.totalMem >= 10 * gib) {
            heap = "3072M";
            opts = "-XX:ReservedCodeCacheSize=192m";
        } else if (memory.totalMem >= 7 * gib) {
            heap = "2048M";
            opts = "-XX:ReservedCodeCacheSize=128m";
        }
        setEnv("HYPRISM_SERVER_HEAP", heap);
        setEnv("HYPRISM_SERVER_OPTS", opts);
        Log.i("HytaleHost", "phone memory " + memory.totalMem / (1 << 20) + " MB, world server heap up to "
            + (heap != null ? heap : "the default"));
    }

    /**
     * Restarts the game, to apply the options that are read at start. RestartActivity (another process)
     * stops the world server so it saves, ends this process and starts SetupActivity again with the same
     * extras, which carry the account's session.
     */
    void restartGame() {
        Intent next = new Intent(this, SetupActivity.class);
        if (getIntent() != null && getIntent().getExtras() != null)
            next.putExtras(getIntent().getExtras());
        next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Intent restart = new Intent(this, RestartActivity.class);
        restart.putExtra(RestartActivity.PID_EXTRA, android.os.Process.myPid());
        restart.putExtra(RestartActivity.NEXT_EXTRA, next);
        restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(restart);
    }

    @Override
    public void onBackPressed() {
        if (settingsPanel != null && settingsPanel.isOpen()) {
            settingsPanel.hide();
            return;
        }
        super.onBackPressed();
    }

    /**
     * Keeps the phone out of the slow paths that were measured on a Galaxy S23: the battery saver
     * pinned the GPU at 220 MHz (out of 719) and cut the average frame rate by about a third, and a
     * hot SoC throttles it further. Nothing here changes what the game draws.
     */
    private void tunePerformance() {
        // Ask the vendor firmware for clocks it can hold, instead of short boosts that end in throttling.
        try {
            getWindow().setSustainedPerformanceMode(true);
        } catch (RuntimeException e) {
            Log.w("HytaleHost", "sustained performance mode unavailable: " + e);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // The refresh rate comes from the in-game panel (60 Hz by default: a steady 60 beat a 120 Hz panel
        // that dropped to 45 under load on the S23). Battery mode picks its own lower rate.
        if (batterySaver)
            applyBatterySaver();
        else
            applyRefreshRate();

        PowerManager power = getSystemService(PowerManager.class);
        if (power == null)
            return;
        if (power.isPowerSaveMode())
            Toast.makeText(this, "Battery saver is on: Hytale will run much slower. Turn it off for smooth play.",
                Toast.LENGTH_LONG).show();
        power.addThermalStatusListener(getMainExecutor(), status -> {
            Log.i("HytaleHost", "thermal status " + status);
            if (status >= PowerManager.THERMAL_STATUS_SEVERE)
                Toast.makeText(this, "The phone is hot and slowing down. Let it cool for a moment.",
                    Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Asks the display for the panel's refresh rate (GameSettings.refreshRate); VSync then paces the game
     * at it. HYPRISM_REFRESH in env.txt wins over the panel, and 0 leaves the rate to the system. Applies
     * at once, so the panel calls it when the option changes. Does nothing in battery mode.
     */
    void applyRefreshRate() {
        if (batterySaver)
            return;
        int hz = GameSettings.refreshRate(this);
        String value = envValue("HYPRISM_REFRESH");
        if (value != null) {
            try {
                hz = Math.round(Float.parseFloat(value.trim()));
            } catch (NumberFormatException e) {
                Log.w("HytaleHost", "ignoring a bad HYPRISM_REFRESH: " + e.getMessage());
            }
        }
        android.view.Display.Mode mode = hz > 0 ? modeNear(this, hz) : null;
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.preferredDisplayModeId = mode != null ? mode.getModeId() : 0;
        attributes.preferredRefreshRate = mode != null ? mode.getRefreshRate() : (hz > 0 ? hz : 0);
        getWindow().setAttributes(attributes);
        Log.i("HytaleHost", "refresh rate: asked for " + hz + " Hz, display mode "
            + (mode != null ? mode.getRefreshRate() + " Hz" : "left to the system"));
    }

    /** The display mode at the current resolution closest to hz, within 8 Hz (a 96 Hz mode serves 90), or null. */
    static android.view.Display.Mode modeNear(android.app.Activity activity, int hz) {
        android.view.Display display = activity.getWindowManager().getDefaultDisplay();
        android.view.Display.Mode current = display.getMode();
        android.view.Display.Mode best = null;
        for (android.view.Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() != current.getPhysicalWidth() || mode.getPhysicalHeight() != current.getPhysicalHeight())
                continue;
            float gap = Math.abs(mode.getRefreshRate() - hz);
            if (gap <= 8 && (best == null || gap < Math.abs(best.getRefreshRate() - hz)))
                best = mode;
        }
        return best;
    }

    /**
     * Battery mode: a display mode between 40 and 50 Hz when the panel has one (VSync then paces the
     * game there, smoothly), otherwise every other refresh of a 60 Hz mode, 30 FPS.
     */
    private void applyBatterySaver() {
        android.view.Display display = getWindowManager().getDefaultDisplay();
        android.view.Display.Mode current = display.getMode();
        android.view.Display.Mode best = null, sixty = null;
        for (android.view.Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() != current.getPhysicalWidth() || mode.getPhysicalHeight() != current.getPhysicalHeight())
                continue;
            float rate = mode.getRefreshRate();
            if (rate >= 40 && rate <= 50 && (best == null || rate < best.getRefreshRate()))
                best = mode;
            if (Math.abs(rate - 60) < 1)
                sixty = mode;
        }
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        if (best != null) {
            attributes.preferredDisplayModeId = best.getModeId();
            Log.i("HytaleHost", "battery mode: display at " + best.getRefreshRate() + " Hz");
        } else {
            if (sixty != null)
                attributes.preferredDisplayModeId = sixty.getModeId();
            attributes.preferredRefreshRate = 60f;
            setEnv("HYPRISM_SWAP_INTERVAL", "2");
            Log.i("HytaleHost", "battery mode: 30 FPS (no display mode near 45 Hz)");
        }
        getWindow().setAttributes(attributes);
    }

    private static void setEnv(String name, String value) {
        try {
            if (value != null)
                android.system.Os.setenv(name, value, true);
            else
                android.system.Os.unsetenv(name);
        } catch (android.system.ErrnoException e) {
            Log.w("HytaleHost", "could not set " + name + ": " + e);
        }
    }

    /** Brings files/java up to date when the game is opened without SetupActivity (adb, old shortcuts). */
    private void installServerLauncher() {
        new ServerSetup(this, message -> { }).installServerLauncher();
    }

    @Override
    protected void onDestroy() {
        running = false;
        super.onDestroy();
    }

    /**
     * PregenerateActivity sets ChunkConfig.PregenerateRegion only while it runs; if it was killed, the
     * region would stay and the server would generate it during play. Remove any that was left.
     */
    private void clearLeftoverPregeneration() {
        File[] saves = new File(getExternalFilesDir(null), "UserData/Saves").listFiles();
        if (saves == null)
            return;
        for (File save : saves) {
            File config = new File(save, "universe/worlds/default/config.json");
            try {
                if (!config.isFile())
                    continue;
                String text = new String(Files.readAllBytes(config.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                if (!text.contains("PregenerateRegion"))
                    continue;
                org.json.JSONObject json = new org.json.JSONObject(text);
                org.json.JSONObject chunk = json.optJSONObject("ChunkConfig");
                if (chunk != null) {
                    chunk.remove("PregenerateRegion");
                    Files.write(config.toPath(), json.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    Log.i("HytaleHost", "removed a leftover pre-generation region from " + save.getName());
                }
            } catch (Exception e) {
                Log.w("HytaleHost", "could not check " + config + ": " + e);
            }
        }
    }

    /** Reads NAME=value from the same env.txt the native loader reads, or null when it is absent. */
    private String envValue(String name) {
        File file = new File(getExternalFilesDir(null), "env.txt");
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null)
                if (line.startsWith(name + "="))
                    return line.substring(name.length() + 1);
        } catch (java.io.IOException e) {
            // No env.txt: use the default.
        }
        return null;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus)
            hideSystemBars();
    }

    // The status and navigation bars come back for a moment on a swipe from the edge.
    private void hideSystemBars() {
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller == null)
            return;
        controller.hide(WindowInsets.Type.systemBars());
        controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "main" };
    }

    @Override
    protected String[] getArguments() {
        String[] arguments = getIntent() != null ? getIntent().getStringArrayExtra(ARGUMENTS_EXTRA) : null;
        return arguments != null ? arguments : new String[0];
    }
}
