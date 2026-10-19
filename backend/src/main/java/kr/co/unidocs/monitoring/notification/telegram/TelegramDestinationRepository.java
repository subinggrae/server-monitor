package kr.co.unidocs.monitoring.notification.telegram;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TelegramDestinationRepository extends JpaRepository<TelegramDestination, Long> {

	List<TelegramDestination> findAllByOrderByNameAscIdAsc();

	/**
	 * topic 비교를 query에 넣지 않습니다.
	 * messageThreadId가 null일 때 {@code = null} 비교는 어떤 행에도 맞지 않습니다.
	 */
	List<TelegramDestination> findAllByChatId(Long chatId);

	List<TelegramDestination> findAllByIdIn(List<Long> ids);

}
