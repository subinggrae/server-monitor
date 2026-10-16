package kr.co.unidocs.monitoring.exporter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/** 서버당 0~1행입니다. 수집 대상 전달은 HTTP SD가 이 값으로 합니다. */
@Entity
@Table(name = "server_exporter_configs")
public class ServerExporterConfig {

	/** V9 migration의 열 기본값, prometheus.yml의 전역 scrape_interval과 같아야 합니다. */
	static final int DEFAULT_SCRAPE_INTERVAL_SECONDS = 15;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "server_id", nullable = false, unique = true)
	private Long serverId;

	@Column(name = "enabled", nullable = false)
	private boolean enabled;

	@Column(name = "port", nullable = false)
	private int port;

	@Column(name = "scrape_interval_seconds", nullable = false)
	private int scrapeIntervalSeconds = DEFAULT_SCRAPE_INTERVAL_SECONDS;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ServerExporterConfig() {
	}

	public ServerExporterConfig(Long serverId, boolean enabled, int port, int scrapeIntervalSeconds) {
		this.serverId = serverId;
		this.enabled = enabled;
		this.port = port;
		this.scrapeIntervalSeconds = scrapeIntervalSeconds;
	}

	void update(boolean enabled, int port, int scrapeIntervalSeconds) {
		this.enabled = enabled;
		this.port = port;
		this.scrapeIntervalSeconds = scrapeIntervalSeconds;
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

	public Long getServerId() {
		return serverId;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public int getPort() {
		return port;
	}

	public int getScrapeIntervalSeconds() {
		return scrapeIntervalSeconds;
	}

}
