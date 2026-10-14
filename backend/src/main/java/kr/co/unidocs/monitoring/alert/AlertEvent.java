package kr.co.unidocs.monitoring.alert;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Alertmanager에서 받은 Alert입니다. 장애 한 번이 한 행입니다.
 *
 * Alertmanager는 같은 Alert를 반복해서 보내므로 (fingerprint, startsAt)으로 묶습니다.
 * fingerprint만 쓰면 같은 대상이 나중에 다시 장애 났을 때 기록이 덮입니다.
 *
 * checkId와 checkTargetId에 FK를 걸지 않습니다. Check를 지워도 장애 기록은 남아야 합니다.
 */
@Entity
@Table(name = "alert_events")
public class AlertEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "fingerprint", nullable = false, length = 100)
	private String fingerprint;

	@Column(name = "alert_name", nullable = false, length = 200)
	private String alertName;

	@Column(name = "check_id")
	private Long checkId;

	@Column(name = "check_target_id")
	private Long checkTargetId;

	/** Prometheus 규칙이 붙인 severity label을 그대로 담습니다. */
	@Column(name = "severity", length = 20)
	private String severity;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private AlertStatus status;

	/** Target을 지운 뒤에도 무엇이 문제였는지 알 수 있게 주소를 남깁니다. */
	@Column(name = "instance", length = 500)
	private String instance;

	@Column(name = "summary", length = 500)
	private String summary;

	@Column(name = "description", length = 1000)
	private String description;

	@Column(name = "starts_at", nullable = false)
	private Instant startsAt;

	@Column(name = "resolved_at")
	private Instant resolvedAt;

	@Column(name = "last_received_at", nullable = false)
	private Instant lastReceivedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected AlertEvent() {
	}

	public AlertEvent(String fingerprint, String alertName, Long checkId, Long checkTargetId, String severity,
			AlertStatus status, String instance, String summary, String description, Instant startsAt,
			Instant resolvedAt) {
		this.fingerprint = fingerprint;
		this.alertName = alertName;
		this.checkId = checkId;
		this.checkTargetId = checkTargetId;
		this.severity = severity;
		this.status = status;
		this.instance = instance;
		this.summary = summary;
		this.description = description;
		this.startsAt = startsAt;
		this.resolvedAt = resolvedAt;
		this.lastReceivedAt = Instant.now();
	}

	public void receiveAgain(AlertStatus status, String summary, String description, Instant resolvedAt) {
		this.status = status;
		this.summary = summary;
		this.description = description;
		if (resolvedAt != null) {
			this.resolvedAt = resolvedAt;
		}
		this.lastReceivedAt = Instant.now();
	}

	@PrePersist
	void onCreate() {
		this.createdAt = Instant.now();
		if (this.lastReceivedAt == null) {
			this.lastReceivedAt = this.createdAt;
		}
	}

	public Long getId() {
		return id;
	}

	public String getFingerprint() {
		return fingerprint;
	}

	public String getAlertName() {
		return alertName;
	}

	public Long getCheckId() {
		return checkId;
	}

	public Long getCheckTargetId() {
		return checkTargetId;
	}

	public String getSeverity() {
		return severity;
	}

	public AlertStatus getStatus() {
		return status;
	}

	public String getInstance() {
		return instance;
	}

	public String getSummary() {
		return summary;
	}

	public String getDescription() {
		return description;
	}

	public Instant getStartsAt() {
		return startsAt;
	}

	public Instant getResolvedAt() {
		return resolvedAt;
	}

	public Instant getLastReceivedAt() {
		return lastReceivedAt;
	}

}
