// Copyright (C) 2026 Hyprism Launcher
// SPDX-License-Identifier: GPL-3.0-only

package org.hyprism.game;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates the land around the player before playing, so exploring it later only loads chunks.
 * The world server runs alone (no client), with the world's ChunkConfig.PregenerateRegion set to a
 * square around the player; when it reports the last chunks it is stopped normally, so it saves,
 * and the region is taken out of the config again (left in, the server would check it on every start).
 */
public class PregenerateActivity extends Activity {
    private static final String TAG = "HytalePregen";
    /** Started by SetupActivity for a new world: generate 1 km around the player, then open the game. */
    static final String AUTO_EXTRA = "org.hyprism.game.PREGEN_AUTO";
    static final String WORLD_EXTRA = "org.hyprism.game.PREGEN_WORLD";
    /** A save that has been pre-generated (or skipped) has this file. */
    static final String MARKER = ".hyprism-pregenerated";
    private static final Pattern PROGRESS = Pattern.compile("Pregenerated (\\d+)/(\\d+) chunks");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status, detail;
    private ProgressBar bar;
    private LinearLayout sizes;
    private Process server;
    private File worldConfig;
    private volatile long lastProgressAt;
    private volatile int done, total;
    private volatile boolean stopping;
    private boolean auto, skipped;
    private File autoWorld;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad * 2, pad, pad * 2, pad);
        root.setBackgroundColor(0xFF08090A);
        root.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = text(28, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setText("Pre-generate world");
        status = text(17, 0xFFF7F7F8);
        detail = text(14, 0xFF9A9CA3);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        sizes = new LinearLayout(this);
        sizes.setOrientation(LinearLayout.HORIZONTAL);
        for (int side : new int[] { 1024, 2048, 4096 }) {
            Button b = new Button(this);
            b.setText((side / 1024) + " × " + (side / 1024) + " km");
            b.setOnClickListener(v -> start(side / 2, latestWorld()));
            sizes.addView(b);
        }
        root.addView(title);
        root.addView(status);
        root.addView(sizes);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(detail);
        setContentView(root);

        auto = getIntent() != null && getIntent().getBooleanExtra(AUTO_EXTRA, false);
        if (auto) {
            autoWorld = new File(getIntent().getStringExtra(WORLD_EXTRA));
            sizes.setVisibility(android.view.View.GONE);
            Button skip = new Button(this);
            skip.setText("Skip and play");
            skip.setOnClickListener(v -> skip());
            root.addView(skip);
            title.setText("Preparing the new world");
            detail.setText("Generating 1 × 1 km around your character, so exploring that area later is much lighter. "
                + "It takes a few minutes. " + chargingHint());
            start(512, autoWorld);
            return;
        }

        if (HytaleActivity.isRunning()) {
            status.setText("Close the game first: the world cannot be open in two places.");
            sizes.setEnabled(false);
            sizes.setVisibility(android.view.View.GONE);
            return;
        }
        File world = latestWorld();
        if (world == null) {
            status.setText("No world found. Enter a world from the game once first.");
            sizes.setVisibility(android.view.View.GONE);
            return;
        }
        status.setText("World \"" + world.getName() + "\". Choose the size of the area around your character.");
        detail.setText(chargingHint());
    }

    private TextView text(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setPadding(0, 8, 0, 8);
        return t;
    }

    private String chargingHint() {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int plugged = battery != null ? battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) : 0;
        return plugged != 0
            ? "Charging. Leave the screen on and the phone still until it finishes."
            : "Tip: keep the phone charging; generating terrain uses a lot of CPU and takes several minutes.";
    }

    private File files() {
        return getExternalFilesDir(null);
    }

    /** The instance folder with the server and assets (the launcher's choice, or the files folder). */
    private File game() {
        String dir = getIntent() != null ? getIntent().getStringExtra(HytaleActivity.GAME_DIR_EXTRA) : null;
        return dir != null ? new File(dir) : files();
    }

    /** The save folder used most recently. */
    private File latestWorld() {
        File[] saves = new File(files(), "UserData/Saves").listFiles();
        File best = null;
        if (saves == null)
            return null;
        for (File s : saves) {
            File config = new File(s, "universe/worlds/default/config.json");
            if (config.isFile() && (best == null || config.lastModified() > best.lastModified()))
                best = s;
        }
        return best;
    }

    private void start(int radius, File world) {
        sizes.setVisibility(android.view.View.GONE);
        if (world == null)
            return;
        worldConfig = new File(world, "universe/worlds/default/config.json");
        try {
            double[] at = playerPosition(new File(world, "universe/players"));
            setRegion(at[0] - radius, at[1] - radius, at[0] + radius, at[1] + radius);
            status.setText(String.format("Generating %d × %d m around (%.0f, %.0f)…", radius * 2, radius * 2,
                at[0], at[1]));
            launchServer(world);
        } catch (Exception e) {
            Log.e(TAG, "could not start", e);
            status.setText("Could not start: " + e.getMessage());
            removeRegion();
        }
    }

    private static double[] playerPosition(File players) throws IOException {
        File[] list = players.listFiles((d, n) -> n.endsWith(".json"));
        if (list != null)
            for (File f : list) {
                try {
                    JSONObject pos = new JSONObject(read(f)).getJSONObject("Components").getJSONObject("Transform")
                        .getJSONObject("Position");
                    return new double[] { pos.getDouble("X"), pos.getDouble("Z") };
                } catch (Exception e) {
                    // try the next file
                }
            }
        return new double[] { 0, 0 };
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private void setRegion(double minX, double minZ, double maxX, double maxZ) throws Exception {
        JSONObject config = new JSONObject(read(worldConfig));
        JSONObject chunk = config.optJSONObject("ChunkConfig");
        if (chunk == null)
            chunk = new JSONObject();
        JSONObject region = new JSONObject();
        region.put("Min", new JSONObject().put("X", Math.floor(minX)).put("Y", Math.floor(minZ)));
        region.put("Max", new JSONObject().put("X", Math.ceil(maxX)).put("Y", Math.ceil(maxZ)));
        chunk.put("PregenerateRegion", region);
        config.put("ChunkConfig", chunk);
        Files.write(worldConfig.toPath(), config.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private void removeRegion() {
        if (worldConfig == null || !worldConfig.isFile())
            return;
        try {
            JSONObject config = new JSONObject(read(worldConfig));
            JSONObject chunk = config.optJSONObject("ChunkConfig");
            if (chunk != null && chunk.has("PregenerateRegion")) {
                chunk.remove("PregenerateRegion");
                Files.write(worldConfig.toPath(), config.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.e(TAG, "could not clear PregenerateRegion", e);
        }
    }

    private void launchServer(File world) throws IOException {
        File files = files();
        File game = game();
        List<String> cmd = new ArrayList<>();
        cmd.add(new File(getFilesDir(), "java").getAbsolutePath());
        cmd.add("-jar");
        cmd.add(new File(game, "Server/HytaleServer.jar").getAbsolutePath());
        cmd.add("--assets");
        cmd.add(new File(game, "Assets.zip").getAbsolutePath());
        cmd.add("--auth-mode");
        cmd.add("insecure");
        cmd.add("--singleplayer");
        cmd.add("--disable-sentry");
        cmd.add("--bind");
        cmd.add("127.0.0.1:5599");
        cmd.add("--universe");
        cmd.add(new File(world, "universe").getAbsolutePath());
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(world).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        File envFile = new File(files, "env.txt");
        if (envFile.isFile())
            try (BufferedReader r = new BufferedReader(new FileReader(envFile))) {
                for (String line; (line = r.readLine()) != null; ) {
                    int eq = line.indexOf('=');
                    if (eq > 0 && !line.startsWith("#"))
                        env.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
        server = pb.start();
        lastProgressAt = System.currentTimeMillis();
        new Thread(this::readOutput, "pregen-log").start();
        ui.postDelayed(this::watch, 5000);
    }

    private void readOutput() {
        List<String> tail = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(server.getInputStream()))) {
            for (String line; (line = r.readLine()) != null; ) {
                Matcher m = PROGRESS.matcher(line);
                if (m.find()) {
                    done = Integer.parseInt(m.group(1));
                    total = Integer.parseInt(m.group(2));
                    lastProgressAt = System.currentTimeMillis();
                    ui.post(this::showProgress);
                }
                tail.add(line);
                if (tail.size() > 12)
                    tail.remove(0);
            }
        } catch (IOException e) {
            Log.w(TAG, "server output: " + e);
        }
        int exit;
        try {
            exit = server.waitFor();
        } catch (InterruptedException e) {
            exit = -1;
        }
        removeRegion();
        final int code = exit;
        final String last = String.join("\n", tail);
        ui.post(() -> finished(code, last));
    }

    private void showProgress() {
        if (total > 0) {
            bar.setProgress((int) (1000L * done / total));
            detail.setText(done + " of " + total + " chunks");
        }
    }

    // The server logs every 100 chunks and says nothing after the last batch: once the count is within
    // a batch of the total and 20 s pass without news, generation is over.
    private void watch() {
        if (server == null || stopping)
            return;
        long quiet = System.currentTimeMillis() - lastProgressAt;
        if (total > 0 && (done >= total || (done >= total - 100 && quiet > 20000))) {
            stopping = true;
            status.setText("Finishing and saving…");
            server.destroy(); // SIGTERM: the server shuts down normally and saves
            ui.postDelayed(() -> { if (server != null && server.isAlive()) server.destroyForcibly(); }, 90000);
            return;
        }
        ui.postDelayed(this::watch, 2000);
    }

    private void finished(int exit, String lastLines) {
        server = null;
        if (auto) {
            if (stopping || skipped)
                mark(autoWorld);
            if (!stopping && !skipped)
                Log.w(TAG, "pre-generation stopped early (" + exit + "): " + lastLines);
            startGame();
            return;
        }
        if (stopping) {
            mark(worldConfig.getParentFile().getParentFile().getParentFile().getParentFile());
            bar.setProgress(1000);
            status.setText("Done! The area is generated. You can open the game.");
            detail.setText(total + " chunks generated.");
        } else {
            status.setText("The server stopped before finishing (code " + exit + ").");
            detail.setText(lastLines);
        }
    }

    /** Skips the pre-generation of this new world for good and opens the game. */
    private void skip() {
        skipped = true;
        if (server != null && server.isAlive()) {
            status.setText("Saving what was generated so far…");
            server.destroy(); // finished() opens the game once it has saved
        } else {
            removeRegion();
            mark(autoWorld);
            startGame();
        }
    }

    static void mark(File world) {
        try {
            new File(world, MARKER).createNewFile();
        } catch (IOException e) {
            Log.w(TAG, "could not mark " + world + ": " + e);
        }
    }

    private void startGame() {
        Intent game = new Intent(this, HytaleActivity.class);
        if (getIntent() != null && getIntent().getExtras() != null)
            game.putExtras(getIntent().getExtras());
        game.removeExtra(AUTO_EXTRA);
        game.removeExtra(WORLD_EXTRA);
        game.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(game);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (server != null && server.isAlive()) {
            stopping = true;
            server.destroy();
        }
        super.onDestroy();
    }
}
