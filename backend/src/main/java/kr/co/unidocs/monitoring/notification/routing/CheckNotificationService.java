package kr.co.unidocs.monitoring.notification.routing;

import kr.co.unidocs.monitoring.check.MonitoringCheck;
import kr.co.unidocs.monitoring.check.MonitoringCheckNotFoundException;
import kr.co.unidocs.monitoring.check.MonitoringCheckRepository;
import kr.co.unidocs.monitoring.notification.routing.CheckNotificationRequest.BindingRequest;
import kr.co.unidocs.monitoring.notification.telegram.TelegramDestination;
import kr.co.unidocs.monitoring.notification.telegram.TelegramDestinationNotFoundException;
import kr.co.unidocs.monitoring.notification.telegram.TelegramDestinationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CheckNotificationService {

	private final CheckNotificationBindingRepository bindingRepository;

	private final TelegramDestinationRepository destinationRepository;

	private final MonitoringCheckRepository checkRepository;

	CheckNotificationService(CheckNotificationBindingRepository bindingRepository,
			TelegramDestinationRepository destinationRepository, MonitoringCheckRepository checkRepository) {
		this.bindingRepository = bindingRepository;
		this.destinationRepository = destinationRepository;
		this.checkRepository = checkRepository;
	}

	@Transactional(readOnly = true)
	public CheckNotificationResponse findByCheck(Long checkId) {
		requireCheck(checkId);
		return CheckNotificationResponse.of(checkId, bindingRepository.findAllByCheckId(checkId));
	}

	/** 목록에 없는 연결은 지웁니다. 기존 연결은 id를 유지한 채로 설정만 바꿉니다. */
	@Transactional
	public CheckNotificationResponse replace(Long checkId, CheckNotificationRequest request) {
		requireCheck(checkId);

		Map<Long, TelegramDestination> destinations = loadDestinations(request.bindings());
		Map<Long, CheckNotificationBinding> existing = bindingRepository.findAllByCheckId(checkId).stream()
				.collect(Collectors.toMap(binding -> binding.getDestination().getId(), binding -> binding));

		Set<Long> keptDestinationIds = new HashSet<>();
		for (BindingRequest requested : request.bindings()) {
			Long destinationId = requested.telegramDestinationId();

			// 같은 그룹이 두 번 들어가면 장애 하나에 메시지가 두 번 갑니다.
			if (!keptDestinationIds.add(destinationId)) {
				throw new DuplicateNotificationBindingException(
						"같은 Telegram 그룹이 두 번 들어 있습니다. 목록을 확인해 주세요.");
			}

			CheckNotificationBinding binding = existing.get(destinationId);
			if (binding != null) {
				binding.update(requested.warningEnabled(), requested.criticalEnabled(), requested.sendResolved(),
						requested.enabled());
				continue;
			}

			bindingRepository.save(new CheckNotificationBinding(checkId, destinations.get(destinationId),
					requested.warningEnabled(), requested.criticalEnabled(), requested.sendResolved(),
					requested.enabled()));
		}

		List<CheckNotificationBinding> removed = existing.values().stream()
				.filter(binding -> !keptDestinationIds.contains(binding.getDestination().getId()))
				.toList();
		bindingRepository.deleteAll(removed);

		// flush해야 응답에 새 연결의 id가 담깁니다.
		bindingRepository.flush();

		return CheckNotificationResponse.of(checkId, bindingRepository.findAllByCheckId(checkId));
	}

	@Transactional(readOnly = true)
	public List<CheckNotificationSummaryResponse> findAll() {
		Map<Long, List<CheckNotificationBinding>> byCheckId = bindingRepository.findAllWithDestination().stream()
				.collect(Collectors.groupingBy(CheckNotificationBinding::getCheckId));

		List<CheckNotificationSummaryResponse> summaries = new ArrayList<>();
		for (MonitoringCheck check : checkRepository.findAllWithTargets()) {
			List<CheckNotificationBinding> bindings = byCheckId.getOrDefault(check.getId(), List.of());

			summaries.add(new CheckNotificationSummaryResponse(check.getId(), check.getName(), check.getEnvironment(),
					check.isEnabled(),
					bindings.stream().map(CheckNotificationResponse.BindingResponse::of).toList()));
		}

		return summaries;
	}

	/** 없는 그룹이 섞여 있으면 하나도 저장하지 않고 알려 줍니다. */
	private Map<Long, TelegramDestination> loadDestinations(List<BindingRequest> bindings) {
		List<Long> ids = bindings.stream().map(BindingRequest::telegramDestinationId).distinct().toList();
		if (ids.isEmpty()) {
			return Map.of();
		}

		Map<Long, TelegramDestination> found = destinationRepository.findAllByIdIn(ids).stream()
				.collect(Collectors.toMap(TelegramDestination::getId, destination -> destination));

		for (Long id : ids) {
			if (!found.containsKey(id)) {
				throw new TelegramDestinationNotFoundException(id);
			}
		}

		return found;
	}

	private void requireCheck(Long checkId) {
		if (!checkRepository.existsById(checkId)) {
			throw new MonitoringCheckNotFoundException(checkId);
		}
	}

}
