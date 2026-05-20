package de.in.jnc.connection.browser.backend.jcef;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cef.CefApp;
import org.cef.CefSettings;

import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.CefInitializationException;
import me.friwi.jcefmaven.UnsupportedPlatformException;
import me.friwi.jcefmaven.impl.progress.ConsoleProgressHandler;

/**
 * Manages the one-time initialisation of the JCEF runtime ({@link CefApp}).
 * <p>
 * {@link #initialize()} is called lazily – the first time a
 * {@link JCEFBackend} is created. It:
 * <ol>
 *   <li>Uses jcefmaven's {@link CefAppBuilder} to download and extract
 *       JCEF native libraries into {@code ~/.jnc/jcef-natives/}</li>
 *   <li>Configures {@link CefSettings} (OSR mode, cache path, user agent)</li>
 *   <li>Starts the CEF message loop via {@link CefAppBuilder#build()}</li>
 * </ol>
 * <p>
 * <b>Thread-safety:</b> All access is guarded by {@code synchronized} methods
 * and an {@link AtomicBoolean} flag.  A single {@link CefApp} instance is
 * shared by all {@link JCEFBackend} instances.
 */
public final class JCEFInitializer {

    private static final Logger LOGGER = LogManager.getLogger(JCEFInitializer.class);

    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    /**
     * Sub-directory under {@code ~/.jnc/} used for extracted JCEF native
     * libraries (DLLs, .so, .dylib).
     */
    private static final String JCEF_NATIVES_SUBDIR = "jcef-natives";

    /**
     * Sub-directory under {@code ~/.jnc/} used for JCEF's browser cache
     * (cookies, localStorage, etc.).
     */
    private static final String JCEF_CACHE_SUBDIR = "jcef-cache";

    private JCEFInitializer() {
        // utility class
    }

    /**
     * Starts the JCEF runtime if it has not already been started.
     * <p>
     * Uses jcefmaven's {@link CefAppBuilder} to handle native library
     * download, extraction, and platform-specific setup automatically.
     * <p>
     * Safe to call multiple times – only the first call has any effect.
     *
     * @throws IllegalStateException if JCEF initialisation fails
     */
    public static synchronized void initialize() {
        if (initialized.get()) {
            return;
        }

        LOGGER.info("Initialising JCEF runtime via jcefmaven...");

        try {
            CefAppBuilder builder = new CefAppBuilder();

            // ── Native library extraction directory ────────────────────
            Path nativesPath = Paths.get(
                    System.getProperty("user.home"), ".jnc", JCEF_NATIVES_SUBDIR);
            builder.setInstallDir(nativesPath.toFile());

            // ── Progress feedback during native download ───────────────
            builder.setProgressHandler(new ConsoleProgressHandler());

            // ── CEF Settings ───────────────────────────────────────────
            CefSettings settings = builder.getCefSettings();

            // Windowed mode (non-OSR) – the browser renders to a native
            // AWT Canvas that is wrapped in a Swing JPanel.
            // OSR mode is not used to avoid JOGL/OpenGL compatibility issues
            // on multi-GPU systems.
            settings.windowless_rendering_enabled = false;

            // Persistent cache for cookies, localStorage, etc.
            Path cachePath = Paths.get(
                    System.getProperty("user.home"), ".jnc", JCEF_CACHE_SUBDIR);
            settings.cache_path = cachePath.toAbsolutePath().toString();

            // User-agent – keep the application name so that servers can
            // identify JCEF traffic.
            settings.user_agent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/130.0.0.0 Safari/537.36 "
                    + "JNodeCommander/"
                    + System.getProperty("jnc.version", "0.0.1");

            // ── JCEF command-line arguments (Chromium flags) ──────────
            // No sandbox is required for embedded usage (CEF sub-processes
            // must be able to communicate with the main JVM process).
            builder.addJcefArgs("--no-sandbox");

            // ── Build CefApp (downloads natives + starts CEF) ──────────
            CefApp cefApp = builder.build();
            LOGGER.info("JCEF initialised successfully (natives={}, cache={})",
                    nativesPath, cachePath);

            if (cefApp == null) {
                throw new IllegalStateException(
                        "CefAppBuilder.build() returned null");
            }

            initialized.set(true);
        } catch (IOException e) {
            LOGGER.error("Failed to download/extract JCEF native libraries", e);
            throw new IllegalStateException(
                    "JCEF native library setup failed", e);
        } catch (UnsupportedPlatformException e) {
            LOGGER.error("JCEF is not supported on this platform", e);
            throw new IllegalStateException(
                    "JCEF unsupported platform", e);
        } catch (CefInitializationException e) {
            LOGGER.error("JCEF runtime initialisation failed", e);
            throw new IllegalStateException(
                    "JCEF initialisation failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "JCEF initialisation was interrupted", e);
        }
    }

    /**
     * Returns {@code true} if the JCEF runtime has been successfully started.
     */
    public static boolean isInitialized() {
        return initialized.get();
    }

    /**
     * Shuts down the JCEF runtime gracefully.
     * <p>
     * Should be called during application shutdown (e.g. from a JVM shutdown
     * hook) so that CEF can persist its cache and release native resources.
     */
    public static synchronized void shutdown() {
        if (!initialized.get()) {
            return;
        }
        try {
            CefApp.getInstance().dispose();
            initialized.set(false);
            LOGGER.info("JCEF shut down");
        } catch (Exception e) {
            LOGGER.error("Error during JCEF shutdown", e);
        }
    }
}
