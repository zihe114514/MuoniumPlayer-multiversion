package com.muoniumplayer.core.ncm.music;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 磁盘音频缓存的文件系统规则。
 *
 * <p>本类只负责缓存文件的校验与规范化。下载、
 * 解码进度、播放状态和音质保留策略仍归
 * 播放流程管理。</p>
 */
final class AudioCacheFiles {

    private AudioCacheFiles() {
    }

    /**
     * AAC/M4A 解码出的 WAV 边车的落点。
     *
     * <p>下载缓存里的文件边车就落在缓存目录旁边（同目录、同名 + {@code .decoded.wav}），
     * 与往常一致。<b>本地音乐文件</b>（用户磁盘上的原文件，见 {@code LocalMusicLibrary}）
     * 绝不能在用户的音乐目录里写边车——那是用户的只读资产。它的边车按「绝对路径的稳定
     * 哈希」落进模组自己的解码缓存目录，两个不同目录里的同名歌也不会互相顶掉。</p>
     */
    static File getDecodedWavFile(File sourceFile) {
        String name = sourceFile.getName();
        int extensionIndex = name.lastIndexOf('.');
        String baseName = extensionIndex > 0 ? name.substring(0, extensionIndex) : name;
        File parent = sourceFile.getParentFile();
        if (parent != null && !isManagedCacheDirectory(parent)) {
            // 用户自己的音频目录：边车改落模组缓存，键是源文件绝对路径的哈希。
            String sidecarKey = stableSidecarKey(sourceFile);
            File sidecarDir = new File(com.muoniumplayer.core.settings.ConfigPaths.DECODED_WAV_DIR,
                    sidecarKey);
            return new File(sidecarDir, baseName + ".decoded.wav");
        }
        return new File(parent == null ? new File(".") : parent, baseName + ".decoded.wav");
    }

    /** 源文件是不是活在模组自己管理的缓存目录里（下载缓存 / 本地封面 / 解码缓存）。 */
    private static boolean isManagedCacheDirectory(File directory) {
        try {
            String path = directory.getCanonicalPath();
            String managed = com.muoniumplayer.core.settings.ConfigPaths.CACHE_DIR.getCanonicalPath();
            return path.startsWith(managed + File.separator) || path.equals(managed);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String stableSidecarKey(File sourceFile) {
        String identity;
        try {
            identity = sourceFile.getCanonicalPath().toLowerCase(java.util.Locale.ROOT);
        } catch (Throwable ignored) {
            identity = sourceFile.getAbsolutePath().toLowerCase(java.util.Locale.ROOT);
        }
        byte[] bytes = identity.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long hash = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            hash ^= b & 0xffL;
            hash *= 0x100000001b3L;
        }
        return Long.toUnsignedString(hash & Long.MAX_VALUE);
    }

    static boolean isReusableDecodedWav(File sourceFile, File decodedFile) {
        return decodedFile.isFile()
                && decodedFile.length() > 44L
                && decodedFile.lastModified() >= sourceFile.lastModified()
                && "wav".equals(AudioContainerSupport.detectContainer(decodedFile));
    }

    static File findCachedAudioFile(File musicCacheDir, String cacheKey, String reportedType) {
        List<String> candidateTypes = new ArrayList<>();
        if (AudioContainerSupport.isSupportedContainer(reportedType)) {
            candidateTypes.add(reportedType);
        }
        for (String supportedType : AudioContainerSupport.getSupportedContainers()) {
            if (!candidateTypes.contains(supportedType)) {
                candidateTypes.add(supportedType);
            }
        }

        for (String candidateType : candidateTypes) {
            File candidate = new File(musicCacheDir, cacheKey + "." + candidateType);
            if (!candidate.isFile()) {
                continue;
            }

            String actualType = AudioContainerSupport.detectContainer(candidate);
            if (!AudioContainerSupport.isSupportedContainer(actualType)) {
                candidate.delete();
                continue;
            }

            File normalized = new File(musicCacheDir, cacheKey + "." + actualType);
            if (candidate.equals(normalized)) {
                return candidate;
            }

            if (normalized.isFile()) {
                String normalizedType = AudioContainerSupport.detectContainer(normalized);
                if (actualType.equals(normalizedType)) {
                    candidate.delete();
                    return normalized;
                }
                normalized.delete();
            }

            moveCacheFile(candidate, normalized);
            return normalized;
        }
        return null;
    }

    static void moveCacheFile(File source, File destination) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to finalize music cache file: " + source.getName(), e);
        }
    }
}
