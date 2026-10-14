package kr.co.unidocs.monitoring.prometheus;

import kr.co.unidocs.monitoring.check.MonitoringCheck;
import kr.co.unidocs.monitoring.check.MonitoringCheckRepository;
import kr.co.unidocs.monitoring.check.MonitoringCheckTarget;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Blackbox Exporter로 확인할 Target 목록입니다. 기준은 Manager Database입니다. (CLAUDE.md 1번, 2번)
 *
 * Target 하나가 SD entry 하나입니다.
 * 한 entry에 주소를 여러 개 넣으면 label을 공유하게 돼서 주소마다 check_target_id를 붙일 수 없습니다.
 * 그러면 상태를 주소 단위로 구분하지 못합니다.
 */
@Service
public class BlackboxServiceDiscoveryService {

	private final MonitoringCheckRepository checkRepository;

	BlackboxServiceDiscoveryService(MonitoringCheckRepository checkRepository) {
		this.checkRepository = checkRepository;
	}

	@Transactional(readOnly = true)
	public List<ServiceDiscoveryTarget> blackboxTargets() {
		List<ServiceDiscoveryTarget> discovered = new ArrayList<>();

		for (MonitoringCheck check : checkRepository.findAllActive()) {
			for (MonitoringCheckTarget target : check.activeTargets()) {
				discovered.add(toTarget(check, target));
			}
		}

		return discovered;
	}

	/**
	 * Label은 이름이 바뀌어도 흔들리지 않도록 ID 중심으로 넣습니다.
	 * 설명이나 긴 문자열은 Prometheus에 그대로 저장되므로 넣지 않습니다.
	 *
	 * 알림 대상은 Manager Database가 정합니다.
	 * Telegram chat id 같은 라우팅 정보를 label에 넣지 않습니다. (CLAUDE.md 4번)
	 *
	 * __scrape_interval__은 Prometheus가 이 대상의 수집 주기로 씁니다.
	 * __로 시작하는 label은 relabel 뒤에 지워져 time series에 남지 않습니다.
	 */
	private ServiceDiscoveryTarget toTarget(MonitoringCheck check, MonitoringCheckTarget target) {
		Map<String, String> labels = new LinkedHashMap<>();
		labels.put("check_id", String.valueOf(check.getId()));
		labels.put("check_target_id", String.valueOf(target.getId()));
		labels.put("check_type", check.getType().name());
		labels.put("module", check.getType().blackboxModule());
		labels.put("environment", check.getEnvironment());
		labels.put("check_name", check.getName());
		labels.put("__scrape_interval__", check.getScrapeIntervalSeconds() + "s");

		return new ServiceDiscoveryTarget(List.of(target.blackboxTarget()), labels);
	}

}
