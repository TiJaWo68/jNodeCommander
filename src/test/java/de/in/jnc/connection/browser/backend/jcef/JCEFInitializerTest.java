package de.in.jnc.connection.browser.backend.jcef;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JCEFInitializer}.
 * <p>
 * These tests verify the state-management logic of the initialiser
 * (the {@code AtomicBoolean} flag, early-exit paths, exception safety).
 * The actual JCEF initialisation path ({@link JCEFInitializer#initialize()}
 * that calls native code) is tested separately via
 * {@link JCEFDebugTest} which must be run with the JCEF native libraries
 * on the classpath.
 * </p>
 */
class JCEFInitializerTest {

    /**
     * Before each test we reset the internal {@code initialized} flag
     * via reflection so that tests are isolated from each other.
     */
    @BeforeEach
    @AfterEach
    void resetInitializedFlag() throws Exception {
        Field field = JCEFInitializer.class.getDeclaredField("initialized");
        field.setAccessible(true);
        AtomicBoolean flag = (AtomicBoolean) field.get(null);
        flag.set(false);
    }

    @Test
    void isInitialized_returnsFalse_byDefault() {
        assertFalse(JCEFInitializer.isInitialized(),
                "isInitialized() should be false before any call to initialize()");
    }

    @Test
    void shutdown_whenNotInitialized_doesNotThrow() {
        assertDoesNotThrow(JCEFInitializer::shutdown,
                "shutdown() must be a no-op when JCEF has not been started");
    }

    @Test
    void shutdown_whenNotInitialized_leavesFlagFalse() {
        JCEFInitializer.shutdown();
        assertFalse(JCEFInitializer.isInitialized(),
                "isInitialized() must remain false after shutdown() when never initialized");
    }

    @Test
    void isInitialized_returnsFalse_afterShutdownOfUninitialized() {
        // sanity – repeated shutdown calls are also safe
        JCEFInitializer.shutdown();
        JCEFInitializer.shutdown();
        assertFalse(JCEFInitializer.isInitialized());
    }
}
