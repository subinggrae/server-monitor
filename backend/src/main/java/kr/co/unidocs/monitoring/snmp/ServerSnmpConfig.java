package kr.co.unidocs.monitoring.snmp;

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
 * community는 암호문만 가지고 있습니다. 평문은 이 Entity 어디에도 담지 않습니다.
 *
 * snmp_exporter는 scrape 요청으로 community를 받지 않습니다.
 * 그래서 이 이름으로 exporter auth config를 만들고 Prometheus에는 이름만 넘깁니다.
 */
@Entity
@Table(name = "server_snmp_configs")
public class ServerSnmpConfig {

	private static final String AUTH_NAME_PREFIX = "manager_server_";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "server_id", nullable = false, unique = true)
	private Long serverId;

	@Enumerated(EnumType.STRING)
	@Column(name = "snmp_version", nullable = false, length = 10)
	private SnmpVersion version;

	@Column(name = "port", nullable = false)
	private int port;

	@Column(name = "community_cipher", nullable = false, length = 512)
	private String communityCipher;

	@Column(name = "enabled", nullable = false)
	private boolean enabled = true;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ServerSnmpConfig() {
	}

	public ServerSnmpConfig(Long serverId, SnmpVersion version, int port, String communityCipher, boolean enabled) {
		this.serverId = serverId;
		this.version = version;
		this.port = port;
		this.communityCipher = communityCipher;
		this.enabled = enabled;
	}

	/** communityCipher가 null이면 기존 값을 그대로 둡니다. 사용자가 새 값을 입력했을 때만 바뀝니다. */
	public void update(SnmpVersion version, int port, String communityCipher, boolean enabled) {
		this.version = version;
		this.port = port;
		this.enabled = enabled;
		if (communityCipher != null) {
			this.communityCipher = communityCipher;
		}
	}

	/** Prometheus label과 exporter auth config가 함께 쓰는 이름입니다. */
	public String authName() {
		return AUTH_NAME_PREFIX + serverId;
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

	public SnmpVersion getVersion() {
		return version;
	}

	public int getPort() {
		return port;
	}

	public String getCommunityCipher() {
		return communityCipher;
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

	/** 실수로 로그에 넣어도 암호문이 새어 나가지 않게 합니다. */
	@Override
	public String toString() {
		return "ServerSnmpConfig[serverId=" + serverId + ", version=" + version + ", port=" + port + ", enabled="
				+ enabled + "]";
	}

}
