package kr.co.unidocs.monitoring.notification.telegram;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Bot Token은 받지 않습니다. 운영자가 화면에서 넣을 값이 아닙니다. (CLAUDE.md 11번) */
public record TelegramDestinationRequest(

		@NotBlank(message = "그룹 이름을 입력해 주세요.")
		@Size(max = 100, message = "그룹 이름은 100자까지 입력할 수 있습니다.")
		String name,

		@NotNull(message = "Chat ID를 입력해 주세요. Telegram 그룹에서 /register를 보내면 자동으로 등록할 수도 있습니다.")
		Long chatId,

		@Min(value = 1, message = "Message Thread ID는 1 이상의 값이어야 합니다.")
		Integer messageThreadId,

		boolean enabled) {
}
