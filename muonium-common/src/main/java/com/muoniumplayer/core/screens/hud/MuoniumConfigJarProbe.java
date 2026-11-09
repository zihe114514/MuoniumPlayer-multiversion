package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.update.SemVer;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 认 mods/ 里那些 {@code muoniumconfig*.jar} 到底是谁、什么版本。
 *
 * <h2>为什么不看文件名</h2>
 * 文件名只是发布时我们自己的约定（{@code muoniumconfig-1.7.0.jar}），玩家改名、解压器
 * 加后缀、第三方启动器塞进来的从来都叫什么的都有。真正的权威是 jar 里的 mod 元数据：
 * Fabric 读 {@code fabric.mod.json}，Forge 1.20.1 读 {@code META-INF/mods.toml}，
 * NeoForge 读 {@code META-INF/neoforge.mods.toml}。三种都看一遍，谁先命中算谁。
 *
 * <h2>为什么必须认出「这是我们的 mod」</h2>
 * 删除动作是按"文件"做的。删之前必须百分百确认那是 MuoniumConfig 而不是玩家的别的
 * mod —— 认错一次就是替玩家删掉他的东西。所以判定条件是硬条件：元数据里
 * {@code id}/{@code modId} 必须等于 {@value #MOD_ID}，文件名像不算数。
 *
 * <p>本类只做只读探测，不做任何删除；删除策略在 {@link MuoniumConfigDownload}。</p>
 */
final class MuoniumConfigJarProbe {

    /** MuoniumConfig 的 mod id（与 fabric.mod.json / *.mods.toml 里的一致）。 */
    static final String MOD_ID = "muoniumconfigv1";

    /** jar 里三种可能带版本信息的元数据文件，按加载器分。 */
    private static final String[] METADATA_ENTRIES = {
            "fabric.mod.json",
            "META-INF/neoforge.mods.toml",
            "META-INF/mods.toml",
    };

    /** 单个元数据文件的读取上限：正常只有几 KB，超过就是坏包，不必读完。 */
    private static final int MAX_METADATA_BYTES = 256 * 1024;

    /** 探测结果：一个 jar 是不是 MuoniumConfig、是哪个版本。 */
    static final class Info {
        /** 文件本身。 */
        final File file;
        /** 元数据里的版本号，例如 {@code 1.7.0}；解析不出时是空串。 */
        final String version;
        /**
         * 元数据里的目标 MC 版本，例如 {@code 26.3.x} / {@code [1.20.1,1.21)}；
         * Fabric 与 NeoForge 的写法不同，只用于日志，不做判定。
         */
        final String minecraftRange;

        Info(File file, String version, String minecraftRange) {
            this.file = file;
            this.version = version == null ? "" : version;
            this.minecraftRange = minecraftRange == null ? "" : minecraftRange;
        }

        /** 版本号可用（能参与比较）才算「知道它是哪一版」。 */
        boolean hasVersion() {
            return !version.isEmpty() && !SemVer.isPreRelease(version);
        }

        @Override
        public String toString() {
            return file.getName() + " (" + (version.isEmpty() ? "版本未知" : version)
                    + (minecraftRange.isEmpty() ? "" : ", MC " + minecraftRange) + ")";
        }
    }

    private MuoniumConfigJarProbe() {
    }

    /**
     * 扫一个目录，挑出所有 MuoniumConfig 的 jar。
     *
     * <p>不可读的 jar（下载中断留下半个文件、别的 mod 换了扩展名）直接跳过并打一行日志：
     * 扫描是启动路径上的动作，任何单个坏文件都不能让整次扫描抛出。</p>
     *
     * @param modsDir mods 目录；不存在时返回空列表
     * @return 命中的 jar，按文件名排序（顺序稳定，便于日志与测试对拍）
     */
    static List<Info> scan(File modsDir) {
        List<Info> found = new ArrayList<>();
        if (modsDir == null) {
            return found;
        }
        File[] files = modsDir.listFiles();
        if (files == null) {
            return found;
        }
        List<File> sorted = new ArrayList<>();
        for (File file : files) {
            // 先按名字粗筛，避免把整个 mods/ 的每个 jar 都开一遍。
            // 这不是安全边界（真正的身份判定在 readInfo 的 modId 检查），只是省 IO。
            if (file.isFile() && looksLikeName(file.getName())) {
                sorted.add(file);
            }
        }
        sorted.sort((left, right) -> left.getName().compareToIgnoreCase(right.getName()));
        for (File file : sorted) {
            Info info = readInfo(file);
            if (info != null) {
                found.add(info);
            }
        }
        return found;
    }

    /** 文件名粗筛：{@code muoniumconfig-1.7.0.jar} / {@code MuoniumConfig-...jar} 都算。 */
    static boolean looksLikeName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".jar")) {
            return false;
        }
        // 去掉 .disabled / .old 之类后缀也无所谓：只要求以 muoniumconfig 开头。
        return lower.startsWith("muoniumconfig")
                || lower.startsWith("muonium_config")
                || lower.startsWith("muonium-config");
    }

    /**
     * 读一个 jar 的元数据，确认它是不是 MuoniumConfig。
     *
     * @return 是我们的 mod 时返回 {@link Info}；不是、或读不出来时返回 {@code null}
     */
    static Info readInfo(File jar) {
        if (jar == null || !jar.isFile() || jar.length() <= 0L) {
            return null;
        }
        try (ZipFile zip = new ZipFile(jar)) {
            for (String entry : METADATA_ENTRIES) {
                ZipEntry found = zip.getEntry(entry);
                if (found == null) {
                    continue;
                }
                String text = readEntry(zip, found);
                if (text == null) {
                    continue;
                }
                Metadata parsed = parse(text, entry.endsWith(".json"));
                if (parsed == null || !MOD_ID.equals(parsed.id)) {
                    // 元数据在、但 id 不是我们：这不是 MuoniumConfig（也可能是个撞名的）。
                    // 继续看下一种元数据文件，全都不是才判否。
                    continue;
                }
                return new Info(jar, parsed.version, parsed.minecraftRange);
            }
        } catch (Throwable broken) {
            // 坏包（半截下载、非法 zip）：跳过。启动路径不能因为一个坏文件中断。
            return null;
        }
        return null;
    }

    private static String readEntry(ZipFile zip, ZipEntry entry) {
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] buffer = new byte[Math.min(MAX_METADATA_BYTES, Math.max(4096, (int) entry.getSize()))];
            int total = 0;
            int read;
            while (total < buffer.length && (read = input.read(buffer, total, buffer.length - total)) > 0) {
                total += read;
            }
            if (total <= 0) {
                return null;
            }
            return new String(buffer, 0, total, StandardCharsets.UTF_8);
        } catch (Throwable broken) {
            return null;
        }
    }

    /** 从一份元数据里抠出来的三样东西。 */
    private static final class Metadata {
        final String id;
        final String version;
        final String minecraftRange;

        Metadata(String id, String version, String minecraftRange) {
            this.id = id;
            this.version = version;
            this.minecraftRange = minecraftRange;
        }
    }

    /**
     * 解析元数据。
     *
     * <p>{@code fabric.mod.json} 是 JSON，用正则抠字段（这里只需要 id/version，为它引入
     * 一个完整 JSON 解析器的收益为零，而且元数据本身不可信，宽松匹配反而更安全）；
     * {@code *.mods.toml} 是 TOML，取第一个 {@code [[mods]]} 段里的 modId 与 version。</p>
     */
    private static Metadata parse(String text, boolean json) {
        try {
            if (json) {
                // "id": "muoniumconfigv1" —— 允许空格与单双引号两种写法。
                String id = firstGroup(text, "\"id\"\\s*:\\s*\"([^\"]+)\"");
                String version = firstGroup(text, "\"version\"\\s*:\\s*\"([^\"]+)\"");
                // "minecraft":"26.3.x"（在 depends 里）
                String mc = firstGroup(text, "\"minecraft\"\\s*:\\s*\"([^\"]+)\"");
                return new Metadata(id, version, mc);
            }
            // TOML：只认 [[mods]] 段里的 modId / version，别把 [[dependencies]] 的 modId 当成自己。
            String modsBlock = firstGroup(text, "\\[\\[mods\\]\\]([\\s\\S]*?)(?:\\n\\[\\[|\\z)");
            if (modsBlock == null) {
                return null;
            }
            String id = firstGroup(modsBlock, "(?m)^\\s*modId\\s*=\\s*[\"']([^\"']+)[\"']");
            String version = firstGroup(modsBlock, "(?m)^\\s*version\\s*=\\s*[\"']([^\"']+)[\"']");
            String mc = firstGroup(text, "(?m)^\\s*modId\\s*=\\s*[\"']minecraft[\"'][\\s\\S]{0,400}?"
                    + "^\\s*versionRange\\s*=\\s*[\"']([^\"']+)[\"']");
            return new Metadata(id, version, mc);
        } catch (Throwable broken) {
            return null;
        }
    }

    private static String firstGroup(String text, String pattern) {
        if (text == null) {
            return "";
        }
        try {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern)
                    .matcher(text);
            if (matcher.find()) {
                String value = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
                return value == null ? "" : value.trim();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }
}
