package kr.co.unidocs.monitoring.exporter;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ServerExporterConfigRepository extends JpaRepository<ServerExporterConfig, Long> {

	Optional<ServerExporterConfig> findByServerId(Long serverId);

	List<ServerExporterConfig> findByEnabledTrue();

}
