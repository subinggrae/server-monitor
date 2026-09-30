package kr.co.unidocs.monitoring.resource;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MonitoredResourceRepository extends JpaRepository<MonitoredResource, Long> {

	List<MonitoredResource> findByServerIdOrderByResourceTypeAscResourceKeyAsc(Long serverId);

}
