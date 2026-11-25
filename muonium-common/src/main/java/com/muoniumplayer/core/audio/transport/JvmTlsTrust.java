package com.muoniumplayer.core.audio.transport;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

/** Exports the JVM's configured trusted CAs for native FFmpeg TLS verification. No network access. */
public final class JvmTlsTrust {
    private JvmTlsTrust() { }

    public static Path export(Path directory) throws IOException {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            List<X509Certificate> certificates = new ArrayList<>();
            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager trust) {
                    certificates.addAll(List.of(trust.getAcceptedIssuers()));
                }
            }
            return export(directory, certificates);
        } catch (GeneralSecurityException failure) {
            throw new IOException("Unable to read the configured JVM TLS trust store", failure);
        }
    }

    static Path export(Path directory, List<X509Certificate> certificates) throws IOException {
        if (certificates.isEmpty()) throw new IOException("The JVM TLS trust store contains no trusted certificates");
        try {
            List<String> encoded = new ArrayList<>();
            Base64.Encoder encoder = Base64.getMimeEncoder(64, new byte[]{'\n'});
            for (X509Certificate certificate : certificates) {
                encoded.add("-----BEGIN CERTIFICATE-----\n" + encoder.encodeToString(certificate.getEncoded())
                        + "\n-----END CERTIFICATE-----\n");
            }
            encoded = encoded.stream().distinct().sorted(Comparator.naturalOrder()).toList();
            byte[] pem = String.join("", encoded).getBytes(StandardCharsets.US_ASCII);
            String digest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pem));
            Files.createDirectories(directory);
            Path destination = directory.resolve("jvm-trust-" + digest + ".pem");
            if (Files.isRegularFile(destination) && MessageDigest.isEqual(Files.readAllBytes(destination), pem)) {
                return destination.toAbsolutePath();
            }
            Path temporary = Files.createTempFile(directory, "jvm-trust-", ".part");
            try {
                Files.write(temporary, pem);
                try {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            return destination.toAbsolutePath();
        } catch (CertificateEncodingException | java.security.NoSuchAlgorithmException failure) {
            throw new IOException("Unable to export the configured JVM TLS trust store", failure);
        }
    }
}
