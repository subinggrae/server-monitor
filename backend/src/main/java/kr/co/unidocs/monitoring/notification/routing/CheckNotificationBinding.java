package kr.co.unidocs.monitoring.notification.routing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import kr.co.unidocs.monitoring.notification.telegram.TelegramDestination;

import java.time.Instant;

/**
 * Monitoring Check와 Telegram 그룹의 연결입니다.
 *
 * checkId를 JPA 관계로 묶지 않습니다. Monitoring Check는 알림을 몰라도 동작해야 합니다.
 * 고아 데이터는 DB의 ON DELETE CASCADE로 막습니다.
 */
@Entity
@Table(name = "monitoring_check_notification_bindings")
public class CheckNotificationBinding {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "check_id", nullable = false)
	private Long checkId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "telegram_destination_id", nullable = false)
	private TelegramDestination destination;

	/** Warning Alert는 Threshold 기능을 만들어야 생깁니다. 지금은 설정만 저장합니다. */
	@Column(name = "warning_enabled", nullable = false)
	private boolean warningEnabled;

	@Column(name = "critical_enabled", nullable = false)
	private boolean criticalEnabled = true;

	@Column(name = "send_resolved", nullable = false)
	private boolean sendResolved = true;

	@Column(name = "enabled", nullable = false)
	private boolean enabled = true;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected CheckNotificationBinding() {
	}

	public CheckNotificationBinding(Long checkId, TelegramDestination destination, boolean warningEnabled,
			boolean criticalEnabled, boolean sendResolved, boolean enabled) {
		this.checkId = checkId;
		this.destination = destination;
		this.warningEnabled = warningEnabled;
		this.criticalEnabled = criticalEnabled;
		this.sendResolved = sendResolved;
		this.enabled = enabled;
	}

	public void update(boolean warningEnabled, boolean criticalEnabled, boolean sendResolved, boolean enabled) {
		this.warningEnabled = warningEnabled;
		this.criticalEnabled = criticalEnabled;
		this.sendResolved = sendResolved;
		this.enabled = enabled;
	}

	/** 알 수 없는 severity는 보내지 않습니다. 운영자가 설정하지 않은 알림이 가면 안 됩니다. */
	public boolean shouldNotify(AlertSeverity severity, boolean resolved) {
		if (!enabled || !destination.isEnabled()) {
			return false;
		}
		if (resolved && !sendResolved) {
			return false;
		}

		return switch (severity) {
			case WARNING -> warningEnabled;
			case CRITICAL -> criticalEnabled;
			case UNKNOWN -> false;
		};
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

	public Long getCheckId() {
		return checkId;
	}

	public TelegramDestination getDestination() {
		return destination;
	}

	public boolean isWarningEnabled() {
		return warningEnabled;
	}

	public boolean isCriticalEnabled() {
		return criticalEnabled;
	}

	public boolean isSendResolved() {
		return sendResolved;
	}

	public boolean isEnabled() {
		return enabled;
	}

}
