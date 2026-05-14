package de.in.jnc;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CryptoUtilTest {

	@Test
	public void testEncryptDecrypt() {
		String original = "SuperSecretPassword123!";
		String encrypted = CryptoUtil.encrypt(original);
		
		assertNotNull(encrypted);
		assertNotEquals(original, encrypted);
		
		String decrypted = CryptoUtil.decrypt(encrypted);
		assertEquals(original, decrypted);
	}

	@Test
	public void testNullOrEmpty() {
		assertNull(CryptoUtil.encrypt(null));
		assertEquals("", CryptoUtil.encrypt(""));
		
		assertNull(CryptoUtil.decrypt(null));
		assertEquals("", CryptoUtil.decrypt(""));
	}
}
