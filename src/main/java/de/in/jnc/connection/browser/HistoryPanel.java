package de.in.jnc.connection.browser;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;

import de.in.jnc.ConnectionProfile;
import de.in.jnc.ProfileManager;

/**
 * A Swing panel that displays browser history in a JTable with per-row delete, clear-all, and double-click to open URL.
 */
public final class HistoryPanel extends JPanel {

	private final HistoryTableModel tableModel;

	/**
	 * @param profile   the connection profile (source of history data)
	 * @param urlOpener callback to open a URL in a new browser tab
	 */
	public HistoryPanel(ConnectionProfile profile, Consumer<String> urlOpener) {
		super(new BorderLayout());
		tableModel = new HistoryTableModel(profile);

		JTable table = new JTable(tableModel);
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.getColumnModel().getColumn(0).setPreferredWidth(60);
		table.getColumnModel().getColumn(1).setPreferredWidth(400);
		table.getColumnModel().getColumn(2).setPreferredWidth(180);

		// Double-click → open URL in new browser tab
		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2) {
					int row = table.getSelectedRow();
					if (row >= 0) {
						String url = tableModel.getUrlAt(row);
						if (url != null && urlOpener != null) {
							urlOpener.accept(url);
						}
					}
				}
			}
		});

		JScrollPane scrollPane = new JScrollPane(table);
		scrollPane.setPreferredSize(new Dimension(800, 400));
		add(scrollPane, BorderLayout.CENTER);

		// Button bar
		JPanel buttonBar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
		JButton deleteBtn = new JButton("Delete Selected");
		deleteBtn.addActionListener(e -> {
			int row = table.getSelectedRow();
			if (row >= 0) {
				tableModel.removeEntry(row);
			}
		});
		JButton clearBtn = new JButton("Clear All");
		clearBtn.addActionListener(e -> {
			int confirm = JOptionPane.showConfirmDialog(this, "Delete all history entries?", "Clear History", JOptionPane.YES_NO_OPTION);
			if (confirm == JOptionPane.YES_OPTION) {
				tableModel.clearAll();
			}
		});
		buttonBar.add(deleteBtn);
		buttonBar.add(clearBtn);
		add(buttonBar, BorderLayout.SOUTH);
	}

	/** Refreshes the table from the profile (call on tab switch). */
	public void refresh() {
		tableModel.fireTableDataChanged();
	}

	private static final class HistoryTableModel extends AbstractTableModel {

		private final ConnectionProfile profile;

		HistoryTableModel(ConnectionProfile profile) {
			this.profile = profile;
		}

		@Override
		public int getRowCount() {
			List<HistoryEntry> h = profile.getHistory();
			return h != null ? h.size() : 0;
		}

		@Override
		public int getColumnCount() {
			return 3;
		}

		@Override
		public String getColumnName(int col) {
			return switch (col) {
			case 0 -> "#";
			case 1 -> "URL";
			case 2 -> "Time";
			default -> "";
			};
		}

		@Override
		public Object getValueAt(int row, int col) {
			List<HistoryEntry> h = profile.getHistory();
			if (h == null || row >= h.size())
				return "";
			HistoryEntry e = h.get(row);
			return switch (col) {
			case 0 -> row + 1;
			case 1 -> e.getTitle();
			case 2 -> e.getInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString().replace('T', ' ');
			default -> "";
			};
		}

		String getUrlAt(int row) {
			List<HistoryEntry> h = profile.getHistory();
			if (h != null && row < h.size()) {
				return h.get(row).getUrl();
			}
			return null;
		}

		void removeEntry(int row) {
			List<HistoryEntry> h = profile.getHistory();
			if (h != null && row < h.size()) {
				h.remove(row);
				ProfileManager.getInstance().addOrUpdateProfile(profile);
				fireTableDataChanged();
			}
		}

		void clearAll() {
			profile.getHistory().clear();
			ProfileManager.getInstance().addOrUpdateProfile(profile);
			fireTableDataChanged();
		}
	}
}
