package kr.co.unidocs.monitoring.notification.telegram;

import kr.co.unidocs.monitoring.common.exception.NotFoundException;

public class TelegramDestinationNotFoundException extends NotFoundException {

	public TelegramDestinationNotFoundException(Long destinationId) {
		super("요청한 Telegram 그룹을 찾을 수 없습니다. 목록에서 다시 확인해 주세요.", destinationId);
	}

}
