package kr.co.unidocs.monitoring.notification.telegram;

/**
 * 실패해도 예외를 던지지 않습니다.
 * Telegram 장애가 Alert 수신까지 실패시키면 Alertmanager가 같은 payload를 계속 다시 보냅니다.
 *
 * @param errorMessage Bot Token은 이미 걸러 낸 값이 들어옵니다.
 */
public record TelegramSendResult(boolean success, String errorMessage) {

	private static final TelegramSendResult SENT = new TelegramSendResult(true, null);

	public static TelegramSendResult sent() {
		return SENT;
	}

	public static TelegramSendResult failure(String errorMessage) {
		return new TelegramSendResult(false, errorMessage);
	}

}
