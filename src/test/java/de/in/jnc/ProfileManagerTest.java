package de.in.jnc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;

public class ProfileManagerTest {

	private File tempFile;

	@BeforeEach
	public void setup() throws Exception {
		tempFile = File.createTempFile("profiles_test", ".json");
		tempFile.deleteOnExit();

		ProfileManager.setProfilesFile(tempFile);

		// Clear existing profiles
		ProfileManager.getInstance().getProfiles().forEach(p -> {
			ProfileManager.getInstance().deleteProfile(p.getId());
		});
	}

	@AfterEach
	public void tearDown() {
		if (tempFile.exists()) {
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
		
		ProfileManager.getInstance().loadProfiles();
		
		assertEquals(1, ProfileManager.getInstance().getProfiles().size());
		ConnectionProfile loaded = ProfileManager.getInstance().getProfiles().get(0);
		assertEquals("Test Profile", loaded.getName());
		assertEquals("localhost", loaded.getHost());
		assertEquals(2222, loaded.getPort());
		assertEquals("testuser", loaded.getUser());
	}
}
