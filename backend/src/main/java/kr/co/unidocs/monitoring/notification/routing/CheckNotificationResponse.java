package kr.co.unidocs.monitoring.notification.routing;

import java.util.List;

public record CheckNotificationResponse(Long checkId, List<BindingResponse> bindings, int repeatIntervalMinutes) {

	public static CheckNotificationResponse of(Long checkId, List<CheckNotificationBinding> bindings,
			int repeatIntervalMinutes) {
		return new CheckNotificationResponse(checkId, bindings.stream().map(BindingResponse::of).toList(),
				repeatIntervalMinutes);
	}

	public record BindingResponse(
			Long id,
			Long telegramDestinationId,
			String telegramDestinationName,
			/** 그룹이 꺼져 있으면 연결이 켜져 있어도 알림이 가지 않아, 화면에서 그 이유를 보여 줘야 합니다. */
			boolean telegramDestinationEnabled,
			boolean warningEnabled,
			boolean criticalEnabled,
			boolean sendResolved,
			boolean enabled) {

		static BindingResponse of(CheckNotificationBinding binding) {
			return new BindingResponse(binding.getId(), binding.getDestination().getId(),
					binding.getDestination().getName(), binding.getDestination().isEnabled(),
					binding.isWarningEnabled(), binding.isCriticalEnabled(), binding.isSendResolved(),
					binding.isEnabled());
		}
	}

}
