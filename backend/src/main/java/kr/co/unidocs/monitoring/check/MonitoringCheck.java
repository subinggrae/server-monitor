package kr.co.unidocs.monitoring.check;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 사용자가 관리하는 논리적인 모니터링 단위입니다. 확인 주소(Target)를 여러 개 가질 수 있습니다.
 *
 * ServerTarget과 관계를 맺지 않습니다. 같은 애플리케이션이 서버 여러 대에서 동작할 수 있어서,
 * 서버 한 대의 하위 항목으로 두면 나머지 주소의 소속이 맞지 않습니다. (CLAUDE.md 2-1번)
 */
@Entity
@Table(name = "monitoring_checks")
public class MonitoringCheck {

	/** prometheus.yml의 전역 scrape_interval과 같습니다. 이 기능 전에 등록한 Check도 이 주기였습니다. */
	private static final int DEFAULT_SCRAPE_INTERVAL_SECONDS = 15;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "name", nullable = false, length = 100)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(name = "check_type", nullable = false, length = 20)
	private CheckType type;

	@Column(name = "environment", nullable = false, length = 50)
	private String environment;

	@Column(name = "description", length = 500)
	private String description;

	@Column(name = "enabled", nullable = false)
	private boolean enabled = true;

	@Column(name = "scrape_interval_seconds", nullable = false)
	private int scrapeIntervalSeconds = DEFAULT_SCRAPE_INTERVAL_SECONDS;

	@OneToMany(mappedBy = "check", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("id asc")
	private List<MonitoringCheckTarget> targets = new ArrayList<>();

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected MonitoringCheck() {
	}

	/** scrapeIntervalSeconds가 null이면 기본 주기(15초)입니다. */
	public MonitoringCheck(String name, CheckType type, String environment, String description, boolean enabled,
			Integer scrapeIntervalSeconds) {
		this.name = name;
		this.type = type;
		this.environment = environment;
		this.description = description;
		this.enabled = enabled;
		if (scrapeIntervalSeconds != null) {
			this.scrapeIntervalSeconds = scrapeIntervalSeconds;
		}
	}

	/** scrapeIntervalSeconds가 null이면 기존 주기를 그대로 둡니다. */
	public void update(String name, CheckType type, String environment, String description, boolean enabled,
			Integer scrapeIntervalSeconds) {
		this.name = name;
		this.type = type;
		this.environment = environment;
		this.description = description;
		this.enabled = enabled;
		if (scrapeIntervalSeconds != null) {
			this.scrapeIntervalSeconds = scrapeIntervalSeconds;
		}
	}

	public MonitoringCheckTarget addTarget(String target, Integer port, boolean enabled) {
		MonitoringCheckTarget checkTarget = new MonitoringCheckTarget(this, target, port, enabled);
		this.targets.add(checkTarget);
		return checkTarget;
	}

	public void removeTarget(MonitoringCheckTarget checkTarget) {
		this.targets.remove(checkTarget);
	}

	public List<MonitoringCheckTarget> activeTargets() {
		return targets.stream().filter(MonitoringCheckTarget::isActive).toList();
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

	public CheckType getType() {
		return type;
	}

	public String getEnvironment() {
		return environment;
	}

	public String getDescription() {
		return description;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public int getScrapeIntervalSeconds() {
		return scrapeIntervalSeconds;
	}

	public List<MonitoringCheckTarget> getTargets() {
		return targets;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
