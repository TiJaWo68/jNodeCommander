---
title: "Story 3.2: Dual-Pane File Transfer Interface (Norton Commander Style)"
labels: enhancement, epic3
assignees: ""
---

## Story 3.2: File Transfer Interface

**Epic 3:** The Three Pillars (Tools Integration)

### Description

Build a dual-pane file transfer interface inside a new `ConnectionFrame` (which replaces the existing `TerminalFrame`). The UI follows the Norton Commander style:

- **Left panel**: Local filesystem (via `java.nio.file`)
- **Right panel**: Remote filesystem (via SSHJ's `SFTPClient`)
- **Toolbar**: Copy (→ / ←), Delete, Rename, MkDir

### Architecture Change

The existing `TerminalFrame` (single-terminal JFrame) is replaced by `ConnectionFrame`, a JFrame with a `JTabbedPane` containing:

| Tab | Content | Icon | Closable |
|-----|---------|------|----------|
| Terminal | JediTermWidget | `terminal.svg` | No (pinned) |
| File Transfer | FileTransferPanel (dual-pane) | `folder.svg` | No (pinned) |
| Browser (Future) | JCEF | — | TBD |

Multiple `ConnectionFrame` instances run in parallel, each with its own SSH connection.

### Tasks

#### Step 1: SFTP Foundation ✅
- [x] Add `getSFTPClient()` to `SshConnection` (opens SFTP channel on existing SSH connection)
- [x] Create `SftpService` wrapping SSHJ's `SFTPClient` (list, upload, download, delete, rename, mkdir, close)

#### Step 2: Data Model ✅
- [x] Create `FileEntry` (name, isDirectory, size, lastModified, permissions) with factory methods for local and remote entries
- [x] Create `FileTableModel` (extends AbstractTableModel) with sortable columns

#### Step 3: Panel Components ✅
- [x] Create `LocalFilePanel` (JTable + navigation via `java.nio.file`)
- [x] Create `RemoteFilePanel` (JTable + navigation via `SftpService`)

#### Step 4: File Operations ✅
- [x] Create `FileOperations` (copy local→remote, remote→local, delete, rename, mkdir)
- [x] Create `FileTransferPanel` (JSplitPane + LocalFilePanel + RemoteFilePanel + Toolbar)

#### Step 5: ConnectionFrame (replaces TerminalFrame) ✅
- [x] Create `ConnectionFrame` (JFrame with JTabbedPane)
- [x] Tab 0: Terminal (JediTermWidget, pinned)
- [x] Tab 1: File Transfer (FileTransferPanel, pinned)
- [x] Each tab has its own icon

#### Step 6: Resources ✅
- [x] Add `terminal.svg` icon
- [x] Add `folder.svg` icon

#### Step 7: Integration ✅
- [x] Update `ConnectionDialog` to create `ConnectionFrame` instead of `TerminalFrame`
- [x] Remove `TerminalFrame.java`

#### Step 8: Tests
- [ ] Unit tests for `FileEntry`, `FileTableModel`, `SftpService`
- [ ] Verify no compilation errors ✅

### Dependencies

- SSHJ 0.38.0 (already present — `SFTPClient` is built-in)
- No new Maven dependencies required

### Acceptance Criteria

- [x] Connecting opens `ConnectionFrame` with Terminal + File Transfer tabs
- [ ] Local file panel shows and navigates the local filesystem
- [ ] Remote file panel shows and navigates the remote filesystem via SFTP
- [ ] File operations work: Copy (both directions), Delete, Rename, MkDir
- [x] Multiple `ConnectionFrame` instances can run in parallel
- [x] Pinned tabs cannot be closed
