package kr.co.unidocs.monitoring.server;

import kr.co.unidocs.monitoring.common.exception.NotFoundException;

public class ServerNotFoundException extends NotFoundException {

	public ServerNotFoundException(Long serverId) {
		super("요청한 서버를 찾을 수 없습니다. 목록에서 다시 확인해 주세요.", serverId);
	}

}
