package de.in.jnc.connection.filetransfer;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FileEntry}.
 */
class FileEntryTest {

    @Test
    void constructorShouldSetAllFields() {
        FileEntry entry = new FileEntry("test.txt", false, 1024, 1_700_000_000_000L, "-rw-r--r--");

        assertEquals("test.txt", entry.getName());
        assertFalse(entry.isDirectory());
        assertEquals(1024, entry.getSize());
        assertEquals(1_700_000_000_000L, entry.getLastModified());
        assertEquals("-rw-r--r--", entry.getPermissions());
    }

    @Test
    void constructorShouldAcceptNullPermissions() {
        FileEntry entry = new FileEntry("test.txt", false, 0, 0, null);

        assertEquals("", entry.getPermissions());
    }

    @Test
    void constructorShouldRejectNullName() {
        assertThrows(NullPointerException.class, () ->
                new FileEntry(null, false, 0, 0, ""));
    }

    @Test
    void directoryEntryShouldHaveCorrectType() {
        FileEntry entry = new FileEntry("folder", true, 0, 0, "drwxr-xr-x");

        assertTrue(entry.isDirectory());
    }

    // ─── getFormattedSize ────────────────────────────────────────────────

    @Test
    void formattedSizeForDirectoryShouldBeDir() {
        FileEntry entry = new FileEntry("folder", true, 4096, 0, "drwxr-xr-x");

        assertEquals("<DIR>", entry.getFormattedSize());
    }

    @Test
    void formattedSizeForBytes() {
        FileEntry entry = new FileEntry("small.txt", false, 500, 0, "-rw-r--r--");

        assertEquals("500 B", entry.getFormattedSize());
    }

    @Test
    void formattedSizeForKilobytes() {
        FileEntry entry = new FileEntry("medium.txt", false, 2048, 0, "-rw-r--r--");

        assertEquals("2.0 KB", entry.getFormattedSize());
    }

    @Test
    void formattedSizeForMegabytes() {
        FileEntry entry = new FileEntry("large.bin", false, 14_000_000, 0, "-rw-r--r--");

        assertEquals("13.4 MB", entry.getFormattedSize());
    }

    @Test
    void formattedSizeForGigabytes() {
        FileEntry entry = new FileEntry("huge.iso", false, 3_200_000_000L, 0, "-rw-r--r--");

        assertEquals("3.0 GB", entry.getFormattedSize());
    }

    @Test
    void formattedSizeForZeroBytes() {
        FileEntry entry = new FileEntry("empty.txt", false, 0, 0, "-rw-r--r--");

        assertEquals("0 B", entry.getFormattedSize());
    }

    // ─── equals / hashCode ──────────────────────────────────────────────

    @Test
    void equalEntriesShouldBeEqual() {
        FileEntry a = new FileEntry("file.txt", false, 1024, 1000, "-rw-r--r--");
        FileEntry b = new FileEntry("file.txt", false, 1024, 1000, "-rw-r--r--");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differentEntriesShouldNotBeEqual() {
        FileEntry a = new FileEntry("file.txt", false, 1024, 1000, "-rw-r--r--");
        FileEntry b = new FileEntry("other.txt", false, 1024, 1000, "-rw-r--r--");

        assertNotEquals(a, b);
    }

    @Test
    void entryShouldEqualItself() {
        FileEntry entry = new FileEntry("self.txt", false, 0, 0, "");

        assertEquals(entry, entry);
    }

    @Test
    void entryShouldNotEqualNull() {
        FileEntry entry = new FileEntry("test.txt", false, 0, 0, "");

        assertNotEquals(null, entry);
    }

    // ─── toString ───────────────────────────────────────────────────────

    @Test
    void toStringShouldReturnName() {
        FileEntry entry = new FileEntry("mylog.log", false, 500, 0, "-rw-r--r--");

        assertEquals("mylog.log", entry.toString());
    }
}
