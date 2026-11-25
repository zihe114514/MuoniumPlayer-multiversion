package com.muoniumplayer.configskija.host.avatar;

import java.io.*;
import java.net.*;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPInputStream;

/** Optional original mc-heads UUID-only fallback. Never forwards authentication, names, cookies or profile properties. */
public final class AvatarWeb implements AvatarLoader.Web {
    public static final int CONNECT_TIMEOUT_MS = 5000, READ_TIMEOUT_MS = 5000;
    private static final String AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36";

    public static URI uri(UUID id) {
        return URI.create(
                "https://mc-heads.net/avatar/" + id.toString().replace("-", "") + "/32.png");
    }

    public byte[] fetch(UUID id, BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) throw new InterruptedIOException("Avatar request cancelled");
        HttpURLConnection connection = (HttpURLConnection) uri(id).toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", AGENT);
        connection.setRequestProperty("Accept", "image/png");
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("Avatar HTTP " + status);
            long length = connection.getContentLengthLong();
            if (length > 1_048_576) throw new IOException("Avatar response too large");
            try (InputStream raw = connection.getInputStream();
                    InputStream input =
                            "gzip".equalsIgnoreCase(connection.getContentEncoding())
                                    ? new GZIPInputStream(raw)
                                    : raw;
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                        throw new InterruptedIOException("Avatar request cancelled");
                    if (bytes.size() + read > 1_048_576)
                        throw new IOException("Avatar response too large");
                    bytes.write(buffer, 0, read);
                }
                return AvatarPng.validate(bytes.toByteArray());
            }
        } finally {
            connection.disconnect();
        }
    }
}
