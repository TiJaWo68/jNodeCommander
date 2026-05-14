package de.in.jnc.connection.filetransfer;

import java.awt.BorderLayout;
import java.awt.Dimension;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JToolBar;

/**
 * Dual-panel file transfer component with a toolbar for file operations.
 * <p>
 * Contains a {@link JSplitPane} with a {@link LocalFilePanel} on the left
 * and a {@link RemoteFilePanel} on the right, plus a {@link JToolBar} with
 * buttons for Copy, Move, Delete, Rename, and MkDir operations.
 */
public class FileTransferPanel extends JPanel {

    private final LocalFilePanel localPanel;
    private final RemoteFilePanel remotePanel;

    /**
     * Creates a new file transfer panel.
     *
     * @param sftpService the SFTP service for remote file operations
     */
    public FileTransferPanel(SftpService sftpService) {
        super(new BorderLayout());

        // Create panels
        localPanel = new LocalFilePanel();
        remotePanel = new RemoteFilePanel(sftpService);

        // Toolbar
        JToolBar toolBar = createToolBar();
        add(toolBar, BorderLayout.NORTH);

        // Split pane
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, localPanel, remotePanel);
        splitPane.setResizeWeight(0.5);
        splitPane.setDividerSize(6);
        splitPane.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        add(splitPane, BorderLayout.CENTER);

        setPreferredSize(new Dimension(900, 500));
    }

    /**
     * Creates the toolbar with file operation buttons.
     */
    private JToolBar createToolBar() {
        JToolBar toolBar = new JToolBar("File Operations");
        toolBar.setFloatable(false);

        // Copy → (Local → Remote)
        JButton copyToRemoteBtn = new JButton("F5: Copy \u2192");
        copyToRemoteBtn.setToolTipText("Copy selected local files to remote directory");
        copyToRemoteBtn.addActionListener(e ->
                FileOperations.copyLocalToRemote(localPanel, remotePanel, this));
        toolBar.add(copyToRemoteBtn);

        // Copy ← (Remote → Local)
        JButton copyToLocalBtn = new JButton("F5: \u2190 Copy");
        copyToLocalBtn.setToolTipText("Copy selected remote files to local directory");
        copyToLocalBtn.addActionListener(e ->
                FileOperations.copyRemoteToLocal(remotePanel, localPanel, this));
        toolBar.add(copyToLocalBtn);

        toolBar.addSeparator();

        // Delete (Local)
        JButton deleteLocalBtn = new JButton("F8: Del Local");
        deleteLocalBtn.setToolTipText("Delete selected local files");
        deleteLocalBtn.addActionListener(e ->
                FileOperations.deleteLocal(localPanel, this));
        toolBar.add(deleteLocalBtn);

        // Delete (Remote)
        JButton deleteRemoteBtn = new JButton("F8: Del Remote");
        deleteRemoteBtn.setToolTipText("Delete selected remote files");
        deleteRemoteBtn.addActionListener(e ->
                FileOperations.deleteRemote(remotePanel, this));
        toolBar.add(deleteRemoteBtn);

        toolBar.addSeparator();

        // Rename (Local)
        JButton renameLocalBtn = new JButton("Rename Local");
        renameLocalBtn.setToolTipText("Rename selected local file/directory");
        renameLocalBtn.addActionListener(e ->
                FileOperations.renameLocal(localPanel, this));
        toolBar.add(renameLocalBtn);

        // Rename (Remote)
        JButton renameRemoteBtn = new JButton("Rename Remote");
        renameRemoteBtn.setToolTipText("Rename selected remote file/directory");
        renameRemoteBtn.addActionListener(e ->
                FileOperations.renameRemote(remotePanel, this));
        toolBar.add(renameRemoteBtn);

        toolBar.addSeparator();

        // MkDir (Local)
        JButton mkdirLocalBtn = new JButton("F7: MkDir Local");
        mkdirLocalBtn.setToolTipText("Create new local directory");
        mkdirLocalBtn.addActionListener(e ->
                FileOperations.mkdirLocal(localPanel, this));
        toolBar.add(mkdirLocalBtn);

        // MkDir (Remote)
        JButton mkdirRemoteBtn = new JButton("F7: MkDir Remote");
        mkdirRemoteBtn.setToolTipText("Create new remote directory");
        mkdirRemoteBtn.addActionListener(e ->
                FileOperations.mkdirRemote(remotePanel, this));
        toolBar.add(mkdirRemoteBtn);

        toolBar.addSeparator();

        // Refresh
        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.setToolTipText("Refresh both panels");
        refreshBtn.addActionListener(e -> {
            localPanel.refresh();
            remotePanel.refresh();
        });
        toolBar.add(refreshBtn);

        return toolBar;
    }

    /**
     * Returns the local file panel.
     *
     * @return the local panel
     */
    public LocalFilePanel getLocalPanel() {
        return localPanel;
    }

    /**
     * Returns the remote file panel.
     *
     * @return the remote panel
     */
    public RemoteFilePanel getRemotePanel() {
        return remotePanel;
    }

    /**
     * Refreshes both panels.
     */
    public void refresh() {
        localPanel.refresh();
        remotePanel.refresh();
    }
}
