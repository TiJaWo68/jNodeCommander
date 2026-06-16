package de.in.jnc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;

import de.in.jnc.connection.browser.Bookmark;
import de.in.jnc.connection.browser.HistoryEntry;

public class ProfileManagerTest {

	private File tempFile;

	@BeforeEach
	public void setup() throws Exception {
		tempFile = File.createTempFile("profiles_test", ".json");
		tempFile.deleteOnExit();
		ProfileManager.resetInstance();
		ProfileManager.setProfilesFile(tempFile);
	}

	@AfterEach
	public void tearDown() {
		ProfileManager.resetInstance();
		if (tempFile != null && tempFile.exists()) {
			tempFile.delete();
		}
	}

	@Test
	public void testAddAndLoadProfile() throws Exception {
		ConnectionProfile p1 = new ConnectionProfile();
		p1.setName("Test Profile");
		p1.setHost("localhost");
		p1.setPort(2222);
		p1.setUser("testuser");

		ProfileManager.getInstance().addOrUpdateProfile(p1);
		assertEquals(1, ProfileManager.getInstance().getProfiles().size());

		// Simulate restart: reload from file
		ProfileManager.resetInstance();
		ProfileManager.setProfilesFile(tempFile);

		assertEquals(1, ProfileManager.getInstance().getProfiles().size());
		ConnectionProfile loaded = ProfileManager.getInstance().getProfiles().get(0);
		assertEquals("Test Profile", loaded.getName());
		assertEquals("localhost", loaded.getHost());
		assertEquals(2222, loaded.getPort());
		assertEquals("testuser", loaded.getUser());
	}

	// ── Bookmark serialization ────────────────────────────────────────

	@Test
	public void testBookmarksPersistAcrossRestart() {
		ConnectionProfile profile = new ConnectionProfile();
		profile.setName("BM-Test");
		profile.setHost("host1");
		profile.setPort(22);
		profile.setUser("u");

		profile.getBookmarks().add(new Bookmark("https://example.com", "Example", null));
		profile.getBookmarks().add(new Bookmark("https://foo.org/path", "Foo", null));

		ProfileManager.getInstance().addOrUpdateProfile(profile);

		// Simulate restart
		ProfileManager.resetInstance();
		ProfileManager.setProfilesFile(tempFile);

		List<ConnectionProfile> loaded = ProfileManager.getInstance().getProfiles();
		assertEquals(1, loaded.size());
		List<Bookmark> bookmarks = loaded.get(0).getBookmarks();
		assertEquals(2, bookmarks.size());
		assertEquals("https://example.com", bookmarks.get(0).getUrl());
		assertEquals("https://foo.org/path", bookmarks.get(1).getUrl());
	}

	@Test
	public void testEmptyBookmarksOnNewProfile() {
		ConnectionProfile profile = new ConnectionProfile();
		profile.setName("BM-Empty");
		assertNotNull(profile.getBookmarks());
		assertTrue(profile.getBookmarks().isEmpty());
	}

	// ── History serialization ─────────────────────────────────────────

	@Test
	public void testHistoryPersistsAcrossRestart() {
		ConnectionProfile profile = new ConnectionProfile();
		profile.setName("Hist-Test");
		profile.setHost("h");
		profile.setPort(22);
		profile.setUser("u");

		profile.getHistory().add(new HistoryEntry("https://a.com/page", "A Page"));
		profile.getHistory().add(new HistoryEntry("https://b.com", "B"));

		ProfileManager.getInstance().addOrUpdateProfile(profile);

		ProfileManager.resetInstance();
		ProfileManager.setProfilesFile(tempFile);

		List<ConnectionProfile> loaded = ProfileManager.getInstance().getProfiles();
		assertEquals(1, loaded.size());
		List<HistoryEntry> history = loaded.get(0).getHistory();
		assertEquals(2, history.size());
		assertEquals("https://a.com/page", history.get(0).getUrl());
		assertEquals("A Page", history.get(0).getTitle());
		assertEquals("https://b.com", history.get(1).getUrl());
	}

	/**
	 * This test reproduces the bug where getInstant() produced
	 * java.time.Instant, which Jackson cannot serialize without
	 * the jsr310 module. The fix was adding @JsonIgnore to getInstant().
	 */
	@Test
	public void testHistoryEntrySerializationDoesNotFail() {
		ConnectionProfile profile = new ConnectionProfile();
		profile.setName("Instant-Test");
		profile.setHost("h");
		profile.setPort(22);
		profile.setUser("u");

		HistoryEntry entry = new HistoryEntry("https://example.com", "Ex");
		profile.getHistory().add(entry);

		// This must not throw
		assertDoesNotThrow(() ->
				ProfileManager.getInstance().addOrUpdateProfile(profile));

		ProfileManager.resetInstance();
		ProfileManager.setProfilesFile(tempFile);

		List<HistoryEntry> loaded = ProfileManager.getInstance().getProfiles()
				.get(0).getHistory();
		assertEquals(1, loaded.size());
		assertEquals("https://example.com", loaded.get(0).getUrl());
	}

	@Test
	public void testBookmarkAndHistoryTogetherPersist() {
		ConnectionProfile profile = new ConnectionProfile();
		profile.setName("Both-Test");
		profile.setHost("h");
		profile.setPort(22);
		profile.setUser("u");

		profile.getBookmarks().add(new Bookmark("https://b.com", "B", null));
		profile.getHistory().add(new HistoryEntry("https://h.com", "H"));

		ProfileManager.getInstance().addOrUpdateProfile(profile);

		ProfileManager.resetInstance();
		ProfileManager.setProfilesFile(tempFile);

		ConnectionProfile loaded = ProfileManager.getInstance().getProfiles().get(0);
		assertEquals(1, loaded.getBookmarks().size());
		assertEquals(1, loaded.getHistory().size());
		assertEquals("https://b.com", loaded.getBookmarks().get(0).getUrl());
		assertEquals("https://h.com", loaded.getHistory().get(0).getUrl());
	}

	@Test
	public void testNullListsBecomeEmpty() {
		// Simulate old-style profile that has null bookmarks/history
		ConnectionProfile profile = new ConnectionProfile();
		profile.setName("Null-Test");
		profile.setBookmarks(null);
		profile.setHistory(null);

		ProfileManager.getInstance().addOrUpdateProfile(profile);

		ProfileManager.resetInstance();
		ProfileManager.setProfilesFile(tempFile);

		ConnectionProfile loaded = ProfileManager.getInstance().getProfiles().get(0);
		assertNotNull(loaded.getBookmarks());
		assertNotNull(loaded.getHistory());
		assertTrue(loaded.getBookmarks().isEmpty());
		assertTrue(loaded.getHistory().isEmpty());
	}
}
