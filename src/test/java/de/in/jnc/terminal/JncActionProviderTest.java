package de.in.jnc.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link JncActionProvider}.
 * <p>
 * Parsing and credential logic tests have been moved to
 * {@link CredentialsServiceTest}. This test class covers only the
 * action provider's own behaviour.
 */
class JncActionProviderTest {

    // ── getActions ───────────────────────────────────────────────────────

    @Test
    void getActionsReturnsCredentialsAction() {
        CredentialsService service = new CredentialsService();
        JncActionProvider provider = new JncActionProvider(service, s -> {});
        var actions = provider.getActions();
        assertNotNull(actions);
        assertEquals(1, actions.size(), "Should have exactly one action: Credentials...");
        assertEquals("Credentials...", actions.get(0).getName());
    }
}
