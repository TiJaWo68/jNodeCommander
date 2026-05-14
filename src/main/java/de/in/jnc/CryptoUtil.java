package de.in.jnc;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Basic AES-256 encryption utility for profile passwords.
 * Uses a static master key for Test Scope purposes.
 */
public class CryptoUtil {

	private static final Logger LOGGER = LogManager.getLogger(CryptoUtil.class);

	private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
	// 32-byte key for AES-256. In a production app, this should be derived securely (e.g. PBKDF2/DPAPI)
	private static final byte[] MASTER_KEY = "JNC_TEST_SCOPE_STATIC_KEY_32BYTE".getBytes(StandardCharsets.UTF_8);

	/**
	 * Encrypts a plaintext string using AES-256.
	 * Returns Base64 encoded string containing IV + Ciphertext.
	 */
	public static String encrypt(String plainText) {
		if (plainText == null || plainText.isEmpty()) {
			return plainText;
		}
		try {
			Cipher cipher = Cipher.getInstance(ALGORITHM);
			SecretKeySpec keySpec = new SecretKeySpec(MASTER_KEY, "AES");
			
			// Generate random IV
			byte[] iv = new byte[16];
			new SecureRandom().nextBytes(iv);
			IvParameterSpec ivSpec = new IvParameterSpec(iv);

			cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
			byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

			// Prepend IV to the encrypted payload for decryption
			byte[] combined = new byte[iv.length + encrypted.length];
			System.arraycopy(iv, 0, combined, 0, iv.length);
			System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

			return Base64.getEncoder().encodeToString(combined);
		} catch (Exception e) {
			LOGGER.error("Failed to encrypt data", e);
			return null;
		}
	}

	/**
	 * Decrypts a Base64 encoded string (IV + Ciphertext) back to plaintext.
	 */
	public static String decrypt(String encryptedText) {
		if (encryptedText == null || encryptedText.isEmpty()) {
			return encryptedText;
		}
		try {
			byte[] combined = Base64.getDecoder().decode(encryptedText);
			if (combined.length < 16) {
				LOGGER.error("Invalid encrypted payload (too short)");
				return null;
			}

			// Extract IV
			byte[] iv = new byte[16];
			System.arraycopy(combined, 0, iv, 0, 16);
			IvParameterSpec ivSpec = new IvParameterSpec(iv);

			// Extract Ciphertext
			byte[] encrypted = new byte[combined.length - 16];
			System.arraycopy(combined, 16, encrypted, 0, encrypted.length);

			Cipher cipher = Cipher.getInstance(ALGORITHM);
			SecretKeySpec keySpec = new SecretKeySpec(MASTER_KEY, "AES");
			cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

			byte[] plain = cipher.doFinal(encrypted);
			return new String(plain, StandardCharsets.UTF_8);
		} catch (Exception e) {
			LOGGER.error("Failed to decrypt data", e);
			return null;
		}
	}
}
