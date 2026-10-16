package kr.co.unidocs.monitoring.server;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/** 등록할 때 host에 실제로 연결되는지는 확인하지 않습니다. 연결 여부는 Monitoring Check가 판단합니다. */
@Entity
@Table(name = "server_targets")
public class ServerTarget {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "name", nullable = false, length = 100, unique = true)
	private String name;

	@Column(name = "host", nullable = false, length = 255)
	private String host;

	@Column(name = "environment", nullable = false, length = 50)
	private String environment;

	@Enumerated(EnumType.STRING)
	@Column(name = "os", nullable = false, length = 10)
	private ServerOs os = ServerOs.LINUX;

	@Column(name = "description", length = 500)
	private String description;

	@Column(name = "enabled", nullable = false)
	private boolean enabled = true;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ServerTarget() {
	}

	public ServerTarget(String name, String host, String environment, ServerOs os, String description,
			boolean enabled) {
		this.name = name;
		this.host = host;
		this.environment = environment;
		this.os = os;
		this.description = description;
		this.enabled = enabled;
	}

	public void update(String name, String host, String environment, ServerOs os, String description,
			boolean enabled) {
		this.name = name;
		this.host = host;
		this.environment = environment;
		this.os = os;
		this.description = description;
		this.enabled = enabled;
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

	public String getHost() {
		return host;
	}

	public String getEnvironment() {
		return environment;
	}

	public ServerOs getOs() {
		return os;
	}

	public String getDescription() {
		return description;
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
