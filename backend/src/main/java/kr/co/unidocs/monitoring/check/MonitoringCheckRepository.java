package kr.co.unidocs.monitoring.check;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface MonitoringCheckRepository extends JpaRepository<MonitoringCheck, Long> {

	// join fetch가 없으면 Check 개수만큼 Target query가 추가로 실행됩니다.

	@Query("""
			select distinct c from MonitoringCheck c
			left join fetch c.targets t
			where c.enabled = true
			order by c.id asc
			""")
	List<MonitoringCheck> findAllActive();

	@Query("""
			select distinct c from MonitoringCheck c
			left join fetch c.targets t
			order by c.name asc, c.id asc
			""")
	List<MonitoringCheck> findAllWithTargets();

	@Query("""
			select c from MonitoringCheck c
			left join fetch c.targets t
			where c.id = :checkId
			""")
	Optional<MonitoringCheck> findByIdWithTargets(Long checkId);

}
