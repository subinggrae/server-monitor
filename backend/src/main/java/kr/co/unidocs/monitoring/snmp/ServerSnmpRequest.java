package kr.co.unidocs.monitoring.snmp;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * community는 쓰기 전용입니다. 응답에는 들어가지 않습니다. (CLAUDE.md 11번)
 *
 * community가 null이면 기존 값을 그대로 둡니다.
 * 수정 화면을 열었다는 이유로 저장해 둔 값이 지워지면 안 됩니다.
 */
public record ServerSnmpRequest(

		@NotNull(message = "SNMP 버전을 선택해 주세요.")
		SnmpVersion version,

		@NotNull(message = "포트를 입력해 주세요.")
		@Min(value = 1, message = "포트는 1에서 65535 사이 숫자로 입력해 주세요.")
		@Max(value = 65535, message = "포트는 1에서 65535 사이 숫자로 입력해 주세요.")
		Integer port,

		String community,

		@NotNull(message = "사용 여부를 지정해 주세요.")
		Boolean enabled,

		/**
		 * null이면 등록할 때는 15초, 수정할 때는 기존 주기입니다.
		 * Integer로 받으면 Jackson이 15.5를 15로 잘라 검증을 지나가므로 BigDecimal로 받습니다.
		 */
		@DecimalMin(value = "10", message = "수집 주기는 10초에서 240초 사이 정수로 입력해 주세요.")
		@DecimalMax(value = "240", message = "수집 주기는 10초에서 240초 사이 정수로 입력해 주세요.")
		@Digits(integer = 3, fraction = 0, message = "수집 주기는 10초에서 240초 사이 정수로 입력해 주세요.")
		BigDecimal scrapeIntervalSeconds) {

	/** 검증을 통과한 요청에서만 부릅니다. 그래서 정수로 바꿀 수 있습니다. */
	public Integer scrapeIntervalSecondsAsInt() {
		return (scrapeIntervalSeconds != null) ? scrapeIntervalSeconds.intValueExact() : null;
	}

	public boolean hasNewCommunity() {
		return community != null && !community.isBlank();
	}

	@Override
	public String toString() {
		return "ServerSnmpRequest[version=" + version + ", port=" + port + ", enabled=" + enabled + "]";
	}

}
