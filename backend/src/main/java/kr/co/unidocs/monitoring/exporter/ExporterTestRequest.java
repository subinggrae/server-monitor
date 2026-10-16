package kr.co.unidocs.monitoring.exporter;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 저장하기 전의 포트로 시험할 수 있도록 포트를 요청으로 받습니다. 호스트는 저장된 서버의 값만 씁니다. */
public record ExporterTestRequest(

		@NotNull(message = "포트를 입력해 주세요.")
		@Min(value = 1, message = "포트는 1에서 65535 사이 숫자로 입력해 주세요.")
		@Max(value = 65535, message = "포트는 1에서 65535 사이 숫자로 입력해 주세요.")
		Integer port) {
}
