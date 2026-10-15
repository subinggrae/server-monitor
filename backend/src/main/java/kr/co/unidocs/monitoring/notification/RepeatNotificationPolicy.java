package kr.co.unidocs.monitoring.notification;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Alert가 이어지는 동안 같은 그룹에 Telegram 알림을 다시 보낼지 정합니다.
 *
 * Alertmanager는 이어지는 Alert를 group_interval(5m)마다 webhook으로 다시 보냅니다(repeat_interval 4m).
 * 실제로 다시 보낼지는 Check마다 정한 재발송 주기로 여기서 판단합니다.
 */
final class RepeatNotificationPolicy {

	/**
	 * 전송 시각은 Telegram 호출이 끝난 뒤에 기록되므로, 주기만큼 뒤에 온 webhook에서도 경과 시간이 조금 모자랍니다.
	 * 이 차이로 한 webhook 간격(5분)을 건너뛰지 않게 둡니다. webhook 간격보다 짧아야 한 간격 일찍 보내지 않습니다.
	 */
	static final Duration TOLERANCE = Duration.ofMinutes(1);

	private RepeatNotificationPolicy() {
	}

	/** 보낸 기록이 없으면 첫 알림이거나 앞선 전송이 모두 실패한 것이므로 보냅니다. */
	static boolean shouldSend(Optional<Instant> lastSentAt, Duration repeatInterval, Instant now) {
		return lastSentAt.map(sentAt -> !now.isBefore(sentAt.plus(repeatInterval).minus(TOLERANCE))).orElse(true);
	}

}
