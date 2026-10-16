package kr.co.unidocs.monitoring.prometheus;

import java.time.Instant;

/** 리소스 하나의 지금 값입니다. CPU는 크기와 사용량이 없습니다. 없는 값은 null입니다. */
public record ResourceValue(Double usagePercent, Long usedBytes, Long sizeBytes, Instant sampledAt) {
}
