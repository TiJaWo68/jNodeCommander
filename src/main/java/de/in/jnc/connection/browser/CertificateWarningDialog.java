package de.in.jnc.connection.browser;

import java.awt.Color;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.cuberact.swing.layout.Cell;
import org.cuberact.swing.layout.Composite;

/**
 * Modal dialog that shows certificate details and asks the user whether to accept an untrusted server certificate.
 * <p>
 * Thread-safe: can be instantiated and shown from any thread; the dialog is always opened on the EDT and blocks the calling thread via
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
	 * @return {@code true} if the user accepted the certificate, {@code false} if rejected
	 */
	public static boolean prompt(String parentTitle, X509Certificate[] chain, String targetUrl) {
		AtomicBoolean result = new AtomicBoolean(false);
		CountDownLatch latch = new CountDownLatch(1);

		SwingUtilities.invokeLater(() -> {
			JDialog dialog = new JDialog();
			dialog.setTitle(WARNING_ICON + "  Ungültiges SSL-Zertifikat");
			dialog.setModal(true);
			dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
			dialog.setLocationRelativeTo(null);

			dialog.setContentPane(createContentPanel(targetUrl, chain, result, latch));

			dialog.pack();

			// Clamp dialog height – cuberact Composite may not respect
			// setPreferredSize() on JScrollPane, causing the dialog to grow
			// ~1500px tall with long certificate text.
			int maxHeight = 520;
			if (dialog.getHeight() > maxHeight) {
				dialog.setSize(dialog.getWidth(), maxHeight);
			}

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

	/**
	 * Creates the cuberact Composite content panel for the warning dialog.
	 * <p>
	 * Package-private so {@code CertificateWarningDialogTest} can verify layout
	 * dimensions without duplicating the layout code.
	 *
	 * @param targetUrl the URL the user was trying to access (may be {@code null})
	 * @param chain     the server certificate chain (may be {@code null})
	 * @param result    atomic boolean to receive the user's decision
	 * @param latch     latch to signal when the user has decided
	 * @return the fully built content panel ({@link org.cuberact.swing.layout.Composite})
	 */
	static javax.swing.JComponent createContentPanel(
			String targetUrl,
			X509Certificate[] chain,
			java.util.concurrent.atomic.AtomicBoolean result,
			java.util.concurrent.CountDownLatch latch) {

		// Resolve LAF-aware colors for dark-theme compatibility
		Color textFg = UIManager.getColor("TextField.foreground");
		Color textBg = UIManager.getColor("TextField.background");
		if (textFg == null)
			textFg = Color.BLACK;
		if (textBg == null)
			textBg = Color.WHITE;

		// ── cuberact Composite layout ───────────────────────────────
		Composite composite = new Composite();
		composite.pad(16).align(Cell.TOP);
		composite.defaults().space(6);

		// ══════════════════ Row 0: Header ════════════════════════════
		JTextArea headerArea = new JTextArea(WARNING_ICON + "  Die Identität des Servers kann nicht " + "bestätigt werden");
		headerArea.setEditable(false);
		headerArea.setLineWrap(true);
		headerArea.setWrapStyleWord(true);
		headerArea.setFont(headerArea.getFont().deriveFont(14f));
		headerArea.setForeground(textFg);
		headerArea.setBackground(textBg);
		headerArea.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
		composite.addCell(headerArea).align(Cell.CENTER).fillX().space(0, 0, 0, 8);
		composite.row();

		// ══════════════════ Row 1: Explanation ═══════════════════════
		StringBuilder html = new StringBuilder(512);
		html.append("<html><body style='width:580px;'>");
		html.append("Der Server <b>").append(escapeHtml(targetUrl != null ? targetUrl : "?")).append("</b> verwendet ein SSL-Zertifikat, "
				+ "das von Ihrem Computer nicht als " + "vertrauenswürdig eingestuft wird. " + "Dies kann folgende Ursachen haben:");
		html.append("<ul style='margin:4px 0 0 0; padding-left:20px;'>");
		html.append("<li>Das Zertifikat ist selbst-signiert</li>");
		html.append("<li>Das Zertifikat wurde von einer internen CA " + "ausgestellt, der nicht vertraut wird</li>");
		html.append("<li>Der Servername stimmt nicht mit dem " + "Zertifikat überein</li>");
		html.append("</ul></body></html>");

		JLabel explanation = new JLabel(html.toString());
		explanation.setVerticalAlignment(JLabel.TOP);
		composite.addCell(explanation).align(Cell.LEFT).fillX().space(0, 0, 0, 8);
		composite.row();

		// ══════════════════ Row 2: Section header ════════════════════
		JLabel detailsSection = new JLabel("Zertifikatsdetails:");
		detailsSection.setFont(detailsSection.getFont().deriveFont(12f));
		composite.addCell(detailsSection).align(Cell.LEFT).space(0, 0, 0, 4);
		composite.row();

		// ══════════════════ Row 3: Certificate details ═══════════════
		StringBuilder details = new StringBuilder();
		if (chain != null && chain.length > 0) {
			X509Certificate cert = chain[0];
			SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm");
			details.append("Subject:      ").append(cert.getSubjectDN()).append('\n');
			details.append("Aussteller:   ").append(cert.getIssuerDN()).append('\n');
			details.append("Gültig von:   ").append(sdf.format(cert.getNotBefore())).append('\n');
			details.append("Gültig bis:   ").append(sdf.format(cert.getNotAfter())).append('\n');
			details.append("Algorithmus:  ").append(cert.getSigAlgName()).append('\n');
			details.append("SHA-256:\n").append(formatFingerprint(cert)).append('\n');
			if (chain.length > 1) {
				details.append("\nKette (").append(chain.length).append(" Zertifikate):\n");
				for (int i = 1; i < chain.length; i++) {
					details.append("  [").append(i).append("] ").append(chain[i].getSubjectDN()).append('\n');
				}
			}
		} else {
			details.append("(Keine Zertifikatsdetails verfügbar)");
		}

		JTextArea detailsArea = new JTextArea(details.toString());
		detailsArea.setEditable(false);
		detailsArea.setLineWrap(true);
		detailsArea.setWrapStyleWord(true);
		detailsArea.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12));
		detailsArea.setForeground(textFg);
		detailsArea.setBackground(textBg);
		detailsArea.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")),
				BorderFactory.createEmptyBorder(4, 6, 4, 6)));

		JScrollPane scrollPane = new JScrollPane(detailsArea);
		scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setPreferredSize(new java.awt.Dimension(600, 200));

		composite.addCell(scrollPane).fillX().space(0, 0, 8, 0);
		composite.row();

		// ══════════════════ Row 4: Buttons ═══════════════════════════
		JButton rejectBtn = new JButton("Ablehnen");
		rejectBtn.addActionListener(e -> {
			result.set(false);
			// find parent dialog and close it
			javax.swing.SwingUtilities.getWindowAncestor(rejectBtn).dispose();
			latch.countDown();
		});

		JButton acceptBtn = new JButton("Trotzdem vertrauen");
		acceptBtn.setBackground(new Color(0x4C, 0xAF, 0x50));
		acceptBtn.setForeground(Color.WHITE);
		acceptBtn.setOpaque(true);
		acceptBtn.setBorderPainted(false);
		acceptBtn.addActionListener(e -> {
			result.set(true);
			javax.swing.SwingUtilities.getWindowAncestor(acceptBtn).dispose();
			latch.countDown();
		});

		Composite buttonRow = new Composite();
		buttonRow.defaults().space(8);
		buttonRow.addCell(new JLabel()).fillX(); // left spacer
		buttonRow.addCell(rejectBtn);
		buttonRow.addCell(acceptBtn);
		composite.addCell(buttonRow).align(Cell.RIGHT).fillX(); // let it fill

		// ── Wrap up ────────────────────────────────────────────────
		composite.setOpaque(false);
		return composite;
	}

	private static String escapeHtml(String text) {
		return text.replace("&", "&").replace("<", "<").replace(">", ">");
	}

	/**
	 * Formats the SHA-256 fingerprint in multiple lines (8 bytes per line) so it never overflows the dialog width.
	 */
	private static String formatFingerprint(X509Certificate cert) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest(cert.getEncoded());
			StringBuilder sb = new StringBuilder(96);
			for (int i = 0; i < digest.length; i++) {
				if (i > 0 && i % 8 == 0) {
					sb.append('\n');
				} else if (i > 0) {
					sb.append(':');
				}
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
