package de.in.jnc;

import java.awt.BorderLayout;
import java.io.File;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cuberact.swing.layout.Cell;
import org.cuberact.swing.layout.Composite;

import com.formdev.flatlaf.extras.FlatSVGIcon;

import de.in.jnc.terminal.SshConnection;
import de.in.jnc.terminal.TerminalFrame;

/**
 * Dialog for entering SSH connection details.
 */
public class ConnectionDialog extends JDialog {

	private static final Logger LOGGER = LogManager.getLogger(ConnectionDialog.class);

	private final JTextField hostField = new JTextField();
	private final JSpinner portSpinner = new JSpinner(new SpinnerNumberModel(22, 1, 65535, 1));
	private final JTextField userField = new JTextField();
	private final JPasswordField passwordField = new JPasswordField();
	private final JTextField keyField = new JTextField();
	private final JButton connectBtn = new JButton("Connect");
	private final JButton saveBtn = new JButton(); // Floppy button

	private String loadedProfileId = null;

	public ConnectionDialog() {
		this(null);
	}

	public ConnectionDialog(ConnectionProfile profile) {
		setTitle("Connect to Host");
		setModal(false);
		setResizable(false);
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);

		FlatSVGIcon svgIcon = new FlatSVGIcon("jnc.svg", 32, 32);
		setIconImage(svgIcon.getImage());

		initUI();

		if (profile != null) {
			loadProfile(profile);
		}

		validateInput();
		pack();
		setLocationRelativeTo(null);
	}

	private void loadProfile(ConnectionProfile profile) {
		this.loadedProfileId = profile.getId();
		hostField.setText(profile.getHost());
		portSpinner.setValue(profile.getPort());
		userField.setText(profile.getUser());
		
		if (profile.getEncryptedPassword() != null && !profile.getEncryptedPassword().isEmpty()) {
			String dec = CryptoUtil.decrypt(profile.getEncryptedPassword());
			if (dec != null) {
				passwordField.setText(dec);
			}
		}
		
		if (profile.getKeyFilePath() != null) {
			keyField.setText(profile.getKeyFilePath());
		}
	}

	private void initUI() {
		Composite composite = new Composite();
		composite.pad(20);
		composite.defaults().space(2); // Default small vertical space

		// Row 0: Labels
		composite.addCell("Host Name (or IP address)").align(Cell.LEFT).space(0, 10, 0, 15);
		composite.addCell("Port").align(Cell.LEFT);
		composite.row();

		// Row 1: Inputs
		composite.addCell(hostField).width(300).fillX().space(0, 10, 15, 15);
		composite.addCell(portSpinner).width(80).space(0, 0, 15, 0);
		composite.row();

		// Row 2: Labels for Username & Password
		JPanel userPassLabels = new JPanel(new java.awt.GridLayout(1, 2, 10, 0));
		userPassLabels.setOpaque(false);
		userPassLabels.add(new JLabel("User Name"));
		userPassLabels.add(new JLabel("Password"));
		composite.addCell(userPassLabels).colspan(2).fillX().space(0, 0, 0, 15);
		composite.row();

		// Row 3: Inputs for Username & Password
		JPanel userPassInputs = new JPanel(new java.awt.GridLayout(1, 2, 15, 0));
		userPassInputs.setOpaque(false);
		userPassInputs.add(userField);
		userPassInputs.add(passwordField);
		composite.addCell(userPassInputs).colspan(2).fillX().space(0, 0, 15, 15);
		composite.row();

		// Row 4: Label
		composite.addCell("Private Key File").colspan(2).align(Cell.LEFT).space(0, 0, 0, 15);
		composite.row();

		// Row 5: Input
		JPanel keyPanel = new JPanel(new BorderLayout(5, 0));
		JButton browseBtn = new JButton("...");
		browseBtn.addActionListener(e -> onBrowseKey());
		keyPanel.add(keyField, BorderLayout.CENTER);
		keyPanel.add(browseBtn, BorderLayout.EAST);
		composite.addCell(keyPanel).colspan(2).fillX().space(0, 0, 0, 15);

		// Row 6: Buttons
		composite.row().space(25);
		
		saveBtn.setIcon(UIManager.getIcon("FileView.floppyDriveIcon"));
		saveBtn.setToolTipText("Save Profile");
		saveBtn.addActionListener(e -> promptSaveProfile(false));

		Composite buttonComposite = new Composite();
		buttonComposite.defaults().space(10);
		JButton cancelBtn = new JButton("Cancel");

		connectBtn.addActionListener(e -> onConnect());
		cancelBtn.addActionListener(e -> dispose());

		// Add Save btn to the left, Connect/Cancel to the right
		composite.addCell(saveBtn).align(Cell.LEFT);
		
		buttonComposite.addCell(connectBtn).prefWidth(110);
		buttonComposite.addCell(cancelBtn).prefWidth(110);
		composite.addCell(buttonComposite).align(Cell.RIGHT);

		add(composite, BorderLayout.CENTER);
		getRootPane().setDefaultButton(connectBtn);

		addValidationListeners();
	}

	private void promptSaveProfile(boolean isFromConnect) {
		String defaultName = userField.getText() + "@" + hostField.getText();
		if (defaultName.equals("@")) defaultName = "New Profile";
		
		if (loadedProfileId != null) {
			for (ConnectionProfile p : ProfileManager.getInstance().getProfiles()) {
				if (p.getId().equals(loadedProfileId)) {
					defaultName = p.getName();
					break;
				}
			}
		}
		
		JTextField nameField = new JTextField(defaultName);
		Object[] message;
		if (isFromConnect) {
			message = new Object[]{"M\u00f6chtest du diese Verbindung als Profil speichern?", nameField};
		} else {
			message = new Object[]{"Profilname:", nameField};
		}
		
		int optionType = isFromConnect ? JOptionPane.YES_NO_OPTION : JOptionPane.OK_CANCEL_OPTION;
		int option = JOptionPane.showConfirmDialog(this, message, "Profil speichern", optionType, JOptionPane.QUESTION_MESSAGE);
		
		if (option == JOptionPane.YES_OPTION || option == JOptionPane.OK_OPTION) {
			String name = nameField.getText();
			if (name != null && !name.trim().isEmpty()) {
				saveProfile(name);
			}
		}
	}

	private void saveProfile(String name) {
		ConnectionProfile p = new ConnectionProfile();
		if (loadedProfileId != null) {
			p.setId(loadedProfileId);
		}
		p.setName(name);
		p.setHost(hostField.getText());
		p.setPort((Integer) portSpinner.getValue());
		p.setUser(userField.getText());
		
		if (AppEnv.isSavePasswordsEnabled()) {
			String pass = new String(passwordField.getPassword());
			p.setEncryptedPassword(CryptoUtil.encrypt(pass));
		}
		
		p.setKeyFilePath(keyField.getText());
		p.setLastUsed(System.currentTimeMillis());
		
		ProfileManager.getInstance().addOrUpdateProfile(p);
		this.loadedProfileId = p.getId();
	}

	private void addValidationListeners() {
		DocumentListener listener = new DocumentListener() {
			public void insertUpdate(DocumentEvent e) {
				validateInput();
			}

			public void removeUpdate(DocumentEvent e) {
				validateInput();
			}

			public void changedUpdate(DocumentEvent e) {
				validateInput();
			}
		};
		hostField.getDocument().addDocumentListener(listener);
		userField.getDocument().addDocumentListener(listener);
		passwordField.getDocument().addDocumentListener(listener);
		keyField.getDocument().addDocumentListener(listener);
	}

	private void validateInput() {
		boolean hasHost = !hostField.getText().trim().isEmpty();
		boolean hasUser = !userField.getText().trim().isEmpty();
		boolean hasCredential = passwordField.getPassword().length > 0 || !keyField.getText().trim().isEmpty();
		connectBtn.setEnabled(hasHost && hasUser && hasCredential);
		saveBtn.setEnabled(hasHost && hasUser);
	}

	private void onBrowseKey() {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Select Private Key");
		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
			File selectedFile = chooser.getSelectedFile();
			keyField.setText(selectedFile.getAbsolutePath());
		}
	}

	private void onConnect() {
		if (loadedProfileId == null) {
			promptSaveProfile(true);
		} else {
			ProfileManager.getInstance().getProfiles().stream()
				.filter(p -> p.getId().equals(loadedProfileId))
				.findFirst()
				.ifPresent(p -> {
					p.setLastUsed(System.currentTimeMillis());
					ProfileManager.getInstance().addOrUpdateProfile(p);
				});
		}

		final String host = hostField.getText().trim();
		final int port = (Integer) portSpinner.getValue();
		final String user = userField.getText().trim();
		final String password = new String(passwordField.getPassword());
		final String keyFilePath = keyField.getText().trim();

		LOGGER.info("Connection requested: {}@{}:{}", user, host, port);

		// Disable the dialog to prevent double-click
		connectBtn.setEnabled(false);
		connectBtn.setText("Connecting...");

		SwingWorker<TerminalFrame, Void> worker = new SwingWorker<>() {
			@Override
			protected TerminalFrame doInBackground() throws Exception {
				SshConnection sshConnection = new SshConnection(
						host, port, user,
						password.isEmpty() ? null : password,
						keyFilePath.isEmpty() ? null : keyFilePath);

				// Blocking I/O on background thread
				sshConnection.connect();
				LOGGER.info("SSH connection established, creating terminal UI");

				// Create TerminalFrame (Swing constructor must be on EDT)
				TerminalFrame terminalFrame = new TerminalFrame(
						user + "@" + host, sshConnection);
				return terminalFrame;
			}

			@Override
			protected void done() {
				try {
					TerminalFrame terminalFrame = get(); // re-throws any exception from doInBackground

					// Start JediTerm and show window on the EDT
					SwingUtilities.invokeLater(() -> {
						terminalFrame.getTerminalWidget().start();
						terminalFrame.setVisible(true);
						LOGGER.info("Terminal window opened for {}", terminalFrame.getSshConnection());
					});

				} catch (Exception e) {
					LOGGER.error("Failed to establish SSH connection: {}", e.getMessage());
					// Show error dialog on the EDT
					SwingUtilities.invokeLater(() ->
							JOptionPane.showMessageDialog(ConnectionDialog.this,
									"Connection failed:\n" + e.getMessage(),
									"SSH Error",
									JOptionPane.ERROR_MESSAGE));
				} finally {
					// Re-enable dialog so user can retry
					connectBtn.setEnabled(true);
					connectBtn.setText("Connect");
				}
			}
		};
		worker.execute();
	}
}
