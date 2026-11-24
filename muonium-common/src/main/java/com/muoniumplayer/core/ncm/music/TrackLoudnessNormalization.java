package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.utils.other.multithreading.BackgroundWork;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Per-deck track gain calculation, background analysis, and disposable measurement cache. */
public final class TrackLoudnessNormalization {

    public static final float TARGET_LUFS = -18.0f;
    private static final int ANALYZER_VERSION = 1;
    private static final int MAX_CACHE_FILES = 2_000;
    private static final int MAX_MEMORY_ENTRIES = 256;
    private static final double MAX_BOOST_DB = 12.0;
    private static final Object MEMORY_LOCK = new Object();
    private static final LinkedHashMap<String, Measurement> MEMORY =
            new LinkedHashMap<String, Measurement>(128, .75f, true);
    private static final Set<AudioPlayer> PLAYERS = ConcurrentHashMap.newKeySet();
    private static final Set<String> ANALYZING = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger WRITES_SINCE_PRUNE = new AtomicInteger();
    private static volatile File cacheDirectory = ConfigPaths.TRACK_LOUDNESS_CACHE;
    private static volatile boolean enabled;

    private TrackLoudnessNormalization() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        for (AudioPlayer player : PLAYERS) {
            player.refreshTrackLoudnessGain();
            if (value) request(player, player.getSourceFile());
        }
    }

    static void register(AudioPlayer player) {
        if (player == null) return;
        PLAYERS.add(player);
        if (enabled) request(player, player.getSourceFile());
    }

    static void trackChanged(AudioPlayer player, File file) {
        if (enabled) request(player, file);
    }

    static void unregister(AudioPlayer player) {
        if (player != null) PLAYERS.remove(player);
    }

    static float gainFor(float integratedLufs, float samplePeak) {
        if (!Float.isFinite(integratedLufs) || !Float.isFinite(samplePeak) || samplePeak <= 0f) return 1f;
        double gainDb = TARGET_LUFS - integratedLufs;
        gainDb = Math.min(gainDb, MAX_BOOST_DB);
        // Sample-peak protection is conservative; this is not true-peak limiting.
        gainDb = Math.min(gainDb, -20.0 * Math.log10(samplePeak));
        double gain = Math.pow(10.0, gainDb / 20.0);
        return Double.isFinite(gain) && gain > 0.0 ? (float) gain : 1f;
    }

    private static void request(final AudioPlayer player, final File file) {
        if (!enabled || player == null || file == null || !file.isFile() || player.isClosed()) return;
        final String key = keyOf(file);
        if (key.isEmpty()) return;
        Measurement cached = lookup(file);
        if (cached != null) {
            player.applyLoudnessMeasurement(file, cached);
            return;
        }
        if (!ANALYZING.add(key)) return;
        BackgroundWork.submit(() -> {
            try {
                IntegratedLoudnessMeter.Measurement measured = TrackLoudnessAnalyzer.analyze(file);
                if (measured == null || !key.equals(keyOf(file))) return;
                Measurement result = new Measurement(measured.integratedLufs, measured.samplePeak);
                cache(file, result);
                for (AudioPlayer candidate : PLAYERS) {
                    File current = candidate.getSourceFile();
                    if (current != null && key.equals(keyOf(current))) {
                        candidate.applyLoudnessMeasurement(current, result);
                    }
                }
            } finally {
                ANALYZING.remove(key);
            }
        }).whenComplete((ignored, failure) -> ANALYZING.remove(key));
    }

    static Measurement lookup(File file) {
        if (file == null || !file.isFile()) return null;
        String key = keyOf(file);
        if (key.isEmpty()) return null;
        Measurement cached = memory(key);
        if (cached == null) {
            cached = read(key);
            if (cached != null) remember(key, cached);
        }
        return cached;
    }

    static void cache(File file, Measurement measurement) {
        if (file == null || measurement == null) return;
        String key = keyOf(file);
        if (key.isEmpty() || !Float.isFinite(measurement.integratedLufs)
                || !Float.isFinite(measurement.samplePeak) || measurement.samplePeak <= 0f) return;
        remember(key, measurement);
        write(key, measurement);
    }

    private static String keyOf(File file) {
        try {
            return "v" + ANALYZER_VERSION + "\n" + file.getCanonicalPath()
                    + "\n" + file.length() + "\n" + file.lastModified();
        } catch (IOException unavailable) {
            return "";
        }
    }

    private static Measurement memory(String key) {
        synchronized (MEMORY_LOCK) {
            return MEMORY.get(key);
        }
    }

    private static void remember(String key, Measurement value) {
        synchronized (MEMORY_LOCK) {
            MEMORY.put(key, value);
            while (MEMORY.size() > MAX_MEMORY_ENTRIES) {
                String eldest = MEMORY.keySet().iterator().next();
                MEMORY.remove(eldest);
            }
        }
    }

    private static Measurement read(String key) {
        File source = fileFor(key);
        if (!source.isFile()) return null;
        try {
            String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
            JsonObject root = new JsonParser().parse(text).getAsJsonObject();
            if (root.get("version").getAsInt() != ANALYZER_VERSION
                    || !fingerprint(key).equals(root.get("key").getAsString())) return null;
            float lufs = root.get("integratedLufs").getAsFloat();
            float peak = root.get("samplePeak").getAsFloat();
            if (!Float.isFinite(lufs) || !Float.isFinite(peak) || peak <= 0f) return null;
            source.setLastModified(System.currentTimeMillis());
            return new Measurement(lufs, peak);
        } catch (Throwable unavailable) {
            return null;
        }
    }

    private static void write(String key, Measurement value) {
        File directory = cacheDirectory;
        if (!directory.isDirectory() && !directory.mkdirs()) return;
        File target = fileFor(key);
        File temporary = new File(directory, target.getName() + ".tmp");
        try {
            JsonObject root = new JsonObject();
            root.addProperty("version", ANALYZER_VERSION);
            root.addProperty("key", fingerprint(key));
            root.addProperty("integratedLufs", value.integratedLufs);
            root.addProperty("samplePeak", value.samplePeak);
            Files.write(temporary.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnavailable) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            if (WRITES_SINCE_PRUNE.incrementAndGet() >= 32) {
                WRITES_SINCE_PRUNE.set(0);
                prune(directory);
            }
        } catch (Throwable unavailable) {
            System.err.println("[Loudness] Cache write failed: " + unavailable.getMessage());
        }
    }

    private static void prune(File directory) {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null || files.length <= MAX_CACHE_FILES) return;
        List<File> oldestFirst = new ArrayList<>();
        java.util.Collections.addAll(oldestFirst, files);
        oldestFirst.sort(Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < oldestFirst.size() - MAX_CACHE_FILES; i++) {
            if (!oldestFirst.get(i).delete()) {
                System.err.println("[Loudness] Unable to prune cache file " + oldestFirst.get(i).getName());
            }
        }
    }

    private static File fileFor(String key) {
        return new File(cacheDirectory, fingerprint(key) + ".json");
    }

    private static String fingerprint(String key) {
        byte[] bytes = ("track-loudness:" + key).getBytes(StandardCharsets.UTF_8);
        long hash = 0xcbf29ce484222325L;
        for (byte value : bytes) {
            hash ^= value & 0xffL;
            hash *= 0x100000001b3L;
        }
        return Long.toHexString(hash);
    }

    static final class Measurement {
        final float integratedLufs;
        final float samplePeak;

        Measurement(float integratedLufs, float samplePeak) {
            this.integratedLufs = integratedLufs;
            this.samplePeak = samplePeak;
        }
    }

    static void setCacheDirectoryForTests(File directory) {
        cacheDirectory = directory == null ? ConfigPaths.TRACK_LOUDNESS_CACHE : directory;
        synchronized (MEMORY_LOCK) {
            MEMORY.clear();
        }
    }

    static void clearMemoryForTests() {
        synchronized (MEMORY_LOCK) {
            MEMORY.clear();
        }
    }
}
