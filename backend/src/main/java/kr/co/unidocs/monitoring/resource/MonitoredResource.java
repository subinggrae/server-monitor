package kr.co.unidocs.monitoring.resource;

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

/**
 * 사용자가 모니터링하기로 고른 Resource입니다. exporter가 내보낸 목록 전체가 아닙니다.
 *
 * resourceKey는 recording rule의 resource_key와 같습니다. Storage는 Linux mountpoint, Windows 드라이브 이름입니다.
 */
@Entity
@Table(name = "monitored_resources")
public class MonitoredResource {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "server_id", nullable = false)
	private Long serverId;

	@Enumerated(EnumType.STRING)
	@Column(name = "resource_type", nullable = false, length = 20)
	private ResourceType resourceType;

	@Column(name = "resource_key", nullable = false, length = 255)
	private String resourceKey;

	@Column(name = "display_name", nullable = false, length = 255)
	private String displayName;

	@Column(name = "description", length = 500)
	private String description;

	@Column(name = "enabled", nullable = false)
	private boolean enabled = true;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected MonitoredResource() {
	}

	public MonitoredResource(Long serverId, ResourceType resourceType, String resourceKey, String displayName,
			String description) {
		this.serverId = serverId;
		this.resourceType = resourceType;
		this.resourceKey = resourceKey;
		this.displayName = displayName;
		this.description = description;
	}

	/** 다시 찾았을 때 exporter가 준 최신 정보로 맞춥니다. 선택 자체는 그대로 둡니다. */
	public void refreshMetadata(String displayName, String description) {
		this.displayName = displayName;
		this.description = description;
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

	public ResourceType getResourceType() {
		return resourceType;
	}

	public String getResourceKey() {
		return resourceKey;
	}

	public String getDisplayName() {
		return displayName;
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
