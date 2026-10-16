package kr.co.unidocs.monitoring.check;

import kr.co.unidocs.monitoring.common.exception.NotFoundException;

public class MonitoringCheckNotFoundException extends NotFoundException {

	public MonitoringCheckNotFoundException(Long checkId) {
		super("요청한 감시 항목을 찾을 수 없습니다. 목록에서 다시 확인해 주세요.", checkId);
	}

}
