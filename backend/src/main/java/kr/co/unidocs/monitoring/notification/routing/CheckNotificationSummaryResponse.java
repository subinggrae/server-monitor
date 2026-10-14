package kr.co.unidocs.monitoring.notification.routing;

import kr.co.unidocs.monitoring.notification.routing.CheckNotificationResponse.BindingResponse;

import java.util.List;

/** 연결이 하나도 없는 Check도 포함합니다. 알림 대상이 비어 있는 Check를 찾는 것이 이 목록의 쓸모입니다. */
public record CheckNotificationSummaryResponse(
		Long checkId,
		String checkName,
		String environment,
		boolean checkEnabled,
		List<BindingResponse> bindings) {
}
