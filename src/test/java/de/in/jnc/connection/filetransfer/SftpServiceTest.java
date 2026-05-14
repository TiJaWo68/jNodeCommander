package de.in.jnc.connection.filetransfer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.FileMode;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.sftp.SFTPException;

/**
 * Unit tests for {@link SftpService} using mocked SSHJ {@link SFTPClient}.
 */
@ExtendWith(MockitoExtension.class)
class SftpServiceTest {

    @Mock
    private SFTPClient sftpClient;

    @Mock
    private RemoteResourceInfo fileInfo;

    @Mock
    private RemoteResourceInfo dirInfo;

    @Mock
    private FileAttributes fileAttrs;

    @Mock
    private FileAttributes dirAttrs;

    @Mock
    private FileMode fileMode;

    @Mock
    private FileMode dirMode;

    private SftpService sftpService;

    @BeforeEach
    void setUp() {
        sftpService = new SftpService(sftpClient);
    }

    @Test
    void constructorShouldSetConnected() {
        assertTrue(sftpService.isConnected());
    }

    // ─── listFiles ───────────────────────────────────────────────────────

    @Test
    void listFilesShouldReturnEntries() throws IOException {
        when(sftpClient.ls("/home/user")).thenReturn(List.of(fileInfo, dirInfo));

        // File mock
        when(fileInfo.getName()).thenReturn("readme.txt");
        when(fileInfo.getAttributes()).thenReturn(fileAttrs);
        when(fileAttrs.getType()).thenReturn(FileMode.Type.REGULAR);
        when(fileAttrs.getSize()).thenReturn(1024L);
        when(fileAttrs.getMtime()).thenReturn(1_700_000_000L);
        when(fileAttrs.getMode()).thenReturn(fileMode);
        when(fileMode.getMask()).thenReturn(0x1A4); // -rw-r-----

        // Directory mock
        when(dirInfo.getName()).thenReturn("docs");
        when(dirInfo.getAttributes()).thenReturn(dirAttrs);
        when(dirAttrs.getType()).thenReturn(FileMode.Type.DIRECTORY);
        when(dirAttrs.getSize()).thenReturn(0L);
        when(dirAttrs.getMtime()).thenReturn(1_700_000_000L);
        when(dirAttrs.getMode()).thenReturn(dirMode);
        when(dirMode.getMask()).thenReturn(0x1ED); // drwxr-xr-x

        List<FileEntry> entries = sftpService.listFiles("/home/user");

        assertEquals(2, entries.size());

        FileEntry file = entries.get(0);
        assertEquals("readme.txt", file.getName());
        assertFalse(file.isDirectory());
        assertEquals(1024, file.getSize());

        FileEntry dir = entries.get(1);
        assertEquals("docs", dir.getName());
        assertTrue(dir.isDirectory());
    }

    @Test
    void listFilesShouldSkipDotEntries() throws IOException {
        when(fileInfo.getName()).thenReturn(".");
        when(dirInfo.getName()).thenReturn("..");
        when(sftpClient.ls("/")).thenReturn(List.of(fileInfo, dirInfo));

        List<FileEntry> entries = sftpService.listFiles("/");

        assertTrue(entries.isEmpty());
    }

    @Test
    void listFilesShouldThrowOnInvalidPath() throws IOException {
        when(sftpClient.ls("/nonexistent")).thenThrow(new SFTPException("No such file"));

        assertThrows(IOException.class, () -> sftpService.listFiles("/nonexistent"));
    }

    // ─── upload ──────────────────────────────────────────────────────────

    @Test
    void uploadShouldDelegateToSftpClient() throws IOException {
        sftpService.upload("C:\\local\\file.txt", "/remote/file.txt");

        verify(sftpClient).put("C:\\local\\file.txt", "/remote/file.txt");
    }

    @Test
    void uploadShouldThrowOnFailure() throws IOException {
        doThrow(new SFTPException("Permission denied"))
                .when(sftpClient).put(anyString(), anyString());

        assertThrows(IOException.class, () ->
                sftpService.upload("/local/fail.txt", "/remote/fail.txt"));
    }

    // ─── download ────────────────────────────────────────────────────────

    @Test
    void downloadShouldDelegateToSftpClient() throws IOException {
        sftpService.download("/remote/file.txt", "C:\\local\\file.txt");

        verify(sftpClient).get("/remote/file.txt", "C:\\local\\file.txt");
    }

    @Test
    void downloadShouldThrowOnFailure() throws IOException {
        doThrow(new SFTPException("File not found"))
                .when(sftpClient).get(anyString(), anyString());

        assertThrows(IOException.class, () ->
                sftpService.download("/remote/missing.txt", "/local/missing.txt"));
    }

    // ─── delete ──────────────────────────────────────────────────────────

    @Test
    void deleteFileShouldCallRm() throws IOException {
        sftpService.delete("/remote/file.txt");

        verify(sftpClient).rm("/remote/file.txt");
    }

    @Test
    void deleteDirectoryShouldCallRmdir() throws IOException {
        doThrow(new SFTPException("Is a directory"))
                .when(sftpClient).rm("/remote/folder");

        sftpService.delete("/remote/folder");

        verify(sftpClient).rmdir("/remote/folder");
    }

    // ─── rename ──────────────────────────────────────────────────────────

    @Test
    void renameShouldDelegateToSftpClient() throws IOException {
        sftpService.rename("/old/name.txt", "/new/name.txt");

        verify(sftpClient).rename("/old/name.txt", "/new/name.txt");
    }

    // ─── mkdir ───────────────────────────────────────────────────────────

    @Test
    void mkdirShouldDelegateToSftpClient() throws IOException {
        sftpService.mkdir("/remote/newfolder");

        verify(sftpClient).mkdir("/remote/newfolder");
    }

    // ─── getDefaultDir ───────────────────────────────────────────────────

    @Test
    void getDefaultDirShouldReturnCanonicalPath() throws IOException {
        when(sftpClient.canonicalize(".")).thenReturn("/home/testuser");

        assertEquals("/home/testuser", sftpService.getDefaultDir());
    }

    // ─── close ───────────────────────────────────────────────────────────

    @Test
    void closeShouldCloseSftpClientAndUpdateState() throws IOException {
        sftpService.close();

        assertFalse(sftpService.isConnected());
        verify(sftpClient).close();
    }

    @Test
    void closeMultipleTimesShouldOnlyCloseOnce() throws IOException {
        sftpService.close();
        sftpService.close();

        verify(sftpClient, times(1)).close();
    }
}
