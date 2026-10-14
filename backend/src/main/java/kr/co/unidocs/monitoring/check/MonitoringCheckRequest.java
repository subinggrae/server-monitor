package kr.co.unidocs.monitoring.check;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * targets는 전체 목록입니다. id가 있으면 수정, 없으면 추가, 목록에 없는 기존 Target은 삭제합니다.
 * id를 그대로 보내야 Prometheus에서 쌓아 온 time series가 끊기지 않습니다.
 *
 * Timeout과 Expected Status는 받지 않습니다.
 * Blackbox Exporter가 probe 요청의 query parameter로 그 값을 받지 않기 때문입니다.
 */
public record MonitoringCheckRequest(

		@NotBlank(message = "Check 이름을 입력해 주세요.")
		@Size(max = 100, message = "Check 이름은 100자까지 입력할 수 있습니다.")
		String name,

		@NotNull(message = "Check 종류를 선택해 주세요.")
		CheckType type,

		@NotBlank(message = "환경을 입력해 주세요.")
		@Size(max = 50, message = "환경은 50자까지 입력할 수 있습니다.")
		String environment,

		@Size(max = 500, message = "설명은 500자까지 입력할 수 있습니다.")
		String description,

		boolean enabled,

		/** null이면 등록할 때는 15초, 수정할 때는 기존 주기입니다. */
		@Min(value = 10, message = "수집 주기는 10초에서 240초 사이로 입력해 주세요.")
		@Max(value = 240, message = "수집 주기는 10초에서 240초 사이로 입력해 주세요.")
		Integer scrapeIntervalSeconds,

		@NotEmpty(message = "확인할 대상을 하나 이상 입력해 주세요.")
		@Size(max = 50, message = "한 Check에는 Target을 50개까지 넣을 수 있습니다.")
		@Valid
		List<TargetRequest> targets) {

	public record TargetRequest(

			Long id,

			@NotBlank(message = "확인할 대상을 입력해 주세요.")
			@Size(max = 500, message = "대상은 500자까지 입력할 수 있습니다.")
			String target,

			@Min(value = 1, message = "Port는 1부터 65535 사이의 값이어야 합니다.")
			@Max(value = 65535, message = "Port는 1부터 65535 사이의 값이어야 합니다.")
			Integer port,

			boolean enabled) {
	}

}
