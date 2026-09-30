package kr.co.unidocs.monitoring.common.security;

import kr.co.unidocs.monitoring.TestProperties;
import kr.co.unidocs.monitoring.config.MonitoringManagerProperties;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Docker 없이 실행되는 테스트입니다. */
class SecretEncryptionServiceTest {

	private static final String PLAINTEXT = "snmp-community-for-test";

	private final SecretEncryptionService service = serviceWithKey(TestProperties.SECRET_KEY);

	@Test
	void decryptsWhatItEncrypted() {
		String encrypted = service.encrypt(PLAINTEXT);

		assertThat(encrypted).startsWith("v1:").doesNotContain(PLAINTEXT);
		assertThat(service.decrypt(encrypted)).isEqualTo(PLAINTEXT);
	}

	/** 같은 값이 늘 같은 암호문이 되면 어떤 서버가 같은 community를 쓰는지 드러납니다. */
	@Test
	void producesDifferentCiphertextForSamePlaintext() {
		assertThat(service.encrypt(PLAINTEXT)).isNotEqualTo(service.encrypt(PLAINTEXT));
	}

	@Test
	void failsWithDifferentKey() {
		String encrypted = service.encrypt(PLAINTEXT);
		SecretEncryptionService other = serviceWithKey("another-test-key-that-is-long-enough-0123456789");

		assertThatThrownBy(() -> other.decrypt(encrypted)).isInstanceOf(SecretEncryptionException.class);
	}

	@Test
	void failsWhenCiphertextIsDamaged() {
		String encrypted = service.encrypt(PLAINTEXT);
		byte[] raw = Base64.getDecoder().decode(encrypted.substring("v1:".length()));
		raw[raw.length - 1] ^= 0x01;
		String damaged = "v1:" + Base64.getEncoder().encodeToString(raw);

		assertThatThrownBy(() -> service.decrypt(damaged)).isInstanceOf(SecretEncryptionException.class);
	}

	@Test
	void failsWhenFormatIsUnknown() {
		assertThatThrownBy(() -> service.decrypt("v2:abc")).isInstanceOf(SecretEncryptionException.class);
		assertThatThrownBy(() -> service.decrypt("plain-text")).isInstanceOf(SecretEncryptionException.class);
	}

	/** 짧은 키로 조용히 동작하면 암호화한 의미가 없습니다. 기동 자체가 실패해야 합니다. */
	@Test
	void refusesShortSecretKey() {
		assertThatThrownBy(() -> serviceWithKey("too-short")).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("APP_SECRET_KEY");
	}

	@Test
	void keepsSecretsOutOfFailureMessages() {
		String encrypted = service.encrypt(PLAINTEXT);
		SecretEncryptionService other = serviceWithKey("another-test-key-that-is-long-enough-0123456789");

		assertThatThrownBy(() -> other.decrypt(encrypted)).satisfies(thrown -> assertThat(thrown.getMessage())
				.doesNotContain(PLAINTEXT)
				.doesNotContain(TestProperties.SECRET_KEY));
	}

	private SecretEncryptionService serviceWithKey(String secretKey) {
		MonitoringManagerProperties properties = TestProperties
				.defaults("http://prometheus:9090", TestProperties.telegram(null, "https://api.telegram.org"));

		return new SecretEncryptionService(new MonitoringManagerProperties(secretKey, properties.prometheus(),
				properties.alertmanager(), properties.grafana(), properties.exporters(), properties.telegram(),
				properties.notification(), properties.snmp()));
	}

}
