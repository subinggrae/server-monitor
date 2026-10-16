package kr.co.unidocs.monitoring.resource;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** resources는 전체 목록입니다. 빠진 것은 모니터링에서 제외합니다. */
public record MonitoredResourceRequest(

		@NotNull(message = "감시할 리소스 목록이 없습니다. 화면을 새로 고친 뒤 다시 시도해 주세요.")
		@Valid
		List<Item> resources) {

	public record Item(

			@NotNull(message = "리소스 종류를 선택해 주세요.")
			ResourceType resourceType,

			@NotBlank(message = "리소스 식별자가 없습니다. 리소스를 다시 찾아 주세요.")
			@Size(max = 255, message = "리소스 식별자는 255자까지 저장할 수 있습니다.")
			String resourceKey,

			@Size(max = 255, message = "표시 이름은 255자까지 입력할 수 있습니다.")
			String displayName,

			@Size(max = 500, message = "설명은 500자까지 입력할 수 있습니다.")
			String description) {
	}

}
