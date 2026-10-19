package kr.co.unidocs.monitoring.notification.telegram;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * 알림을 받을 Telegram 그룹입니다.
 *
 * Bot Token은 여기에 두지 않습니다. 그룹마다 다른 값이 아니라 시스템 전체의 secret입니다.
 */
@Entity
@Table(name = "telegram_destinations")
public class TelegramDestination {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "name", nullable = false, length = 100)
	private String name;

	/** supergroup은 -100으로 시작하는 큰 값이라 int 범위를 넘습니다. */
	@Column(name = "chat_id", nullable = false)
	private Long chatId;

	/** Forum topic에만 씁니다. 일반 그룹은 null입니다. */
	@Column(name = "message_thread_id")
	private Integer messageThreadId;

	@Column(name = "enabled", nullable = false)
	private boolean enabled = true;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected TelegramDestination() {
	}

	public TelegramDestination(String name, Long chatId, Integer messageThreadId, boolean enabled) {
		this.name = name;
		this.chatId = chatId;
		this.messageThreadId = messageThreadId;
		this.enabled = enabled;
	}

	public void update(String name, Long chatId, Integer messageThreadId, boolean enabled) {
		this.name = name;
		this.chatId = chatId;
		this.messageThreadId = messageThreadId;
		this.enabled = enabled;
	}

	/** 사용 여부는 건드리지 않습니다. 운영자가 일부러 꺼 둔 그룹이 다시 켜지면 안 됩니다. */
	public void renameFromTelegram(String name) {
		this.name = name;
	}

	public boolean sameChat(Long chatId, Integer messageThreadId) {
		return Objects.equals(this.chatId, chatId) && Objects.equals(this.messageThreadId, messageThreadId);
	}

	@PrePersist
	void onCreate() {
		this.createdAt = Instant.now();
		this.updatedAt = this.createdAt;
	}

	@PreUpdate
	void onUpdate() {
		this.updatedAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public Long getChatId() {
		return chatId;
	}

	public Integer getMessageThreadId() {
		return messageThreadId;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
