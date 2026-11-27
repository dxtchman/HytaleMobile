// Copyright (C) 2026 Hyprism Launcher
// SPDX-License-Identifier: GPL-3.0-only

package org.hyprism.game;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Restarts the game. It runs in its own process (":restart"), because it has to end the game's
 * process: first it asks the world server to stop, which saves the world, then it ends the game and
 * starts SetupActivity again with the extras of the first launch (the account's session included).
 */
public class RestartActivity extends Activity {
    static final String PID_EXTRA = "org.hyprism.game.RESTART_PID";
    static final String NEXT_EXTRA = "org.hyprism.game.RESTART_NEXT";
    private static final String TAG = "HytaleRestart";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setText("Restarting the game…\nSaving the world.");
        text.setTextColor(Color.rgb(220, 224, 232));
        text.setTextSize(18);
        text.setGravity(Gravity.CENTER);
        text.setBackgroundColor(Color.rgb(16, 18, 24));
        setContentView(text);

        int gamePid = getIntent().getIntExtra(PID_EXTRA, -1);
        Intent next = getIntent().getParcelableExtra(NEXT_EXTRA);
        new Thread(() -> {
            if (gamePid > 0)
                restart(gamePid);
            runOnUiThread(() -> {
                if (next != null)
                    startActivity(next);
                finish();
            });
        }, "restart").start();
    }

    private void restart(int gamePid) {
        // The world server was started by the game with --client-pid <its pid>. SIGTERM makes it save
        // the world and shut down normally, the same way the pre-generation ends it.
        List<Integer> servers = serversOf(gamePid);
        for (int pid : servers)
            Process.sendSignal(pid, 15);
        long deadline = System.currentTimeMillis() + 30000;
        for (int pid : servers)
            while (alive(pid) && System.currentTimeMillis() < deadline)
                sleep(250);
        Log.i(TAG, "servers " + servers + " stopped; ending the game process " + gamePid);
        Process.killProcess(gamePid);
        deadline = System.currentTimeMillis() + 5000;
        while (alive(gamePid) && System.currentTimeMillis() < deadline)
            sleep(100);
    }

    private static List<Integer> serversOf(int gamePid) {
        List<Integer> found = new ArrayList<>();
        File[] procs = new File("/proc").listFiles();
        if (procs == null)
            return found;
        String wanted = String.valueOf(gamePid);
        for (File proc : procs) {
            String name = proc.getName();
            if (!name.matches("\\d+"))
                continue;
            String[] args = cmdline(new File(proc, "cmdline"));
            for (int i = 0; i < args.length; i++) {
                if ((args[i].equals("--client-pid") && i + 1 < args.length && args[i + 1].equals(wanted))
                        || args[i].equals("--client-pid=" + wanted)) {
                    found.add(Integer.parseInt(name));
                    break;
                }
            }
        }
        return found;
    }

    private static String[] cmdline(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[8192];
            int n = in.read(data);
            return n > 0 ? new String(data, 0, n, StandardCharsets.UTF_8).split("\0") : new String[0];
        } catch (IOException e) {
            return new String[0];
        }
    }

    // A process that has exited but not been reaped by its parent stays in /proc as a zombie ("Z").
    private static boolean alive(int pid) {
        File stat = new File("/proc/" + pid + "/stat");
        if (!stat.exists())
            return false;
        try (FileInputStream in = new FileInputStream(stat)) {
            byte[] data = new byte[512];
            int n = in.read(data);
            String line = n > 0 ? new String(data, 0, n, StandardCharsets.UTF_8) : "";
            int close = line.lastIndexOf(')');
            return !(close >= 0 && close + 2 < line.length() && line.charAt(close + 2) == 'Z');
        } catch (IOException e) {
            return false;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            // keep waiting for the process
        }
    }
}
