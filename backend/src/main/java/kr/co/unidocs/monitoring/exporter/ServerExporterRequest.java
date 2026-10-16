package kr.co.unidocs.monitoring.exporter;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record ServerExporterRequest(

		@NotNull(message = "사용 여부를 지정해 주세요.")
		Boolean enabled,

		@NotNull(message = "포트를 입력해 주세요.")
		@Min(value = 1, message = "포트는 1에서 65535 사이 숫자로 입력해 주세요.")
		@Max(value = 65535, message = "포트는 1에서 65535 사이 숫자로 입력해 주세요.")
		Integer port,

		/** Integer로 받으면 Jackson이 15.5를 15로 잘라 검증을 지나가므로 BigDecimal로 받습니다. */
		@NotNull(message = "수집 주기를 입력해 주세요.")
		@DecimalMin(value = "10", message = "수집 주기는 10초에서 240초 사이 정수로 입력해 주세요.")
		@DecimalMax(value = "240", message = "수집 주기는 10초에서 240초 사이 정수로 입력해 주세요.")
		@Digits(integer = 3, fraction = 0, message = "수집 주기는 10초에서 240초 사이 정수로 입력해 주세요.")
		BigDecimal scrapeIntervalSeconds) {

	/** 검증을 통과한 요청에서만 부릅니다. 그래서 정수로 바꿀 수 있습니다. */
	int scrapeIntervalSecondsAsInt() {
		return scrapeIntervalSeconds.intValueExact();
	}

}
