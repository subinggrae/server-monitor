package kr.co.unidocs.monitoring.prometheus;

import java.util.List;
import java.util.Map;

/** Label 값은 모두 문자열이어야 합니다. 숫자를 그대로 넣으면 Prometheus가 SD 응답 전체를 거부합니다. */
public record ServiceDiscoveryTarget(List<String> targets, Map<String, String> labels) {
}
