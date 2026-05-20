package de.in.jnc.connection.browser.backend.jcef;

import java.io.File;
import java.nio.file.Paths;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.EnumProgress;
import me.friwi.jcefmaven.IProgressHandler;

/**
 * Minimal test that exercises JCEF native library installation only,
 * printing progress to stdout for diagnostic purposes.
 * <p>
 * Run with:
 * <pre>{@code
 *   mvn exec:java "-Dexec.mainClass=de.in.jnc.connection.browser.backend.jcef.JCEFNativeInstallTest"
 * }</pre>
 */
public final class JCEFNativeInstallTest {

    private static final Logger LOGGER = LogManager.getLogger(JCEFNativeInstallTest.class);

    public static void main(String[] args) {
        System.out.println("=== JCEF Native Install Test ===");
        System.out.println("Starting native download & extraction...");

        try {
            CefAppBuilder builder = new CefAppBuilder();

            File installDir = Paths.get(
                    System.getProperty("user.home"), ".jnc", "jcef-natives").toFile();
            builder.setInstallDir(installDir);
            System.out.println("Install dir: " + installDir.getAbsolutePath());

            // Custom progress handler that prints to stdout
            builder.setProgressHandler(new IProgressHandler() {
                @Override
                public void handleProgress(EnumProgress state, float percent) {
                    System.out.printf("[JCEF] %s (%.1f%%)%n", state, percent * 100f);
                }
            });

            System.out.println("Calling builder.install()...");
            builder.install();
            System.out.println("Native installation complete!");

            System.out.println("Calling builder.build()...");
            builder.build();
            System.out.println("CefApp built successfully!");
            System.out.println("=== DONE ===");

        } catch (Exception e) {
            System.err.println("ERROR: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
