package kr.co.unidocs.monitoring.notification.telegram;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Telegram은 Update에 훨씬 많은 field를 담아 보냅니다. /register를 알아보는 데 필요한 값만 읽습니다. */
public record TelegramUpdate(Message message) {

	public record Message(

			Chat chat,

			String text,

			/** 일반 supergroup에서도 답글이면 값이 들어옵니다. forum인 그룹에서만 topic으로 취급해야 합니다. */
			@JsonProperty("message_thread_id") Integer messageThreadId) {
	}

	public record Chat(

			Long id,

			String type,

			String title,

			@JsonProperty("is_forum") Boolean isForum) {

		public boolean isGroup() {
			return "group".equals(type) || "supergroup".equals(type);
		}

		public boolean forum() {
			return Boolean.TRUE.equals(isForum);
		}
	}

}
