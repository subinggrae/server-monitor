package kr.co.unidocs.monitoring.check;

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

import java.time.Instant;

/**
 * Blackbox Exporter가 실제로 확인하는 주소 하나입니다.
 *
 * target에 들어가는 값은 Check Type에 따라 다릅니다.
 * ICMP와 TCP는 host 또는 IP, HTTP와 HTTPS는 전체 URL입니다.
 */
@Entity
@Table(name = "monitoring_check_targets")
public class MonitoringCheckTarget {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "check_id", nullable = false)
	private MonitoringCheck check;

	@Column(name = "target", nullable = false, length = 500)
	private String target;

	/** TCP Target에만 씁니다. 같은 Check 안에서도 node마다 다를 수 있어 Check가 아니라 여기에 둡니다. */
	@Column(name = "port")
	private Integer port;

	@Column(name = "enabled", nullable = false)
	private boolean enabled = true;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected MonitoringCheckTarget() {
	}

	public MonitoringCheckTarget(MonitoringCheck check, String target, Integer port, boolean enabled) {
		this.check = check;
		this.target = target;
		this.port = port;
		this.enabled = enabled;
	}

	public void update(String target, Integer port, boolean enabled) {
		this.target = target;
		this.port = port;
		this.enabled = enabled;
	}

	/** Prometheus가 Blackbox Exporter에 넘길 주소입니다. */
	public String blackboxTarget() {
		if (check.getType() == CheckType.TCP) {
			return target + ":" + port;
		}
		return target;
	}

	public boolean isActive() {
		return enabled && check.isEnabled();
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

	public MonitoringCheck getCheck() {
		return check;
	}

	public String getTarget() {
		return target;
	}

	public Integer getPort() {
		return port;
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
