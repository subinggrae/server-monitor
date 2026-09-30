package kr.co.unidocs.monitoring.check;

import kr.co.unidocs.monitoring.check.CheckStatusService.ProbeResult;
import kr.co.unidocs.monitoring.check.CheckStatusService.ProbeSnapshot;
import kr.co.unidocs.monitoring.check.MonitoringCheckRequest.TargetRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MonitoringCheckService {

	private static final String HOST_PATTERN = "^[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?$";

	private final MonitoringCheckRepository checkRepository;

	private final CheckStatusService checkStatusService;

	MonitoringCheckService(MonitoringCheckRepository checkRepository, CheckStatusService checkStatusService) {
		this.checkRepository = checkRepository;
		this.checkStatusService = checkStatusService;
	}

	@Transactional(readOnly = true)
	public List<MonitoringCheckResponse> findAll() {
		return toResponses(checkRepository.findAllWithTargets());
	}

	@Transactional(readOnly = true)
	public MonitoringCheckResponse findById(Long checkId) {
		MonitoringCheck check = getCheck(checkId);
		return MonitoringCheckResponse.of(check, checkStatusService.loadProbeSnapshot());
	}

	@Transactional
	public MonitoringCheckResponse create(MonitoringCheckRequest request) {
		List<NormalizedTarget> normalized = normalizeTargets(request);

		MonitoringCheck check = new MonitoringCheck(request.name().trim(), request.type(),
				request.environment().trim(), trimToNull(request.description()), request.enabled());
		for (NormalizedTarget target : normalized) {
			check.addTarget(target.target(), target.port(), target.enabled());
		}

		MonitoringCheck saved = checkRepository.save(check);
		// flush해야 응답에 새 Target의 id가 담깁니다.
		checkRepository.flush();

		// 방금 만든 Target은 아직 수집된 Metric이 없어서 Prometheus를 부르지 않습니다.
		return MonitoringCheckResponse.of(saved, new ProbeSnapshot(true, Map.of()));
	}

	@Transactional
	public MonitoringCheckResponse update(Long checkId, MonitoringCheckRequest request) {
		MonitoringCheck check = getCheck(checkId);

		List<NormalizedTarget> normalized = normalizeTargets(request);

		check.update(request.name().trim(), request.type(), request.environment().trim(),
				trimToNull(request.description()), request.enabled());
		applyTargets(check, normalized);

		// flush해야 응답에 새 Target의 id가 담깁니다.
		checkRepository.flush();

		return MonitoringCheckResponse.of(check, checkStatusService.loadProbeSnapshot());
	}

	@Transactional
	public void delete(Long checkId) {
		if (!checkRepository.existsById(checkId)) {
			throw new MonitoringCheckNotFoundException(checkId);
		}
		checkRepository.deleteById(checkId);
	}

	@Transactional(readOnly = true)
	public CheckStatusResponse status(Long checkId) {
		MonitoringCheck check = getCheck(checkId);
		ProbeSnapshot snapshot = checkStatusService.loadProbeSnapshot();
		MonitoringStatus status = snapshot.statusOf(check);

		List<CheckStatusResponse.TargetStatus> targetStatuses = new ArrayList<>();
		for (MonitoringCheckTarget target : check.getTargets()) {
			ProbeResult probeResult = snapshot.probeResultOf(target.getId());
			targetStatuses.add(new CheckStatusResponse.TargetStatus(target.getId(), target.blackboxTarget(),
					snapshot.statusOf(target), (probeResult != null) ? probeResult.success() : null,
					(probeResult != null) ? probeResult.sampledAt() : null));
		}

		return new CheckStatusResponse(checkId, status, describe(status, snapshot.prometheusAvailable()),
				targetStatuses);
	}

	private String describe(MonitoringStatus status, boolean prometheusAvailable) {
		return switch (status) {
			case OK -> "모든 대상이 정상입니다.";
			case CRITICAL -> "확인에 실패한 대상이 있습니다. 아래 목록에서 어떤 주소인지 확인해 주세요.";
			case DISABLED -> "사용하지 않는 Check입니다.";
			case UNKNOWN -> prometheusAvailable
					? "Prometheus에서 아직 수집된 Metric이 없습니다. 잠시 후 다시 확인해 주세요."
					: "Prometheus에 연결할 수 없어 상태를 확인하지 못했습니다. 관리자에게 문의해 주세요.";
		};
	}

	private List<MonitoringCheckResponse> toResponses(List<MonitoringCheck> checks) {
		// 목록이 길어도 Prometheus query는 한 번만 실행합니다.
		ProbeSnapshot snapshot = checkStatusService.loadProbeSnapshot();
		return checks.stream().map(check -> MonitoringCheckResponse.of(check, snapshot)).toList();
	}

	private MonitoringCheck getCheck(Long checkId) {
		return checkRepository.findByIdWithTargets(checkId)
				.orElseThrow(() -> new MonitoringCheckNotFoundException(checkId));
	}

	/** 기존 Target의 id를 유지해야 Prometheus에서 쌓아 온 time series가 끊기지 않습니다. */
	private void applyTargets(MonitoringCheck check, List<NormalizedTarget> requested) {
		Map<Long, MonitoringCheckTarget> existing = new HashMap<>();
		for (MonitoringCheckTarget target : check.getTargets()) {
			existing.put(target.getId(), target);
		}

		Set<Long> keptIds = new HashSet<>();
		for (NormalizedTarget target : requested) {
			if (target.id() == null) {
				check.addTarget(target.target(), target.port(), target.enabled());
				continue;
			}

			MonitoringCheckTarget found = existing.get(target.id());
			if (found == null) {
				throw new InvalidCheckConfigurationException("이 Check에 속하지 않는 대상이 있습니다. 화면을 새로 고친 뒤 다시 시도해 주세요.");
			}
			found.update(target.target(), target.port(), target.enabled());
			keptIds.add(target.id());
		}

		List<MonitoringCheckTarget> removed = existing.values().stream()
				.filter(target -> !keptIds.contains(target.getId()))
				.toList();
		removed.forEach(check::removeTarget);
	}

	/** Type과 값을 함께 봐야 알 수 있는 조건이라 Bean Validation 대신 여기에서 처리합니다. */
	private List<NormalizedTarget> normalizeTargets(MonitoringCheckRequest request) {
		List<NormalizedTarget> normalized = new ArrayList<>();
		Set<String> seen = new HashSet<>();

		for (TargetRequest target : request.targets()) {
			NormalizedTarget result = normalize(request.type(), target);

			// 같은 주소가 두 번 들어가면 Prometheus가 같은 대상을 두 번 확인합니다.
			if (!seen.add(result.blackboxTarget())) {
				throw new InvalidCheckConfigurationException(
						"같은 대상이 두 번 들어 있습니다: " + result.blackboxTarget());
			}
			normalized.add(result);
		}

		return normalized;
	}

	private NormalizedTarget normalize(CheckType type, TargetRequest request) {
		String target = request.target().trim();

		return switch (type) {
			case ICMP -> {
				requireHost(target);
				// ICMP는 port를 쓰지 않습니다. 값이 들어와도 버립니다.
				yield new NormalizedTarget(request.id(), target, null, request.enabled());
			}
			case TCP -> {
				requireHost(target);
				if (request.port() == null) {
					throw new InvalidCheckConfigurationException(
							"TCP Check는 대상마다 Port가 필요합니다. 1부터 65535 사이의 값을 입력해 주세요.");
				}
				yield new NormalizedTarget(request.id(), target, request.port(), request.enabled());
			}
			case HTTP, HTTPS -> {
				requireUrl(target, type);
				// port가 URL 안에 들어 있어 따로 저장하지 않습니다.
				yield new NormalizedTarget(request.id(), target, null, request.enabled());
			}
		};
	}

	private void requireHost(String target) {
		if (!target.matches(HOST_PATTERN)) {
			throw new InvalidCheckConfigurationException(
					"대상에는 IP 또는 hostname만 입력할 수 있습니다. 예: 10.10.10.15, web01.internal");
		}
		if (target.length() > 255) {
			throw new InvalidCheckConfigurationException("Host는 255자까지 입력할 수 있습니다.");
		}
	}

	private void requireUrl(String target, CheckType type) {
		URI uri;
		try {
			uri = new URI(target);
		}
		catch (URISyntaxException ex) {
			throw new InvalidCheckConfigurationException("URL 형식이 올바르지 않습니다. 예: http://10.10.10.15:8080/health");
		}

		String scheme = uri.getScheme();
		if (scheme == null || uri.getHost() == null) {
			throw new InvalidCheckConfigurationException("URL 형식이 올바르지 않습니다. 예: http://10.10.10.15:8080/health");
		}

		// file이나 gopher 같은 scheme을 막습니다.
		String expectedScheme = type.requiredUrlScheme();
		if (!expectedScheme.equalsIgnoreCase(scheme)) {
			throw new InvalidCheckConfigurationException(
					type.displayName() + " Check의 URL은 " + expectedScheme + ":// 로 시작해야 합니다.");
		}

		int port = uri.getPort();
		if (port != -1 && (port < 1 || port > 65535)) {
			throw new InvalidCheckConfigurationException("URL의 Port는 1부터 65535 사이의 값이어야 합니다.");
		}
	}

	private String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private record NormalizedTarget(Long id, String target, Integer port, boolean enabled) {

		/** 중복 검사용 값입니다. TCP는 port까지 합쳐야 같은 대상인지 알 수 있습니다. */
		String blackboxTarget() {
			return (port != null) ? target + ":" + port : target;
		}

	}

}
