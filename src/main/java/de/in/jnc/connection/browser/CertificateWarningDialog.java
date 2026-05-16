package de.in.jnc.connection.browser;

import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/**
 * Modal dialog that shows certificate details and asks the user whether to
 * accept an untrusted server certificate.
 * <p>
 * Thread-safe: can be instantiated and shown from any thread; the dialog is
 * always opened on the EDT and blocks the calling thread via
 * {@link CountDownLatch} until the user decides.
 */
public class CertificateWarningDialog {

    private static final String WARNING_ICON = "\u26A0\uFE0F"; // ⚠️

    /**
     * Shows the certificate warning dialog and blocks until the user responds.
     *
     * @param parentTitle title of the parent window (or {@code null})
     * @param chain       the server certificate chain
     * @param targetUrl   the URL the user was trying to access
     * @return {@code true} if the user accepted the certificate,
     *         {@code false} if rejected
     */
    public static boolean prompt(String parentTitle, X509Certificate[] chain, String targetUrl) {
        AtomicBoolean result = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(() -> {
            JDialog dialog = new JDialog();
            dialog.setTitle(WARNING_ICON + "  Ungültiges SSL-Zertifikat");
            dialog.setModal(true);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            dialog.setSize(560, 480);
            dialog.setLocationRelativeTo(null);

            JPanel content = new JPanel();
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

            // Warning header
            JLabel header = new JLabel(WARNING_ICON + "  Die Identität des Servers kann nicht bestätigt werden");
            header.setFont(header.getFont().deriveFont(14f));
            header.setAlignmentX(0.5f);
            content.add(header);
            content.add(Box.createVerticalStrut(12));

            // Explanation
            JLabel explanation = new JLabel(
                    "<html><body style='width:500px;'>"
                    + "Der Server <b>" + escapeHtml(targetUrl != null ? targetUrl : "?") + "</b> "
                    + "verwendet ein SSL-Zertifikat, das von Ihrem Computer nicht als "
                    + "vertrauenswürdig eingestuft wird. Dies kann folgende Ursachen haben:"
                    + "<ul>"
                    + "<li>Das Zertifikat ist selbst-signiert</li>"
                    + "<li>Das Zertifikat wurde von einer internen CA ausgestellt, "
                    + "der nicht vertraut wird</li>"
                    + "<li>Der Servername stimmt nicht mit dem Zertifikat überein</li>"
                    + "</ul>"
                    + "</body></html>");
            content.add(explanation);
            content.add(Box.createVerticalStrut(12));

            // Certificate details
            JLabel detailsHeader = new JLabel("Zertifikatsdetails:");
            detailsHeader.setFont(detailsHeader.getFont().deriveFont(12f));
            content.add(detailsHeader);
            content.add(Box.createVerticalStrut(4));

            StringBuilder details = new StringBuilder();
            if (chain != null && chain.length > 0) {
                X509Certificate cert = chain[0];
                SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm");
                details.append("Subject:     ").append(cert.getSubjectDN()).append('\n');
                details.append("Aussteller:  ").append(cert.getIssuerDN()).append('\n');
                details.append("Gültig von:  ").append(sdf.format(cert.getNotBefore())).append('\n');
                details.append("Gültig bis:  ").append(sdf.format(cert.getNotAfter())).append('\n');
                details.append("Algorithmus: ").append(cert.getSigAlgName()).append('\n');
                details.append("SHA-256:     ").append(formatFingerprint(cert)).append('\n');
                if (chain.length > 1) {
                    details.append("\nKette (").append(chain.length).append(" Zertifikate):\n");
                    for (int i = 1; i < chain.length; i++) {
                        details.append("  [").append(i).append("] ")
                               .append(chain[i].getSubjectDN()).append('\n');
                    }
                }
            } else {
                details.append("(Keine Zertifikatsdetails verfügbar)");
            }

            JTextArea detailsArea = new JTextArea(details.toString());
            detailsArea.setEditable(false);
            detailsArea.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 11));
            detailsArea.setBackground(new java.awt.Color(240, 240, 240));
            detailsArea.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

            JScrollPane scrollPane = new JScrollPane(detailsArea);
            scrollPane.setPreferredSize(new java.awt.Dimension(520, 180));
            content.add(scrollPane);
            content.add(Box.createVerticalStrut(16));

            // Buttons
            JPanel buttonPanel = new JPanel();
            buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.X_AXIS));
            buttonPanel.add(Box.createHorizontalGlue());

            JButton rejectBtn = new JButton("Ablehnen");
            rejectBtn.addActionListener(e -> {
                result.set(false);
                dialog.dispose();
                latch.countDown();
            });

            JButton acceptBtn = new JButton("Trotzdem vertrauen");
            acceptBtn.setBackground(new java.awt.Color(0x4C, 0xAF, 0x50));
            acceptBtn.setForeground(java.awt.Color.WHITE);
            acceptBtn.setOpaque(true);
            acceptBtn.setBorderPainted(false);
            acceptBtn.addActionListener(e -> {
                result.set(true);
                dialog.dispose();
                latch.countDown();
            });

            buttonPanel.add(rejectBtn);
            buttonPanel.add(Box.createHorizontalStrut(12));
            buttonPanel.add(acceptBtn);
            content.add(buttonPanel);

            dialog.setContentPane(content);
            dialog.setVisible(true);
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return result.get();
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&").replace("<", "<").replace(">", ">");
    }

    private static String formatFingerprint(X509Certificate cert) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(cert.getEncoded());
            StringBuilder sb = new StringBuilder(64);
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format("%02X", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "(Fehler)";
        }
    }

    private CertificateWarningDialog() {
        // utility class
    }
}
