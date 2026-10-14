package kr.co.unidocs.monitoring.snmp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ServerSnmpConfigRepository extends JpaRepository<ServerSnmpConfig, Long> {

	Optional<ServerSnmpConfig> findByServerId(Long serverId);

	List<ServerSnmpConfig> findByEnabledTrue();

	void deleteByServerId(Long serverId);

}
