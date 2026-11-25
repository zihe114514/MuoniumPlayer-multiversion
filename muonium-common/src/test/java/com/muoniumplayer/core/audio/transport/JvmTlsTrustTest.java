package com.muoniumplayer.core.audio.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JvmTlsTrustTest {
    @TempDir Path temporary;

    @Test
    void exportsRealJvmTrustRootsAndReusesTheIdenticalBundle() throws Exception {
        Path first = JvmTlsTrust.export(temporary);
        byte[] pem = Files.readAllBytes(first);
        var certificates = CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(pem));
        assertFalse(certificates.isEmpty());
        long modified = Files.getLastModifiedTime(first).toMillis();
        assertEquals(first, JvmTlsTrust.export(temporary));
        assertArrayEquals(pem, Files.readAllBytes(first));
        assertEquals(modified, Files.getLastModifiedTime(first).toMillis());
    }

    @Test
    void refusesAnEmptyTrustStoreRatherThanDisablingVerification() {
        assertThrows(java.io.IOException.class, () -> JvmTlsTrust.export(temporary, List.of()));
    }
}
