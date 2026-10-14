package kr.co.unidocs.monitoring.notification.routing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * bindings는 전체 목록입니다. 목록에 없는 그룹은 연결이 끊깁니다.
 * 빈 목록을 보내면 이 Check는 알림을 보내지 않습니다.
 */
public record CheckNotificationRequest(

		@NotNull(message = "알림 대상 목록이 필요합니다. 없으면 빈 목록을 보내 주세요.")
		@Size(max = 50, message = "한 Check에는 알림 그룹을 50개까지 연결할 수 있습니다.")
		@Valid
		List<BindingRequest> bindings,

		/**
		 * null이면 기존 주기를 그대로 둡니다.
		 * 하한 5분은 alertmanager.yml의 repeat_interval(5m)입니다. 그보다 자주 webhook이 오지 않습니다.
		 * Integer로 받으면 Jackson이 15.5를 15로 잘라 검증을 지나가므로 BigDecimal로 받습니다.
		 */
		@DecimalMin(value = "5", message = "재발송 주기는 5분에서 1440분 사이 정수로 입력해 주세요.")
		@DecimalMax(value = "1440", message = "재발송 주기는 5분에서 1440분 사이 정수로 입력해 주세요.")
		@Digits(integer = 4, fraction = 0, message = "재발송 주기는 5분에서 1440분 사이 정수로 입력해 주세요.")
		BigDecimal repeatIntervalMinutes) {

	/** 검증을 통과한 요청에서만 부릅니다. 그래서 정수로 바꿀 수 있습니다. */
	public Integer repeatIntervalMinutesAsInt() {
		return (repeatIntervalMinutes != null) ? repeatIntervalMinutes.intValueExact() : null;
	}

	public record BindingRequest(

			@NotNull(message = "Telegram 그룹을 선택해 주세요.")
			Long telegramDestinationId,

			boolean warningEnabled,

			boolean criticalEnabled,

			boolean sendResolved,

			boolean enabled) {
	}

}
