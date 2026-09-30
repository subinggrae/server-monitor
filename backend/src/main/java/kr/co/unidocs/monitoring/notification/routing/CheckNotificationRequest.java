package kr.co.unidocs.monitoring.notification.routing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * bindings는 전체 목록입니다. 목록에 없는 그룹은 연결이 끊깁니다.
 * 빈 목록을 보내면 이 Check는 알림을 보내지 않습니다.
 */
public record CheckNotificationRequest(

		@NotNull(message = "알림 대상 목록이 필요합니다. 없으면 빈 목록을 보내 주세요.")
		@Size(max = 50, message = "한 Check에는 알림 그룹을 50개까지 연결할 수 있습니다.")
		@Valid
		List<BindingRequest> bindings) {

	public record BindingRequest(

			@NotNull(message = "Telegram 그룹을 선택해 주세요.")
			Long telegramDestinationId,

			boolean warningEnabled,

			boolean criticalEnabled,

			boolean sendResolved,

			boolean enabled) {
	}

}
