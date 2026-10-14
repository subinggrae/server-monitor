package kr.co.unidocs.monitoring.snmp;

import kr.co.unidocs.monitoring.common.security.SecretEncryptionService;
import kr.co.unidocs.monitoring.server.ServerTargetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SNMP 설정 저장의 수집 주기")
class ServerSnmpConfigStoreTest {

	private final ServerTargetRepository serverRepository = mock(ServerTargetRepository.class);

	private final ServerSnmpConfigRepository snmpConfigRepository = mock(ServerSnmpConfigRepository.class);

	private final SecretEncryptionService secretEncryptionService = mock(SecretEncryptionService.class);

	private final ServerSnmpConfigStore store = new ServerSnmpConfigStore(serverRepository, snmpConfigRepository,
			secretEncryptionService);

	@BeforeEach
	void stubCollaborators() {
		when(serverRepository.existsById(5L)).thenReturn(true);
		when(secretEncryptionService.encrypt(anyString())).thenReturn("cipher");
		when(snmpConfigRepository.save(any(ServerSnmpConfig.class))).then(returnsFirstArg());
	}

	@Test
	@DisplayName("등록하면 요청의 수집 주기로 저장한다")
	void createsWithRequestedInterval() {
		when(snmpConfigRepository.findByServerId(5L)).thenReturn(Optional.empty());

		ServerSnmpConfig saved = store.save(5L, request("public", new BigDecimal("120")));

		assertThat(saved.getScrapeIntervalSeconds()).isEqualTo(120);
	}

	@Test
	@DisplayName("수집 주기 없이 등록하면 15초로 저장한다")
	void createsWithDefaultInterval() {
		when(snmpConfigRepository.findByServerId(5L)).thenReturn(Optional.empty());

		ServerSnmpConfig saved = store.save(5L, request("public", null));

		assertThat(saved.getScrapeIntervalSeconds()).isEqualTo(15);
	}

	@Test
	@DisplayName("수정하면 요청의 수집 주기로 바꾼다")
	void updatesInterval() {
		givenExistingConfig(120);

		ServerSnmpConfig saved = store.save(5L, request(null, new BigDecimal("30")));

		assertThat(saved.getScrapeIntervalSeconds()).isEqualTo(30);
	}

	@Test
	@DisplayName("수집 주기 없이 수정하면 기존 주기를 그대로 둔다")
	void keepsIntervalWhenUpdatedWithout() {
		givenExistingConfig(120);

		ServerSnmpConfig saved = store.save(5L, request(null, null));

		assertThat(saved.getScrapeIntervalSeconds()).isEqualTo(120);
	}

	private void givenExistingConfig(int scrapeIntervalSeconds) {
		ServerSnmpConfig existing = new ServerSnmpConfig(5L, SnmpVersion.V2C, 161, "cipher", true,
				scrapeIntervalSeconds);
		when(snmpConfigRepository.findByServerId(5L)).thenReturn(Optional.of(existing));
	}

	private ServerSnmpRequest request(String community, BigDecimal scrapeIntervalSeconds) {
		return new ServerSnmpRequest(SnmpVersion.V2C, 161, community, true, scrapeIntervalSeconds);
	}

}
