package kr.co.unidocs.monitoring.alert;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AlertEventRepository extends JpaRepository<AlertEvent, Long> {

	Optional<AlertEvent> findByFingerprintAndStartsAt(String fingerprint, Instant startsAt);

	List<AlertEvent> findAllByOrderByLastReceivedAtDescIdDesc(Limit limit);

}
