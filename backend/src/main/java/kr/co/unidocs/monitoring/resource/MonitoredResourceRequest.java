package kr.co.unidocs.monitoring.resource;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** resources는 전체 목록입니다. 빠진 것은 모니터링에서 제외합니다. */
public record MonitoredResourceRequest(

		@NotNull(message = "모니터링할 Resource 목록을 보내 주세요.")
		@Valid
		List<Item> resources) {

	public record Item(

			@NotNull(message = "Resource 종류를 선택해 주세요.")
			ResourceType resourceType,

			@NotBlank(message = "Resource 식별자가 비어 있습니다.")
			@Size(max = 255, message = "Resource 식별자가 너무 깁니다.")
			String resourceKey,

			@Size(max = 255, message = "표시 이름이 너무 깁니다.")
			String displayName,

			@Size(max = 500, message = "설명이 너무 깁니다.")
			String description,

			Integer snmpIndex) {
	}

}
