package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FFmpeg is an in-process native library; production sources must not restore the external tool chain. */
class ExternalFfmpegForbiddenStaticTest {
    private static final String[] TREES = {
            "1.20.1 recode", "1.21.1", "1.21.10", "1.21.11 recode", "26.1.2", "26.2", "26.3"
    };
    private static final Pattern LEGACY = Pattern.compile(
            "\\b(?:FfmpegSupport|FfmpegInstaller|FfmpegNotice|FfmpegNoticeOverlay|FfmpegPackageDownload|GuiFfmpegNotice|FFMPEG_NOTICE)\\b");
    private static final Pattern PROCESS = Pattern.compile("\\bProcessBuilder\\b|\\.\\s*exec\\s*\\(");
    private static final Pattern ENVIRONMENT = Pattern.compile("\\bgetenv\\s*\\(");

    @Test
    void allProductionJavaAndKotlinUseOnlyTheNativeLibrary() throws IOException {
        Path repo = ProjectPaths.repoRoot();
        List<Path> roots = new ArrayList<>();
        for (String shared : new String[]{"muonium-common", "mpparty-common"}) {
            assertTrue(Files.isDirectory(repo.resolve(shared).resolve("src/main/java")), "Missing shared source root: " + shared);
            addMainRoots(roots, repo.resolve(shared));
        }
        for (String tree : TREES) {
            Path version = repo.resolve(tree);
            assertTrue(Files.isDirectory(version.resolve("src/main/java")), "Missing version source root: " + tree);
            addMainRoots(roots, version);
            for (String loader : new String[]{"fabric", "forge", "neoforge"}) {
                addMainRoots(roots, version.resolve(loader));
            }
        }
        List<String> offenders = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path file : walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".kt")).toList()) {
                    List<String> reasons = violations(Files.readString(file), file.toString().endsWith(".kt"));
                    if (LEGACY.matcher(file.getFileName().toString()).find()) reasons.add("removed source file");
                    if (!reasons.isEmpty()) offenders.add(repo.relativize(file) + ": " + String.join(", ", reasons));
                }
            }
        }
        offenders.sort(String::compareTo);
        assertTrue(offenders.isEmpty(), "External FFmpeg/ffprobe dependencies are forbidden:\n" + String.join("\n", offenders));
    }

    @Test
    void detectsDirectVariableAndAliasedProcessCommands() {
        for (String program : new String[]{"ffmpeg", "ffprobe", "C:/tools/ffmpeg.exe"}) {
            assertFalse(violations("new ProcessBuilder(\"" + program + "\", \"-version\").start();", false).isEmpty());
            assertFalse(violations("String executable = \"" + program + "\"; new ProcessBuilder(executable);", false).isEmpty());
        }
        assertFalse(violations("Runtime.getRuntime().exec(\"ffmpeg -version\");", false).isEmpty());
        assertFalse(violations("val tool = \"\"\"ffmpeg\"\"\"\nProcessBuilder(listOf(tool, \"-version\"))", true).isEmpty());
        assertFalse(violations("import java.lang.ProcessBuilder as Spawn\nval tool = \"ffmpeg\"\nSpawn(tool)", true).isEmpty());
    }

    @Test
    void detectsPathDiscoveryAndRemovedClasses() {
        assertFalse(violations("String dirs = System.getenv(\"PATH\"); find(dirs, \"ffmpeg\");", false).isEmpty());
        assertFalse(violations("val dirs = System.getenv()[\"Path\"]; find(dirs, \"ffmpeg\")", true).isEmpty());
        assertFalse(violations("System.getenv(\"FFMPEG_PATH\");", false).isEmpty());
        assertFalse(violations("FfmpegSupport.executable();", false).isEmpty());
    }

    @Test
    void permitsOtherCompanionsNativeAssetsAndComments() {
        assertTrue(violations("new ProcessBuilder(\"node\", \"companion.js\"); System.getenv(\"PATH\");", false).isEmpty());
        assertTrue(violations("new ProcessBuilder(\"browser\", \"https://example.invalid/\");", false).isEmpty());
        assertTrue(violations("FfmpegRuntime.isLoaded(); nativeRoot.resolve(\"native/ffmpeg\"); System.getenv();", false).isEmpty());
        assertTrue(violations("if (name.contains(\"ffprobe\")) throw new IllegalArgumentException();", false).isEmpty());
        assertTrue(violations("// new ProcessBuilder(\"ffmpeg\");\nint x; /* ffprobe.exe */", false).isEmpty());
        assertTrue(violations("/* outer /* nested */ ProcessBuilder(\"ffprobe\") */ val x = 1", true).isEmpty());
        assertFalse(violations("/* outer /* nested */ end */ ProcessBuilder(\"ffmpeg\")", true).isEmpty());
        // Comment markers inside a Kotlin raw string must not hide the subsequent executable call.
        assertFalse(violations("val note = \"\"\"/* //\"\"\"\nProcessBuilder(\"ffmpeg\")", true).isEmpty());
        assertTrue(violations("val note = \"\"\"ProcessBuilder(ignored) /* native/ffmpeg */\"\"\"", true).isEmpty());
    }

    private static void addMainRoots(List<Path> roots, Path project) {
        roots.add(project.resolve("src/main/java"));
        roots.add(project.resolve("src/main/kotlin"));
    }

    private static List<String> violations(String source, boolean kotlin) {
        Lexed lexed = lex(source, kotlin);
        String text = lexed.code() + "\n" + String.join("\n", lexed.literals());
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> reasons = new ArrayList<>();
        if (LEGACY.matcher(text).find()) reasons.add("removed installer/probe/notice reference");
        if (lower.contains("ffmpeg") || lower.contains("ffprobe")) {
            if (PROCESS.matcher(lexed.code()).find()) reasons.add("FFmpeg subprocess");
            boolean path = lexed.literals().stream().map(s -> s.toLowerCase(Locale.ROOT))
                    .anyMatch(s -> s.equals("path") || ((s.contains("ffmpeg") || s.contains("ffprobe")) && s.contains("path")));
            if (path && ENVIRONMENT.matcher(lexed.code()).find()) reasons.add("FFmpeg PATH/environment discovery");
        }
        return reasons;
    }

    private record Lexed(String code, List<String> literals) {}

    /** Keep code separate from literals; Kotlin has raw strings and nested block comments. */
    private static Lexed lex(String source, boolean kotlin) {
        StringBuilder code = new StringBuilder();
        List<String> literals = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            if (source.startsWith("//", i)) {
                while (i < source.length() && source.charAt(i) != '\n') i++;
                code.append('\n');
            } else if (source.startsWith("/*", i)) {
                i += 2;
                int depth = 1;
                while (i < source.length() && depth > 0) {
                    if (kotlin && source.startsWith("/*", i)) { depth++; i += 2; }
                    else if (source.startsWith("*/", i)) { depth--; i += 2; }
                    else i++;
                }
                code.append(' ');
            } else if (source.charAt(i) == '"' || source.charAt(i) == '\'') {
                char quote = source.charAt(i);
                boolean raw = source.startsWith("\"\"\"", i);
                i += raw ? 3 : 1;
                int start = i;
                while (i < source.length()) {
                    if (raw && source.startsWith("\"\"\"", i)) break;
                    if (!raw && source.charAt(i) == quote) break;
                    if (source.charAt(i) == '\\' && (!raw || !kotlin)) i++;
                    if (i < source.length()) i++;
                }
                literals.add(source.substring(start, Math.min(i, source.length())));
                i = Math.min(source.length(), i + (raw ? 3 : 1));
                code.append(' ');
            } else {
                code.append(source.charAt(i++));
            }
        }
        return new Lexed(code.toString(), literals);
    }
}
