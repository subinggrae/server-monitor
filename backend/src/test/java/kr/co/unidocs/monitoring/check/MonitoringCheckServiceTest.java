package kr.co.unidocs.monitoring.check;

import kr.co.unidocs.monitoring.check.CheckStatusService.ProbeSnapshot;
import kr.co.unidocs.monitoring.check.MonitoringCheckRequest.TargetRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Check 저장의 수집 주기")
class MonitoringCheckServiceTest {

	private final MonitoringCheckRepository repository = mock(MonitoringCheckRepository.class);

	private final CheckStatusService checkStatusService = mock(CheckStatusService.class);

	private final MonitoringCheckService service = new MonitoringCheckService(repository, checkStatusService);

	@BeforeEach
	void stubCollaborators() {
		// 실제 저장소처럼 저장할 때 id를 붙입니다. 응답을 만들 때 대상 id가 필요합니다.
		when(repository.save(any(MonitoringCheck.class))).then(invocation -> {
			MonitoringCheck check = invocation.getArgument(0);
			ReflectionTestUtils.setField(check, "id", 3L);
			check.getTargets().forEach(target -> ReflectionTestUtils.setField(target, "id", 7L));
			return check;
		});
		when(checkStatusService.loadProbeSnapshot()).thenReturn(new ProbeSnapshot(true, Map.of()));
	}

	@Test
	@DisplayName("등록하면 요청의 수집 주기로 저장한다")
	void createsWithRequestedInterval() {
		MonitoringCheckResponse response = service.create(request(new BigDecimal("30"), null));

		assertThat(response.scrapeIntervalSeconds()).isEqualTo(30);
	}

	@Test
	@DisplayName("수집 주기 없이 등록하면 15초로 저장한다")
	void createsWithDefaultInterval() {
		MonitoringCheckResponse response = service.create(request(null, null));

		assertThat(response.scrapeIntervalSeconds()).isEqualTo(15);
	}

	@Test
	@DisplayName("수정하면 요청의 수집 주기로 바꾼다")
	void updatesInterval() {
		MonitoringCheck existing = givenExistingCheck(60);

		service.update(3L, request(new BigDecimal("30"), 7L));

		assertThat(existing.getScrapeIntervalSeconds()).isEqualTo(30);
	}

	@Test
	@DisplayName("수집 주기 없이 수정하면 기존 주기를 그대로 둔다")
	void keepsIntervalWhenUpdatedWithout() {
		MonitoringCheck existing = givenExistingCheck(60);

		service.update(3L, request(null, 7L));

		assertThat(existing.getScrapeIntervalSeconds()).isEqualTo(60);
	}

	private MonitoringCheck givenExistingCheck(int scrapeIntervalSeconds) {
		MonitoringCheck check = new MonitoringCheck("전자결재 Health", CheckType.ICMP, "Production", null, true,
				scrapeIntervalSeconds);
		ReflectionTestUtils.setField(check, "id", 3L);
		MonitoringCheckTarget target = check.addTarget("10.0.0.1", null, true);
		ReflectionTestUtils.setField(target, "id", 7L);
		when(repository.findByIdWithTargets(3L)).thenReturn(Optional.of(check));
		return check;
	}

	private MonitoringCheckRequest request(BigDecimal scrapeIntervalSeconds, Long targetId) {
		return new MonitoringCheckRequest("전자결재 Health", CheckType.ICMP, "Production", null, true,
				scrapeIntervalSeconds, List.of(new TargetRequest(targetId, "10.0.0.1", null, true)));
	}

}
