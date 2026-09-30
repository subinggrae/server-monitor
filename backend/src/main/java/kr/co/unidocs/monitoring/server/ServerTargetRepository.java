package kr.co.unidocs.monitoring.server;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ServerTargetRepository extends JpaRepository<ServerTarget, Long> {

	List<ServerTarget> findAllByOrderByNameAsc();

	boolean existsByName(String name);

}
