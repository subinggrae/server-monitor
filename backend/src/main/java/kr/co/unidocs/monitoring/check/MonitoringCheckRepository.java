package kr.co.unidocs.monitoring.check;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

	/** 같은 Check에 딸린 설정을 동시에 저장할 때 차례로 처리하려고 Check 행을 잠급니다. transaction이 끝나면 풀립니다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from MonitoringCheck c where c.id = :checkId")
	Optional<MonitoringCheck> findByIdForUpdate(Long checkId);

}
