package kr.co.unidocs.monitoring.notification.routing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Check 하나에 하나씩 두는 알림 설정입니다. 지금은 재발송 주기만 있습니다.
 *
 * checkId를 JPA 관계로 묶지 않습니다. Monitoring Check는 알림을 몰라도 동작해야 합니다.
 * 행이 없는 Check는 기본값을 씁니다. 고아 데이터는 DB의 ON DELETE CASCADE로 막습니다.
 */
@Entity
@Table(name = "check_notification_settings")
public class CheckNotificationSetting {

	/** Alertmanager의 repeat_interval이 1시간이던 때와 같은 값입니다. */
	public static final int DEFAULT_REPEAT_INTERVAL_MINUTES = 60;

	@Id
	@Column(name = "check_id")
	private Long checkId;

	@Column(name = "repeat_interval_minutes", nullable = false)
	private int repeatIntervalMinutes;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected CheckNotificationSetting() {
	}

	public CheckNotificationSetting(Long checkId, int repeatIntervalMinutes) {
		this.checkId = checkId;
		this.repeatIntervalMinutes = repeatIntervalMinutes;
	}

	public void changeRepeatIntervalMinutes(int repeatIntervalMinutes) {
		this.repeatIntervalMinutes = repeatIntervalMinutes;
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

	public Long getCheckId() {
		return checkId;
	}

	public int getRepeatIntervalMinutes() {
		return repeatIntervalMinutes;
	}

}
