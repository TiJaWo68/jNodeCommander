package de.in.jnc.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.in.jnc.terminal.JncActionProvider.Credential;

/**
 * Tests for {@link JncActionProvider}.
 */
class JncActionProviderTest {

    // ── parseCredentialNames ─────────────────────────────────────────────

    @Test
    void parseCredentialNamesReturnsEmptyForNull() {
        assertTrue(JncActionProvider.parseCredentialNames(null).isEmpty());
    }

    @Test
    void parseCredentialNamesReturnsEmptyForBlank() {
        assertTrue(JncActionProvider.parseCredentialNames("").isEmpty());
        assertTrue(JncActionProvider.parseCredentialNames("   ").isEmpty());
        assertTrue(JncActionProvider.parseCredentialNames("\n\n").isEmpty());
    }

    @Test
    void parseCredentialNamesReturnsEmptyWhenNoSupportedCredentialsHeader() {
        String output = """
                Usage: /usr/bin/show_credentials <credential>
                Some random output
                """;
        assertTrue(JncActionProvider.parseCredentialNames(output).isEmpty());
    }

    @Test
    void parseCredentialNamesSingleEntry() {
        String output = """
                Usage: /usr/bin/show_credentials <credential>

                Supported credentials
                    admin          KeyCloak super user
                """;
        List<String> names = JncActionProvider.parseCredentialNames(output);
        assertEquals(1, names.size());
        assertEquals("admin", names.get(0));
    }

    @Test
    void parseCredentialNamesMultipleEntries() {
        String output = """
                Usage: /usr/bin/show_credentials <credential>

                Supported credentials
                    admin          KeyCloak super user
                    du_admin       Admin user for DeepUnity Platform
                    dicom_admin    Admin user for DeepUnity DICOM Services
                """;
        List<String> names = JncActionProvider.parseCredentialNames(output);
        assertEquals(3, names.size());
        assertEquals("admin", names.get(0));
        assertEquals("du_admin", names.get(1));
        assertEquals("dicom_admin", names.get(2));
    }

    @Test
    void parseCredentialNamesHandlesCaseInsensitiveHeader() {
        String output = """
                SUPPORTED CREDENTIALS
                    backup_user    User for backup share
                """;
        List<String> names = JncActionProvider.parseCredentialNames(output);
        assertEquals(1, names.size());
        assertEquals("backup_user", names.get(0));
    }

    @Test
    void parseCredentialNamesSkipsUsageLinesInSection() {
        String output = """
                Supported credentials
                    admin          KeyCloak super user
                usage: some other command
                    extra          Another user
                """;
        List<String> names = JncActionProvider.parseCredentialNames(output);
        assertEquals(2, names.size());
        assertEquals("admin", names.get(0));
        assertEquals("extra", names.get(1));
    }

    @Test
    void parseCredentialNamesReturnsEmptyForOnlyUsageLines() {
        String output = """
                Usage: /usr/bin/show_credentials <credential>

                Supported credentials
                """;
        List<String> names = JncActionProvider.parseCredentialNames(output);
        assertTrue(names.isEmpty());
    }

    // ── parseUserPassword ────────────────────────────────────────────────

    @Test
    void parseUserPasswordReturnsNullForNull() {
        assertNull(JncActionProvider.parseUserPassword(null));
    }

    @Test
    void parseUserPasswordReturnsNullForBlank() {
        assertNull(JncActionProvider.parseUserPassword(""));
        assertNull(JncActionProvider.parseUserPassword("   "));
        assertNull(JncActionProvider.parseUserPassword("\n\n"));
    }

    @Test
    void parseUserPasswordBothFields() {
        String output = """
                User: du_admin
                Password: fYCXwS84QnUf
                """;
        Credential credential = JncActionProvider.parseUserPassword(output);
        assertNotNull(credential);
        assertEquals("du_admin", credential.username());
        assertEquals("fYCXwS84QnUf", credential.password());
    }

    @Test
    void parseUserPasswordOnlyUsername() {
        String output = "User: jdoe\n";
        Credential credential = JncActionProvider.parseUserPassword(output);
        assertNotNull(credential);
        assertEquals("jdoe", credential.username());
        assertEquals("", credential.password());
    }

    @Test
    void parseUserPasswordOnlyPassword() {
        String output = "Password: s3cret\n";
        Credential credential = JncActionProvider.parseUserPassword(output);
        assertNotNull(credential);
        assertEquals("", credential.username());
        assertEquals("s3cret", credential.password());
    }

    @Test
    void parseUserPasswordCaseInsensitiveKeys() {
        String output = """
                USER: admin
                PASSWORD: secret123
                """;
        Credential credential = JncActionProvider.parseUserPassword(output);
        assertNotNull(credential);
        assertEquals("admin", credential.username());
        assertEquals("secret123", credential.password());
    }

    @Test
    void parseUserPasswordExtraLinesAreIgnored() {
        String output = """
                Some random output
                [sudo] password for node-admin:
                User: du_admin
                Password: fYCXwS84QnUf
                Extra info
                """;
        Credential credential = JncActionProvider.parseUserPassword(output);
        assertNotNull(credential);
        assertEquals("du_admin", credential.username());
        assertEquals("fYCXwS84QnUf", credential.password());
    }

    @Test
    void parseUserPasswordTrimsWhitespace() {
        String output = """
                User:   admin
                Password:   secret
                """;
        Credential credential = JncActionProvider.parseUserPassword(output);
        assertNotNull(credential);
        assertEquals("admin", credential.username());
        assertEquals("secret", credential.password());
    }

    @Test
    void parseUserPasswordReturnsNullWhenNoMatch() {
        String output = """
                error: some error
                another line without colon
                """;
        assertNull(JncActionProvider.parseUserPassword(output));
    }

    // ── getActions ───────────────────────────────────────────────────────

    @Test
    void getActionsReturnsCredentialsAction() {
        JncActionProvider provider = new JncActionProvider(null, s -> {
        });
        var actions = provider.getActions();
        assertNotNull(actions);
        assertEquals(1, actions.size(), "Should have exactly one action: Credentials...");
        assertEquals("Credentials...", actions.get(0).getName());
    }

    // ── Credential record ────────────────────────────────────────────────

    @Test
    void credentialRecordStoresValues() {
        Credential credential = new Credential("admin", "s3cret");
        assertEquals("admin", credential.username());
        assertEquals("s3cret", credential.password());
    }
}
