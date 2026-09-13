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

    static File getDecodedWavFile(File sourceFile) {
        String name = sourceFile.getName();
        int extensionIndex = name.lastIndexOf('.');
        String baseName = extensionIndex > 0 ? name.substring(0, extensionIndex) : name;
        return new File(sourceFile.getParentFile(), baseName + ".decoded.wav");
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
