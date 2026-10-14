package kr.co.unidocs.monitoring.notification.routing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CheckNotificationBindingRepository extends JpaRepository<CheckNotificationBinding, Long> {

	// join fetch가 없으면 Alert를 받을 때마다 연결 개수만큼 query가 추가로 실행됩니다.

	@Query("""
			select b from CheckNotificationBinding b
			join fetch b.destination d
			where b.checkId = :checkId
			order by b.id asc
			""")
	List<CheckNotificationBinding> findAllByCheckId(Long checkId);

	@Query("""
			select b from CheckNotificationBinding b
			join fetch b.destination d
			order by b.checkId asc, b.id asc
			""")
	List<CheckNotificationBinding> findAllWithDestination();

	void deleteAllByCheckId(Long checkId);

}
