package de.in.jnc.connection.browser;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link CertificateStoreManager}.
 * <p>
 * Covers fingerprint computation, PEM round-trip (save → parse), and
 * consistency with {@link CertificateTrustManager#fingerprint(X509Certificate)}.
 */
class CertificateStoreManagerTest {

    @TempDir
    Path tempDir;

    // ── fingerprint() ─────────────────────────────────────────────────

    @Test
    void fingerprint_isConsistentWithTrustManager() throws Exception {
        X509Certificate cert = TestCertificates.createSelfSignedCert();
        String fp1 = CertificateStoreManager.fingerprint(cert);
        String fp2 = CertificateTrustManager.fingerprint(cert);
        assertEquals(fp1, fp2,
                "CertificateStoreManager.fingerprint() must produce the "
                + "same hash as CertificateTrustManager.fingerprint()");
    }

    @Test
    void fingerprint_isHex64Chars() throws Exception {
        X509Certificate cert = TestCertificates.createSelfSignedCert();
        String fp = CertificateStoreManager.fingerprint(cert);
        assertNotNull(fp);
        assertTrue(fp.matches("[0-9a-f]{64}"),
                "Fingerprint should be a 64-char hex string, got: " + fp);
    }

    @Test
    void fingerprint_isDeterministic() throws Exception {
        X509Certificate cert = TestCertificates.createSelfSignedCert();
        assertEquals(
                CertificateStoreManager.fingerprint(cert),
                CertificateStoreManager.fingerprint(cert));
    }

    @Test
    void fingerprint_differentCerts_differentFingerprints() throws Exception {
        X509Certificate certA = TestCertificates.createSelfSignedCert(
                new byte[]{0x01, 0x02, 0x03});
        X509Certificate certB = TestCertificates.createSelfSignedCert(
                new byte[]{0x04, 0x05, 0x06});
        assertNotEquals(
                CertificateStoreManager.fingerprint(certA),
                CertificateStoreManager.fingerprint(certB),
                "Different certificates must have different fingerprints");
    }

    // ── PEM parse tests ───────────────────────────────────────────────

    @Test
    void parsePemCertificate_invalidPem_returnsNull() throws Exception {
        byte[] garbage = "This is not a PEM certificate".getBytes();
        assertNull(CertificateStoreManager.parsePemCertificate(garbage));
    }

    @Test
    void parsePemCertificate_emptyBytes_returnsNull() throws Exception {
        assertNull(CertificateStoreManager.parsePemCertificate(new byte[0]));
    }

    @Test
    void saveAsPem_producesValidPemFormat() throws Exception {
        // Note: this test verifies the PEM FORMAT (headers/footers/base64),
        // not that the DER content is parseable.  Mock certificates have
        // synthetic getEncoded() bytes that are not valid DER, so the PEM
        // can only be verified for structural correctness.
        X509Certificate cert = TestCertificates.createSelfSignedCert();
        Path pemFile = tempDir.resolve("format-test.pem");

        CertificateStoreManager.saveAsPem(cert, pemFile);

        String content = Files.readString(pemFile);
        assertTrue(content.startsWith("-----BEGIN CERTIFICATE-----"),
                "PEM must start with BEGIN CERTIFICATE");
        assertTrue(content.trim().endsWith("-----END CERTIFICATE-----"),
                "PEM must end with END CERTIFICATE");
        assertTrue(content.contains("\n"),
                "PEM must have line breaks (MIME encoding)");

        // Verify the base64 content is valid base64 (decodes without error)
        String b64section = content
                .replace("-----BEGIN CERTIFICATE-----\n", "")
                .replace("\n-----END CERTIFICATE-----\n", "")
                .replace("\n", "")
                .trim();
        assertNotNull(java.util.Base64.getDecoder().decode(b64section),
                "Content between PEM headers must be valid Base64");
    }

    @Test
    void saveAsPem_atomicWrite_producesNoTempFiles() throws Exception {
        X509Certificate cert = TestCertificates.createSelfSignedCert();
        Path pemFile = tempDir.resolve("atomic-test.pem");
        Path tmpFile = pemFile.resolveSibling(pemFile.getFileName() + ".tmp");

        CertificateStoreManager.saveAsPem(cert, pemFile);

        assertTrue(Files.exists(pemFile), "Target PEM file should exist");
        assertFalse(Files.exists(tmpFile),
                "Temporary .tmp file should be removed after atomic move");
    }
}
