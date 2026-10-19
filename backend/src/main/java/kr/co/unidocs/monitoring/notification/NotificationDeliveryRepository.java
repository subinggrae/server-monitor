package kr.co.unidocs.monitoring.notification;

import kr.co.unidocs.monitoring.alert.AlertStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {

	boolean existsByAlertEventIdAndTelegramDestinationIdAndAlertStatusAndDeliveryStatusAndAttemptedAtAfter(
			Long alertEventId, Long telegramDestinationId, AlertStatus alertStatus, DeliveryStatus deliveryStatus,
			Instant since);

	Optional<NotificationDelivery> findFirstByAlertEventIdAndTelegramDestinationIdAndAlertStatusAndDeliveryStatusOrderByAttemptedAtDesc(
			Long alertEventId, Long telegramDestinationId, AlertStatus alertStatus, DeliveryStatus deliveryStatus);

}
