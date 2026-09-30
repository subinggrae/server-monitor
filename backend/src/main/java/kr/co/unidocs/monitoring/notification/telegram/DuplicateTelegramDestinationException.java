package kr.co.unidocs.monitoring.notification.telegram;

/**
 * 같은 Chat ID를 두 번 등록하면 같은 그룹에 알림이 두 번 갑니다.
 *
 * topic이 없는 그룹은 DB의 UNIQUE로 막을 수 없습니다. UNIQUE는 NULL을 서로 다른 값으로 보기 때문입니다.
 */
public class DuplicateTelegramDestinationException extends RuntimeException {

	public DuplicateTelegramDestinationException(String message) {
		super(message);
	}

}
