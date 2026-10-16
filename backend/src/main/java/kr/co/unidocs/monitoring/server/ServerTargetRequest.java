package kr.co.unidocs.monitoring.server;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ServerTargetRequest(

		@NotBlank(message = "서버 이름을 입력해 주세요.")
		@Size(max = 100, message = "서버 이름은 100자까지 입력할 수 있습니다.")
		String name,

		@NotBlank(message = "호스트 이름이나 IP를 입력해 주세요.")
		@Size(max = 255, message = "호스트 이름은 255자까지 입력할 수 있습니다.")
		// IP와 hostname만 받습니다. http:// 같은 scheme이나 경로가 들어오면 거부합니다.
		@Pattern(regexp = "^[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?$",
				message = "IP나 호스트 이름만 입력할 수 있습니다. 예: 10.10.10.15, web01.internal")
		String host,

		@NotBlank(message = "환경을 입력해 주세요.")
		@Size(max = 50, message = "환경은 50자까지 입력할 수 있습니다.")
		String environment,

		/** enum으로 받으면 모르는 값이 칸별 오류가 아니라 요청 전체 오류가 되므로 문자열로 받습니다. */
		@NotNull(message = "OS를 선택해 주세요.")
		@Pattern(regexp = "LINUX|WINDOWS", message = "OS는 Linux나 Windows 가운데 하나를 선택해 주세요.")
		String os,

		@Size(max = 500, message = "설명은 500자까지 입력할 수 있습니다.")
		String description,

		boolean enabled) {

	/** 검증을 통과한 요청에서만 부릅니다. */
	public ServerOs serverOs() {
		return ServerOs.valueOf(os);
	}

}
