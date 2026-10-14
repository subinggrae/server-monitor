package kr.co.unidocs.monitoring.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import kr.co.unidocs.monitoring.alert.AlertStatus;

import java.time.Instant;

/**
 * Telegram 전송 시도 한 번의 기록입니다.
 *
 * Bot Token과 Telegram 응답 전체는 저장하지 않습니다. (CLAUDE.md 11번)
 * telegramDestinationId에 FK를 걸지 않습니다. 그룹을 지워도 전송 기록은 남아야 합니다.
 */
@Entity
@Table(name = "notification_deliveries")
public class NotificationDelivery {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "alert_event_id", nullable = false)
	private Long alertEventId;

	@Column(name = "telegram_destination_id", nullable = false)
	private Long telegramDestinationId;

	@Enumerated(EnumType.STRING)
	@Column(name = "alert_status", nullable = false, length = 20)
	private AlertStatus alertStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "delivery_status", nullable = false, length = 20)
	private DeliveryStatus deliveryStatus;

	@Column(name = "attempted_at", nullable = false)
	private Instant attemptedAt;

	/** Bot Token이 섞여 들어가지 않도록 애플리케이션에서 걸러서 넣습니다. */
	@Column(name = "error_message", length = 500)
	private String errorMessage;

	protected NotificationDelivery() {
	}

	public NotificationDelivery(Long alertEventId, Long telegramDestinationId, AlertStatus alertStatus,
			DeliveryStatus deliveryStatus, String errorMessage) {
		this.alertEventId = alertEventId;
		this.telegramDestinationId = telegramDestinationId;
		this.alertStatus = alertStatus;
		this.deliveryStatus = deliveryStatus;
		this.errorMessage = errorMessage;
	}

	@PrePersist
	void onCreate() {
		this.attemptedAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public Long getAlertEventId() {
		return alertEventId;
	}

	public Long getTelegramDestinationId() {
		return telegramDestinationId;
	}

	public AlertStatus getAlertStatus() {
		return alertStatus;
	}

	public DeliveryStatus getDeliveryStatus() {
		return deliveryStatus;
	}

	public Instant getAttemptedAt() {
		return attemptedAt;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

}
