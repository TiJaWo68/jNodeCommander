package de.in.jnc;

import java.awt.BorderLayout;
import java.awt.FlowLayout;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.border.EmptyBorder;

/**
 * Dialog to view, sort and delete saved connection profiles.
 */
public class ProfileManagerDialog extends JDialog {

	private final DefaultListModel<ConnectionProfile> listModel = new DefaultListModel<>();
	private final JList<ConnectionProfile> profileList = new JList<>(listModel);
	private final JButton deleteBtn = new JButton("Delete");
	private final JComboBox<ProfileManager.SortMode> sortBox = new JComboBox<>(ProfileManager.SortMode.values());
	private final JButton upBtn = new JButton("Up");
	private final JButton downBtn = new JButton("Down");
	private final Runnable onUpdateCallback;

	public ProfileManagerDialog(Runnable onUpdateCallback) {
		this.onUpdateCallback = onUpdateCallback;
		setTitle("Manage Profiles");
		setModal(true);
		setSize(380, 400);
		setLocationRelativeTo(null);
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		
		initUI();
		loadData();
	}

	private void initUI() {
		JPanel content = new JPanel(new BorderLayout(10, 10));
		content.setBorder(new EmptyBorder(10, 10, 10, 10));

		// Top panel with Sort Box
		JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
		topPanel.add(new JLabel("Sort:"));
		sortBox.setSelectedItem(ProfileManager.getInstance().getSortMode());
		sortBox.addActionListener(e -> {
			ProfileManager.getInstance().setSortMode((ProfileManager.SortMode) sortBox.getSelectedItem());
			loadData();
			if (onUpdateCallback != null) onUpdateCallback.run();
		});
		topPanel.add(sortBox);
		content.add(topPanel, BorderLayout.NORTH);

		// Center list
		profileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		profileList.addListSelectionListener(e -> updateButtonState());
		content.add(new JScrollPane(profileList), BorderLayout.CENTER);

		// Right panel for Up/Down
		JPanel rightPanel = new JPanel();
		rightPanel.setLayout(new BoxLayout(rightPanel, BoxLayout.Y_AXIS));
		upBtn.addActionListener(e -> moveSelected(-1));
		downBtn.addActionListener(e -> moveSelected(1));
		rightPanel.add(upBtn);
		rightPanel.add(Box.createVerticalStrut(5));
		rightPanel.add(downBtn);
		content.add(rightPanel, BorderLayout.EAST);

		// Bottom panel for Delete / Close
		JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton closeBtn = new JButton("Close");
		
		deleteBtn.setEnabled(false);
		deleteBtn.addActionListener(e -> onDelete());
		closeBtn.addActionListener(e -> dispose());
		
		btnPanel.add(deleteBtn);
		btnPanel.add(closeBtn);
		
		content.add(btnPanel, BorderLayout.SOUTH);
		add(content);
	}

	private void loadData() {
		listModel.clear();
		ProfileManager.getInstance().getProfiles().forEach(listModel::addElement);
		updateButtonState();
	}

	private void updateButtonState() {
		int sel = profileList.getSelectedIndex();
		deleteBtn.setEnabled(sel != -1);
		
		boolean isManual = (sortBox.getSelectedItem() == ProfileManager.SortMode.MANUAL);
		upBtn.setEnabled(isManual && sel > 0);
		downBtn.setEnabled(isManual && sel != -1 && sel < listModel.size() - 1);
	}

	private void moveSelected(int offset) {
		int sel = profileList.getSelectedIndex();
		if (sel == -1) return;
		int to = sel + offset;
		if (to < 0 || to >= listModel.size()) return;
		
		ProfileManager.getInstance().moveProfile(sel, to);
		loadData();
		profileList.setSelectedIndex(to);
		if (onUpdateCallback != null) onUpdateCallback.run();
	}

	private void onDelete() {
		ConnectionProfile selected = profileList.getSelectedValue();
		if (selected != null) {
			ProfileManager.getInstance().deleteProfile(selected.getId());
			loadData();
			if (onUpdateCallback != null) {
				onUpdateCallback.run(); // Notify TrayManager to rebuild menu
			}
		}
	}
}
