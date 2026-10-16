package kr.co.unidocs.monitoring.exporter;

import kr.co.unidocs.monitoring.server.ServerNotFoundException;
import kr.co.unidocs.monitoring.server.ServerOs;
import kr.co.unidocs.monitoring.server.ServerTarget;
import kr.co.unidocs.monitoring.server.ServerTargetRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("서버의 수집 설정과 연결 시험")
class ServerExporterServiceTest {

	private final ServerTargetRepository serverRepository = mock(ServerTargetRepository.class);

	private final ServerExporterConfigRepository configRepository = mock(ServerExporterConfigRepository.class);

	private final ExporterClient exporterClient = mock(ExporterClient.class);

	private final ServerExporterService service = new ServerExporterService(serverRepository, configRepository,
			exporterClient);

	@ParameterizedTest(name = "{0}")
	@EnumSource(ServerOs.class)
	@DisplayName("설정이 없으면 OS 기본 포트와 15초를 기본값으로 돌려준다")
	void returnsDefaultsWithoutConfig(ServerOs os) {
		givenServer(5L, os);

		ServerExporterResponse response = service.find(5L);

		assertThat(response.configured()).isFalse();
		assertThat(response.enabled()).isFalse();
		assertThat(response.port()).isEqualTo(os.defaultExporterPort());
		assertThat(response.defaultPort()).isEqualTo(os.defaultExporterPort());
		assertThat(response.scrapeIntervalSeconds()).isEqualTo(15);
	}

	@Test
	@DisplayName("저장한 설정을 돌려준다")
	void returnsSavedConfig() {
		givenServer(5L, ServerOs.LINUX);
		when(configRepository.findByServerId(5L)).thenReturn(Optional.of(new ServerExporterConfig(5L, true, 9200, 30)));

		ServerExporterResponse response = service.find(5L);

		assertThat(response).isEqualTo(new ServerExporterResponse(true, true, 9200, 30, 9100));
	}

	@Test
	@DisplayName("처음 저장하면 새 설정을 만들고, 다시 저장하면 기존 설정을 바꾼다")
	void createsThenUpdates() {
		givenServer(5L, ServerOs.LINUX);
		when(configRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		ServerExporterResponse created = service.save(5L, new ServerExporterRequest(true, 9100, new BigDecimal("30")));
		assertThat(created).isEqualTo(new ServerExporterResponse(true, true, 9100, 30, 9100));

		ServerExporterConfig existing = new ServerExporterConfig(5L, true, 9100, 30);
		when(configRepository.findByServerId(5L)).thenReturn(Optional.of(existing));

		ServerExporterResponse updated = service.save(5L, new ServerExporterRequest(false, 9200, new BigDecimal("60")));
		assertThat(updated).isEqualTo(new ServerExporterResponse(true, false, 9200, 60, 9100));
		assertThat(existing.getPort()).isEqualTo(9200);
	}

	@Test
	@DisplayName("없는 서버면 찾지 못했다는 오류가 난다")
	void rejectsUnknownServer() {
		when(serverRepository.findById(9L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.find(9L)).isInstanceOf(ServerNotFoundException.class);
		assertThatThrownBy(() -> service.test(9L, new ExporterTestRequest(9100)))
			.isInstanceOf(ServerNotFoundException.class);
		verify(exporterClient, never()).read(any(), any(Integer.class));
	}

	@Test
	@DisplayName("연결 시험은 요청의 포트로 서버 호스트를 읽고 exporter 종류와 버전을 돌려준다")
	void testsWithRequestedPort() {
		givenServer(5L, ServerOs.LINUX);
		when(exporterClient.read("10.0.0.5", 9200)).thenReturn(ok(ExporterType.NODE_EXPORTER));

		ExporterTestResult result = service.test(5L, new ExporterTestRequest(9200));

		assertThat(result.success()).isTrue();
		assertThat(result.outcome()).isEqualTo(ExporterOutcome.OK);
		assertThat(result.exporterType()).isEqualTo(ExporterType.NODE_EXPORTER);
		assertThat(result.version()).isEqualTo("1.12.1");
		assertThat(result.osMatches()).isTrue();
		assertThat(result.responseTimeMs()).isEqualTo(12L);
	}

	@Test
	@DisplayName("고른 OS와 exporter가 다르면 성공이지만 OS가 다르다고 알린다")
	void reportsOsMismatch() {
		givenServer(5L, ServerOs.WINDOWS);
		when(exporterClient.read("10.0.0.5", 9100)).thenReturn(ok(ExporterType.NODE_EXPORTER));

		ExporterTestResult result = service.test(5L, new ExporterTestRequest(9100));

		assertThat(result.success()).isTrue();
		assertThat(result.osMatches()).isFalse();
		assertThat(result.message()).contains("OS");
	}

	@Test
	@DisplayName("실패하면 분류마다 정한 문구만 돌려준다")
	void returnsFixedMessagePerOutcome() {
		givenServer(5L, ServerOs.LINUX);
		Map<ExporterOutcome, String> messages = new EnumMap<>(ExporterOutcome.class);
		for (ExporterOutcome outcome : ExporterOutcome.values()) {
			if (outcome == ExporterOutcome.OK) {
				continue;
			}
			when(exporterClient.read("10.0.0.5", 9100))
				.thenReturn(ExporterReadResult.failed(outcome, outcome == ExporterOutcome.HTTP_ERROR ? 404 : null, null));

			ExporterTestResult result = service.test(5L, new ExporterTestRequest(9100));

			assertThat(result.success()).as(outcome.name()).isFalse();
			assertThat(result.outcome()).isEqualTo(outcome);
			assertThat(result.exporterType()).isNull();
			assertThat(result.osMatches()).isNull();
			messages.put(outcome, result.message());
		}

		assertThat(messages.get(ExporterOutcome.UNREADABLE)).startsWith("exporter 응답을 읽지 못했습니다.");
		assertThat(messages.values()).doesNotContainNull().doesNotHaveDuplicates();
	}

	@Test
	@DisplayName("켠 설정이 있을 때만 리소스 찾기 대상이 있다")
	void enabledTargetRequiresEnabledConfig() {
		givenServer(5L, ServerOs.WINDOWS);
		assertThat(service.enabledTarget(5L)).isEmpty();

		when(configRepository.findByServerId(5L)).thenReturn(Optional.of(new ServerExporterConfig(5L, false, 9182, 15)));
		assertThat(service.enabledTarget(5L)).isEmpty();

		when(configRepository.findByServerId(5L)).thenReturn(Optional.of(new ServerExporterConfig(5L, true, 9182, 15)));
		assertThat(service.enabledTarget(5L)).contains(new ExporterTarget("10.0.0.5", 9182));
	}

	private static ExporterReadResult ok(ExporterType type) {
		return new ExporterReadResult(ExporterOutcome.OK, 200, new ExporterMetrics(type, "1.12.1", 2, 100L, List.of()),
				12L);
	}

	private void givenServer(Long serverId, ServerOs os) {
		ServerTarget server = new ServerTarget("web01", "10.0.0.5", "Production", os, null, true);
		ReflectionTestUtils.setField(server, "id", serverId);
		when(serverRepository.findById(serverId)).thenReturn(Optional.of(server));
		when(serverRepository.existsById(serverId)).thenReturn(true);
	}

}
