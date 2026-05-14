package de.in.jnc.connection.filetransfer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FileTableModel}.
 */
class FileTableModelTest {

    private FileTableModel model;

    @BeforeEach
    void setUp() {
        model = new FileTableModel();
    }

    @Test
    void emptyModelShouldHaveZeroRows() {
        assertEquals(0, model.getRowCount());
    }

    @Test
    void columnCountShouldBeFour() {
        assertEquals(4, model.getColumnCount());
    }

    @Test
    void columnNamesShouldBeCorrect() {
        assertEquals("Name", model.getColumnName(0));
        assertEquals("Size", model.getColumnName(1));
        assertEquals("Last Modified", model.getColumnName(2));
        assertEquals("Permissions", model.getColumnName(3));
    }

    @Test
    void setEntriesShouldUpdateRowCount() {
        List<FileEntry> entries = List.of(
                new FileEntry("file1.txt", false, 100, 1000, "-rw-r--r--"),
                new FileEntry("file2.txt", false, 200, 2000, "-rw-r--r--"));

        model.setEntries(entries);

        assertEquals(2, model.getRowCount());
    }

    @Test
    void getValueAtShouldReturnCorrectColumnData() {
        List<FileEntry> entries = List.of(
                new FileEntry("readme.md", false, 2048, 1_700_000_000_000L, "-rw-r--r--"));

        model.setEntries(entries);

        assertEquals("readme.md", model.getValueAt(0, 0));
        assertTrue(model.getValueAt(0, 1).toString().contains("KB"));
        assertNotNull(model.getValueAt(0, 2));
        assertEquals("-rw-r--r--", model.getValueAt(0, 3));
    }

    @Test
    void directoryEntryShouldShowDirInSizeColumn() {
        List<FileEntry> entries = List.of(
                new FileEntry("docs", true, 0, 1000, "drwxr-xr-x"));

        model.setEntries(entries);

        assertEquals("<DIR>", model.getValueAt(0, 1));
    }

    @Test
    void getEntryShouldReturnCorrectEntry() {
        FileEntry entry = new FileEntry("target.txt", false, 512, 3000, "-rwx------");
        model.setEntries(List.of(entry));

        assertSame(entry, model.getEntry(0));
    }

    @Test
    void setEntriesShouldReplaceOldEntries() {
        model.setEntries(List.of(
                new FileEntry("old.txt", false, 10, 0, "")));

        model.setEntries(List.of(
                new FileEntry("new.txt", false, 20, 0, "")));

        assertEquals(1, model.getRowCount());
        assertEquals("new.txt", model.getValueAt(0, 0));
    }

    @Test
    void getEntriesShouldReturnImmutableCopy() {
        List<FileEntry> original = new ArrayList<>(List.of(
                new FileEntry("a.txt", false, 0, 0, "")));
        model.setEntries(original);

        List<FileEntry> retrieved = model.getEntries();
        assertEquals(1, retrieved.size());

        assertThrows(UnsupportedOperationException.class, () -> retrieved.add(
                new FileEntry("b.txt", false, 0, 0, "")));
    }

    // ─── sortDirectoriesFirst ────────────────────────────────────────────

    @Test
    void sortDirectoriesFirstWithParentShouldStartWithDoubleDot() {
        List<FileEntry> unsorted = List.of(
                new FileEntry("z_file.txt", false, 10, 0, ""),
                new FileEntry("a_folder", true, 0, 0, ""));

        List<FileEntry> sorted = FileTableModel.sortDirectoriesFirst(unsorted, true);

        assertEquals(3, sorted.size());
        assertEquals("..", sorted.get(0).getName());
        assertTrue(sorted.get(1).isDirectory());
        assertFalse(sorted.get(2).isDirectory());
    }

    @Test
    void sortDirectoriesFirstAlphabeticalOrder() {
        List<FileEntry> unsorted = List.of(
                new FileEntry("delta.txt", false, 10, 0, ""),
                new FileEntry("alpha.txt", false, 10, 0, ""),
                new FileEntry("beta.txt", false, 10, 0, ""));

        List<FileEntry> sorted = FileTableModel.sortDirectoriesFirst(unsorted, false);

        assertEquals("alpha.txt", sorted.get(0).getName());
        assertEquals("beta.txt", sorted.get(1).getName());
        assertEquals("delta.txt", sorted.get(2).getName());
    }

    @Test
    void sortDirectoriesFirstDirectoriesBeforeFiles() {
        List<FileEntry> unsorted = List.of(
                new FileEntry("readme.txt", false, 10, 0, ""),
                new FileEntry("images", true, 0, 0, ""));

        List<FileEntry> sorted = FileTableModel.sortDirectoriesFirst(unsorted, false);

        assertTrue(sorted.get(0).isDirectory());
        assertFalse(sorted.get(1).isDirectory());
    }

    @Test
    void sortDirectoriesFirstCaseInsensitive() {
        List<FileEntry> unsorted = List.of(
                new FileEntry("CASE.TXT", false, 10, 0, ""),
                new FileEntry("alpha.txt", false, 10, 0, ""));

        List<FileEntry> sorted = FileTableModel.sortDirectoriesFirst(unsorted, false);

        assertEquals("alpha.txt", sorted.get(0).getName());
        assertEquals("CASE.TXT", sorted.get(1).getName());
    }

    // ─── sort ────────────────────────────────────────────────────────────

    @Test
    void sortShouldReorderEntries() {
        model.setEntries(List.of(
                new FileEntry("b.txt", false, 10, 0, ""),
                new FileEntry("a.txt", false, 10, 0, "")));

        model.sort(java.util.Comparator.comparing(FileEntry::getName));

        assertEquals("a.txt", model.getEntry(0).getName());
        assertEquals("b.txt", model.getEntry(1).getName());
    }
}
