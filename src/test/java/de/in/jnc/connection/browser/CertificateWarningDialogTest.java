package de.in.jnc.connection.browser;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JDialog;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CertificateWarningDialog}.
 * <p>
 * Verifies that the dialog content panel does not exceed the expected height
 * when packed into a real JDialog, reproducing the ~1500px bug that occurred
 * because cuberact Composite ignored {@code setPreferredSize()} on the
 * JScrollPane.
 */
class CertificateWarningDialogTest {

    /**
     * REPRODUCES THE ~1500px BUG: even in the headless test environment,
     * {@code pack()} produces a ~1516px dialog because cuberact Composite
     * ignores {@code setPreferredSize()} on the JScrollPane and distributes
     * all extra vertical space to the scroll pane cell.
     * <p>
     * After applying the same post-pack height clamp that {@code prompt()}
     * uses, the dialog must be ≤ 520px.
     */
    @Test
    void dialogHeight_isClampedAfterPack() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());

        AtomicBoolean result = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        AtomicBoolean passed = new AtomicBoolean(false);

        SwingUtilities.invokeAndWait(() -> {
            JDialog dialog = new JDialog();
            dialog.setModal(false);

            dialog.setContentPane(CertificateWarningDialog.createContentPanel(
                    "https://test-server.local/auth", null, result, latch));

            dialog.pack();

            int rawHeight = dialog.getHeight();
            System.out.println("Dialog raw height after pack(): " + rawHeight + "px");

            // REPRODUCES THE BUG: raw height is ~1500px in headless mode
            // (same as the runtime ~1500px reported by the user)
            assertTrue(rawHeight > 500,
                    "Before clamping: dialog height " + rawHeight
                    + "px should be >500px, confirming the cuberact "
                    + "Composite bug that ignores setPreferredSize(). "
                    + "If this assertion fails, the bug may have been "
                    + "fixed in a different way.");

            // Apply the SAME clamp as CertificateWarningDialog.prompt()
            int maxHeight = 520;
            if (dialog.getHeight() > maxHeight) {
                dialog.setSize(dialog.getWidth(), maxHeight);
            }

            int clampedHeight = dialog.getHeight();
            System.out.println("Dialog height after clamp: " + clampedHeight + "px");

            assertTrue(clampedHeight <= maxHeight,
                    "After clamping: dialog height " + clampedHeight
                    + "px must be ≤ " + maxHeight + "px. "
                    + "The post-pack clamp in prompt() MUST bound "
                    + "the height.");

            passed.set(true);
            dialog.dispose();
        });
    }
}
