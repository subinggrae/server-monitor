package kr.co.unidocs.monitoring.notification.telegram;

import java.time.Instant;

/** Bot Token은 담지 않습니다. 일부만 가린 값도 담지 않습니다. */
public record TelegramDestinationResponse(
		Long id,
		String name,
		Long chatId,
		Integer messageThreadId,
		boolean enabled,
		Instant createdAt,
		Instant updatedAt) {

	public static TelegramDestinationResponse of(TelegramDestination destination) {
		return new TelegramDestinationResponse(destination.getId(), destination.getName(), destination.getChatId(),
				destination.getMessageThreadId(), destination.isEnabled(), destination.getCreatedAt(),
				destination.getUpdatedAt());
	}

}
