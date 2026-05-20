package de.in.jnc.connection.browser;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.cert.X509Certificate;

/**
 * Provides mock {@link X509Certificate} instances for unit tests.
 * <p>
 * The mock certificates are sufficient for testing:
 * <ul>
 *   <li>{@link CertificateTrustManager#fingerprint(X509Certificate)}</li>
 *   <li>{@link CertificateTrustManager#isAlreadyAccepted(X509Certificate[])}</li>
 *   <li>{@link CertificateTrustManager#rememberAccepted(X509Certificate[])}</li>
 * </ul>
 * They do NOT require any external CA, BouncyCastle, or {@code --add-opens}
 * JVM flags.
 */
public final class TestCertificates {

    private TestCertificates() {
        // utility class
    }

    /**
     * Creates a mock X.509 certificate with a deterministic encoded form.
     * <p>
     * Different calls with different {@code seed} values produce certificates
     * with different fingerprints.
     *
     * @param seed a byte array that determines the certificate's encoded form
     *             (and thus its fingerprint)
     * @return a mock {@link X509Certificate}
     * @throws Exception should never happen (all mocks)
     */
    public static X509Certificate createSelfSignedCert(byte[] seed) throws Exception {
        X509Certificate cert = mock(X509Certificate.class);
        when(cert.getEncoded()).thenReturn(seed.clone());
        when(cert.getSubjectDN()).thenReturn(
                new javax.security.auth.x500.X500Principal("CN=Test " + seed.length));
        return cert;
    }

    /**
     * Convenience: creates a mock certificate with a default seed.
     */
    public static X509Certificate createSelfSignedCert() throws Exception {
        return createSelfSignedCert(new byte[]{
                0x30, (byte) 0x82, 0x05, (byte) 0xA2, // start of SEQUENCE (mock DER)
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08
        });
    }

    /**
     * Creates a different certificate (different seed → different fingerprint).
     */
    public static X509Certificate createOtherCert() throws Exception {
        return createSelfSignedCert(new byte[]{
                (byte) 0xFF, (byte) 0xEE, (byte) 0xDD, (byte) 0xCC,
                (byte) 0xBB, (byte) 0xAA, 0x00, 0x11, 0x22, 0x33
        });
    }
}
