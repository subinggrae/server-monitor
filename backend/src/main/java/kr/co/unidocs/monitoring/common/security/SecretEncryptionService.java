package kr.co.unidocs.monitoring.common.security;

import kr.co.unidocs.monitoring.config.MonitoringManagerProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Manager Database에 저장하는 인증 정보를 암호화합니다.
 *
 * AES-GCM을 씁니다. 복호화할 때 값이 바뀌었는지도 함께 확인해야 하기 때문입니다.
 * 평문은 저장하지 않고, 복호화는 실제로 그 값이 필요한 순간에만 합니다. (CLAUDE.md 11번)
 */
@Service
public class SecretEncryptionService {

	/** 나중에 알고리즘이나 키를 바꿔도 기존 값을 읽을 수 있도록 version을 앞에 붙입니다. */
	private static final String VERSION_PREFIX = "v1:";

	private static final String TRANSFORMATION = "AES/GCM/NoPadding";

	private static final int NONCE_BYTES = 12;

	private static final int TAG_BITS = 128;

	/** 짧은 키는 사람이 외우려고 만든 값입니다. 그런 키로 암호화하면 지키는 의미가 없습니다. */
	private static final int MINIMUM_KEY_LENGTH = 32;

	private final SecretKeySpec key;

	private final SecureRandom random = new SecureRandom();

	SecretEncryptionService(MonitoringManagerProperties properties) {
		this.key = deriveKey(properties.secretKey());
	}

	public String encrypt(String plaintext) {
		if (plaintext == null) {
			throw new IllegalArgumentException("암호화할 값이 없습니다.");
		}

		try {
			byte[] nonce = new byte[NONCE_BYTES];
			random.nextBytes(nonce);

			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
			byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

			byte[] combined = new byte[nonce.length + ciphertext.length];
			System.arraycopy(nonce, 0, combined, 0, nonce.length);
			System.arraycopy(ciphertext, 0, combined, nonce.length, ciphertext.length);

			return VERSION_PREFIX + Base64.getEncoder().encodeToString(combined);
		}
		catch (Exception ex) {
			// 예외 메시지에 평문이 섞여 나가지 않도록 원인을 그대로 전달하지 않습니다.
			throw new SecretEncryptionException("값을 암호화하지 못했습니다.");
		}
	}

	public String decrypt(String encrypted) {
		if (encrypted == null || !encrypted.startsWith(VERSION_PREFIX)) {
			throw new SecretEncryptionException("저장된 값의 형식을 알 수 없습니다.");
		}

		try {
			byte[] combined = Base64.getDecoder().decode(encrypted.substring(VERSION_PREFIX.length()));
			if (combined.length <= NONCE_BYTES) {
				throw new SecretEncryptionException("저장된 값이 손상되었습니다.");
			}

			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, combined, 0, NONCE_BYTES));
			byte[] plaintext = cipher.doFinal(combined, NONCE_BYTES, combined.length - NONCE_BYTES);

			return new String(plaintext, StandardCharsets.UTF_8);
		}
		catch (SecretEncryptionException ex) {
			throw ex;
		}
		catch (Exception ex) {
			// 키가 바뀌었거나 값이 손상된 경우입니다. 어느 쪽인지 구분해서 알려 주지 않습니다.
			throw new SecretEncryptionException("저장된 값을 복호화하지 못했습니다. APP_SECRET_KEY가 바뀌지 않았는지 확인해 주세요.");
		}
	}

	private SecretKeySpec deriveKey(String secretKey) {
		if (secretKey == null || secretKey.trim().length() < MINIMUM_KEY_LENGTH) {
			throw new IllegalStateException("APP_SECRET_KEY가 너무 짧습니다. " + MINIMUM_KEY_LENGTH
					+ "자 이상의 임의 문자열을 Docker Secret(monitoring-manager.secret-key) 또는 APP_SECRET_KEY로 설정해 주세요.");
		}

		try {
			// 설정값은 길이가 제각각입니다. AES가 요구하는 256bit로 맞춥니다.
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(secretKey.trim().getBytes(StandardCharsets.UTF_8));
			return new SecretKeySpec(digest, "AES");
		}
		catch (Exception ex) {
			throw new IllegalStateException("APP_SECRET_KEY로 암호화 키를 만들지 못했습니다.");
		}
	}

}
