// Copyright (C) 2026 Hyprism Launcher
// SPDX-License-Identifier: GPL-3.0-only

package org.hyprism.game;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Prepares what the local world server needs on this phone, with nothing but the APK and the game
 * files the launcher installed:
 *
 * <ul>
 *   <li>the Java runtime (Termux's OpenJDK 25 for Android, in assets/runtime.zip) and the server's
 *       native libraries, extracted to the app's private files folder, the only place Android lets
 *       the app execute them from (the app targets SDK 28 for that);</li>
 *   <li>a copy of Server/HytaleServer.jar that loads on Android arm64 (see {@link #patchJar}), kept
 *       next to the runtime so the original stays as the launcher installed it and game updates
 *       still apply to it; the server launcher (files/java) runs the copy;</li>
 *   <li>env.txt and args.txt, which point the client at that Java, when they are missing.</li>
 * </ul>
 *
 * Every step is skipped when its result is already there for this APK and this game build.
 */
final class ServerSetup {
    interface Progress {
        void report(String message);
    }

    private static final String TAG = "HytaleSetup";

    private final Context context;
    private final Progress progress;
    private final File home;
    private final File files;
    private final File external;
    private final File game;

    ServerSetup(Context context, Progress progress) {
        this(context, progress, null);
    }

    /** @param gameDir the instance folder the launcher installed the game in (null: the files folder) */
    ServerSetup(Context context, Progress progress, String gameDir) {
        this.context = context;
        this.progress = progress;
        this.home = context.getDataDir();
        this.files = context.getFilesDir();
        this.external = context.getExternalFilesDir(null);
        this.game = gameDir != null ? new File(gameDir) : external;
    }

    /** The client binary the launcher installs; without it there is no game to start. */
    File client() {
        return new File(game, "Client/Hytale.app/Contents/MacOS/HytaleClient");
    }

    File patchedJar() {
        return new File(files, "server/HytaleServer.jar");
    }

    void run() throws IOException {
        extractRuntime();
        installServerLauncher();
        patchServerJar();
        writeDefaults();
    }

    // ---- runtime ------------------------------------------------------------------------------

    private String apkStamp() {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionCode + " " + info.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }

    private void extractRuntime() throws IOException {
        File stampFile = new File(files, "runtime.stamp");
        String stamp = apkStamp();
        if (new File(files, "jdk/bin/java").isFile() && stamp.equals(readText(stampFile)))
            return;
        progress.report("Installing the world server's Java…");
        long start = System.currentTimeMillis();
        deleteTree(new File(files, "jdk"));
        deleteTree(new File(files, "natives"));
        deleteTree(new File(files, "jar-natives"));
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(context.getAssets().open("runtime.zip"))) {
            byte[] buffer = new byte[1 << 16];
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                File out = new File(files, entry.getName());
                if (!out.getCanonicalPath().startsWith(files.getCanonicalPath() + File.separator))
                    throw new IOException("bad runtime entry " + entry.getName());
                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                out.getParentFile().mkdirs();
                try (OutputStream os = new FileOutputStream(out)) {
                    for (int n; (n = zip.read(buffer)) > 0; )
                        os.write(buffer, 0, n);
                }
                String name = entry.getName();
                if (name.startsWith("jdk/bin/") || name.equals("jdk/lib/jspawnhelper") || name.equals("jdk/lib/jexec"))
                    out.setExecutable(true, true);
                if (++count % 200 == 0)
                    progress.report("Installing the world server's Java… " + count + " files");
            }
        }
        new File(home, "tmp").mkdirs();
        writeText(stampFile, stamp);
        Log.i(TAG, "runtime extracted: " + count + " files in " + (System.currentTimeMillis() - start) + " ms");
    }

    /**
     * Writes the world server launcher (assets/java) to files/java, where args.txt points the client,
     * so a new APK brings its JVM settings. Only a changed file is rewritten.
     */
    void installServerLauncher() {
        File target = new File(files, "java");
        try (InputStream in = context.getAssets().open("java")) {
            byte[] wanted = readAll(in);
            if (target.isFile() && Arrays.equals(Files.readAllBytes(target.toPath()), wanted))
                return;
            File tmp = new File(files, "java.tmp");
            Files.write(tmp.toPath(), wanted);
            if (!tmp.setExecutable(true, true) || !tmp.renameTo(target))
                throw new IOException("could not replace " + target);
            Log.i(TAG, "installed the world server launcher");
        } catch (IOException e) {
            Log.w(TAG, "world server launcher not updated: " + e);
        }
    }

    // ---- server jar ---------------------------------------------------------------------------

    private void patchServerJar() throws IOException {
        File original = new File(game, "Server/HytaleServer.jar");
        if (!original.isFile())
            return;
        File patched = patchedJar();
        File stampFile = new File(files, "server/HytaleServer.jar.stamp");
        String stamp = original.getPath() + " " + original.length() + " " + original.lastModified() + " " + apkStamp();
        if (patched.isFile() && stamp.equals(readText(stampFile)))
            return;
        progress.report("Adapting the world server for Android…");
        long start = System.currentTimeMillis();
        patched.getParentFile().mkdirs();
        File tmp = new File(patched.getPath() + ".tmp");
        patchJar(original, tmp,
            Files.readAllBytes(new File(files, "jar-natives/libzstd.so").toPath()),
            Files.readAllBytes(new File(files, "jar-natives/libquiche.so").toPath()));
        if (!tmp.renameTo(patched))
            throw new IOException("could not write " + patched);
        writeText(stampFile, stamp);
        Log.i(TAG, "server jar patched in " + (System.currentTimeMillis() - start) + " ms");
    }

    private static final String ZSTD_CLASS = "com/hypixel/hytale/protocol/io/ZstdNative.class";
    private static final String QUICHE_CLASS = "com/hypixel/hytale/lib/quiche/QuicheNative.class";
    private static final String SERVER_CLASS = "com/hypixel/hytale/server/core/HytaleServer.class";
    // Every ECS system keeps a HistoricMetric sampled every 33 333 333 ns (30 a second) over windows of
    // 1 s, 1 min and 5 min: 9 000 samples in two long[] each, ~34 MB of heap, read only by /world perf.
    // One sample a second keeps the same windows with 300 samples.
    private static final String STORE_CLASS = "com/hypixel/hytale/component/Store.class";
    // putstatic #74 (instance); invokestatic #78 (Quic.ensureAvailability); bipush 25
    private static final byte[] QUIC_CHECK = hex("b3004ab8004e1019");
    private static final byte[] QUIC_CHECK_NOP = hex("b3004a0000001019");

    /**
     * Copies HytaleServer.jar with the changes that let it run on Android arm64, the same ones as
     * server/patch-server-jar.py: ZstdNative and QuicheNative accept Linux only on amd64 and then
     * extract native/linux-x64/*.so from the jar, so their "amd64" constant becomes "aarch64" and
     * those two entries become Android arm64 builds; HytaleServer's constructor calls Netty's
     * Quic.ensureAvailability(), whose native library exists only for glibc and is not used with the
     * QUICHE transport, so the call becomes NOPs; and the per-system metrics sample once a second.
     * A jar already patched this way (an install made before this app did it) is copied as it is.
     */
    static void patchJar(File in, File out, byte[] zstd, byte[] quiche) throws IOException {
        Map<String, byte[]> natives = new HashMap<>();
        natives.put("native/linux-x64/libzstd.so", zstd);
        natives.put("native/linux-x64/libquiche.so", quiche);
        Set<String> done = new HashSet<>();
        try (ZipFile zip = new ZipFile(in);
             ZipOutputStream zout = new ZipOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(out), 1 << 16))) {
            zout.setLevel(Deflater.BEST_SPEED);
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            byte[] buffer = new byte[1 << 16];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                byte[] data = null;
                if (name.equals(ZSTD_CLASS) || name.equals(QUICHE_CLASS)) {
                    data = rewriteUtf8(read(zip, entry), "amd64", "aarch64");
                } else if (name.equals(SERVER_CLASS)) {
                    data = read(zip, entry);
                    if (count(data, QUIC_CHECK) == 1)
                        data = replace(data, QUIC_CHECK, QUIC_CHECK_NOP);
                    else if (count(data, QUIC_CHECK_NOP) != 1)
                        throw new IOException("Quic.ensureAvailability call not found in this server version");
                } else if (name.equals(STORE_CLASS)) {
                    data = rewriteLong(read(zip, entry), 33333333L, 1000000000L);
                } else if (natives.containsKey(name)) {
                    data = natives.get(name);
                }
                ZipEntry copy = new ZipEntry(name);
                copy.setTime(entry.getTime());
                zout.putNextEntry(copy);
                if (data != null) {
                    zout.write(data);
                    done.add(name);
                } else if (!entry.isDirectory()) {
                    try (InputStream is = zip.getInputStream(entry)) {
                        for (int n; (n = is.read(buffer)) > 0; )
                            zout.write(buffer, 0, n);
                    }
                }
                zout.closeEntry();
            }
        }
        Set<String> missing = new HashSet<>(Arrays.asList(ZSTD_CLASS, QUICHE_CLASS, SERVER_CLASS, STORE_CLASS));
        missing.addAll(natives.keySet());
        missing.removeAll(done);
        if (!missing.isEmpty()) {
            out.delete();
            throw new IOException("server jar entries not found: " + missing);
        }
    }

    private static final int[] CONSTANT_SIZES = new int[21];

    static {
        Arrays.fill(CONSTANT_SIZES, -1);
        CONSTANT_SIZES[3] = 4; CONSTANT_SIZES[4] = 4; CONSTANT_SIZES[5] = 8; CONSTANT_SIZES[6] = 8;
        CONSTANT_SIZES[7] = 2; CONSTANT_SIZES[8] = 2; CONSTANT_SIZES[9] = 4; CONSTANT_SIZES[10] = 4;
        CONSTANT_SIZES[11] = 4; CONSTANT_SIZES[12] = 4; CONSTANT_SIZES[15] = 3; CONSTANT_SIZES[16] = 2;
        CONSTANT_SIZES[17] = 4; CONSTANT_SIZES[18] = 4; CONSTANT_SIZES[19] = 2; CONSTANT_SIZES[20] = 2;
    }

    private static int u16(byte[] d, int pos) {
        return ((d[pos] & 0xff) << 8) | (d[pos + 1] & 0xff);
    }

    private static int constantSize(byte[] d, int pos) throws IOException {
        int tag = d[pos] & 0xff;
        if (tag == 1)
            return 3 + u16(d, pos + 1);
        if (tag >= CONSTANT_SIZES.length || CONSTANT_SIZES[tag] < 0)
            throw new IOException("unknown constant pool tag " + tag);
        return 1 + CONSTANT_SIZES[tag];
    }

    /** Rewrites the CONSTANT_Utf8 entries equal to old; a class that has new instead is left as is. */
    static byte[] rewriteUtf8(byte[] d, String old, String now) throws IOException {
        byte[] oldBytes = old.getBytes(StandardCharsets.UTF_8);
        byte[] newBytes = now.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.length + 16);
        out.write(d, 0, 10);
        int count = u16(d, 8);
        int pos = 10, hits = 0, already = 0;
        for (int index = 1; index < count; ) {
            int tag = d[pos] & 0xff;
            int size = constantSize(d, pos);
            if (tag == 1) {
                byte[] text = Arrays.copyOfRange(d, pos + 3, pos + size);
                if (Arrays.equals(text, oldBytes)) {
                    text = newBytes;
                    hits++;
                } else if (Arrays.equals(text, newBytes)) {
                    already++;
                }
                out.write(1);
                out.write(text.length >> 8);
                out.write(text.length & 0xff);
                out.write(text);
            } else {
                out.write(d, pos, size);
            }
            pos += size;
            index += (tag == 5 || tag == 6) ? 2 : 1;
        }
        if (hits == 0 && already == 0)
            throw new IOException("constant \"" + old + "\" not found");
        out.write(d, pos, d.length - pos);
        return out.toByteArray();
    }

    /** Rewrites the one CONSTANT_Long equal to old; a class that already has new is left as is. */
    static byte[] rewriteLong(byte[] d, long old, long now) throws IOException {
        byte[] out = d.clone();
        int count = u16(d, 8);
        int pos = 10, hits = 0, already = 0;
        for (int index = 1; index < count; ) {
            int tag = d[pos] & 0xff;
            int size = constantSize(d, pos);
            if (tag == 5) {
                long value = ByteBuffer.wrap(d, pos + 1, 8).getLong();
                if (value == old) {
                    ByteBuffer.wrap(out, pos + 1, 8).putLong(now);
                    hits++;
                } else if (value == now) {
                    already++;
                }
            }
            pos += size;
            index += (tag == 5 || tag == 6) ? 2 : 1;
        }
        if (hits == 1 || (hits == 0 && already > 0))
            return out;
        throw new IOException("long constant " + old + " found " + hits + " times");
    }

    private static int count(byte[] data, byte[] pattern) {
        int n = 0;
        for (int i = indexOf(data, pattern, 0); i >= 0; i = indexOf(data, pattern, i + 1))
            n++;
        return n;
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (int i = from; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++)
                if (data[i + j] != pattern[j])
                    continue outer;
            return i;
        }
        return -1;
    }

    private static byte[] replace(byte[] data, byte[] pattern, byte[] with) {
        byte[] out = data.clone();
        int i = indexOf(data, pattern, 0);
        System.arraycopy(with, 0, out, i, with.length);
        return out;
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++)
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    // ---- env.txt and args.txt -----------------------------------------------------------------

    private void writeDefaults() throws IOException {
        String h = home.getPath();
        File env = new File(external, "env.txt");
        if (!env.exists()) {
            writeText(env, "MACHO_SOFT_TRAPS=1\n"
                + "LD_LIBRARY_PATH=" + h + "/files/jdk/lib:" + h + "/files/jdk/lib/server\n"
                + "JAVA_TOOL_OPTIONS=-XX:-UsePerfData -Djava.io.tmpdir=" + h + "/tmp -Duser.home=" + h
                + " -Drocksdb.lib.path=" + h + "/files/natives/librocksdb.so"
                + " -DZstdNativePath=" + h + "/files/natives/libzstd-jni.so\n");
            Log.i(TAG, "wrote " + env);
        }
        File args = new File(external, "args.txt");
        if (!args.exists()) {
            writeText(args, "--java-exec\n" + h + "/files/java\n");
            Log.i(TAG, "wrote " + args);
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static byte[] read(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[1 << 14];
        for (int n; (n = in.read(buffer)) > 0; )
            bytes.write(buffer, 0, n);
        return bytes.toByteArray();
    }

    private static String readText(File file) {
        try (InputStream in = new FileInputStream(file)) {
            return new String(readAll(in), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeText(File file, String text) throws IOException {
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), (text.endsWith("\n") ? text : text + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null && !Files.isSymbolicLink(file.toPath()))
            for (File child : children)
                deleteTree(child);
        file.delete();
    }
}
