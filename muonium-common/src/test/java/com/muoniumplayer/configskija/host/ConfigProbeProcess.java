package com.muoniumplayer.configskija.host;

import java.io.File;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The real settings model initializes legacy singletons; isolate probes from the shared JUnit VM. */
final class ConfigProbeProcess {
    private ConfigProbeProcess() {}
    static void run(Class<?> entry, String scenario) throws Exception {
        Set<String> classpath = new LinkedHashSet<>();
        for (String path : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator))) if (!path.isBlank()) classpath.add(path);
        for (ClassLoader loader = entry.getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) for (var url : urls.getURLs()) {
                if (url.getProtocol().equals("file")) classpath.add(Path.of(url.toURI()).toString());
            }
        }
        Path output = Files.createTempFile("muonium-config-probe-", ".log");
        Process process = null;
        try {
            Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
            process = new ProcessBuilder(java.toString(), "-Djava.awt.headless=true", "-Dmuonium.skija.autoDownload=false", "-cp", String.join(File.pathSeparator, classpath), entry.getName(), scenario)
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                fail("离屏探针超时: " + entry.getSimpleName());
            }
            assertEquals(0, process.exitValue(), () -> {
                try { return Files.readString(output); } catch (Exception failure) { return failure.toString(); }
            });
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(output);
        }
    }
}
