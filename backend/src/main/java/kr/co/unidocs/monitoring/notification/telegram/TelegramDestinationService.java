package kr.co.unidocs.monitoring.notification.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class TelegramDestinationService {

	private static final Logger log = LoggerFactory.getLogger(TelegramDestinationService.class);

	private final TelegramDestinationRepository destinationRepository;

	private final TelegramClient telegramClient;

	TelegramDestinationService(TelegramDestinationRepository destinationRepository, TelegramClient telegramClient) {
		this.destinationRepository = destinationRepository;
		this.telegramClient = telegramClient;
	}

	@Transactional(readOnly = true)
	public List<TelegramDestinationResponse> findAll() {
		return destinationRepository.findAllByOrderByNameAscIdAsc().stream()
				.map(TelegramDestinationResponse::of)
				.toList();
	}

	@Transactional(readOnly = true)
	public TelegramDestinationResponse findById(Long destinationId) {
		return TelegramDestinationResponse.of(getDestination(destinationId));
	}

	@Transactional
	public TelegramDestinationResponse create(TelegramDestinationRequest request) {
		requireNotRegistered(request.chatId(), request.messageThreadId(), null);

		TelegramDestination destination = new TelegramDestination(request.name().trim(), request.chatId(),
				request.messageThreadId(), request.enabled());

		return TelegramDestinationResponse.of(destinationRepository.save(destination));
	}

	@Transactional
	public TelegramDestinationResponse update(Long destinationId, TelegramDestinationRequest request) {
		TelegramDestination destination = getDestination(destinationId);
		requireNotRegistered(request.chatId(), request.messageThreadId(), destinationId);

		destination.update(request.name().trim(), request.chatId(), request.messageThreadId(), request.enabled());

		return TelegramDestinationResponse.of(destination);
	}

	/** 연결해 둔 알림 설정은 함께 지워지지만 전송 기록은 남깁니다. 남지 않으면 확인할 방법이 없습니다. */
	@Transactional
	public void delete(Long destinationId) {
		destinationRepository.delete(getDestination(destinationId));
	}

	/**
	 * 장애 Alert를 일부러 만들어서 확인하면 실제 운영 그룹에 가짜 장애 알림이 갑니다.
	 * 연결 확인은 이 기능으로만 합니다.
	 */
	@Transactional(readOnly = true)
	public TelegramSendResult sendTestMessage(Long destinationId) {
		TelegramDestination destination = getDestination(destinationId);

		TelegramSendResult result = telegramClient.sendMessage(destination.getChatId(),
				destination.getMessageThreadId(), """
						Monitoring Manager 테스트 메시지입니다.

						Telegram 알림 연결이 정상입니다.""");

		if (result.success()) {
			log.info("Telegram 테스트 메시지를 보냈습니다. destination_id={}", destinationId);
		}
		else {
			log.warn("Telegram 테스트 메시지를 보내지 못했습니다. destination_id={} reason={}", destinationId,
					result.errorMessage());
		}

		return result;
	}

	/**
	 * 같은 그룹에서 /register를 여러 번 보내도 알림이 여러 번 가면 안 되므로 이름만 갱신합니다.
	 *
	 * @return 새로 등록했으면 true입니다.
	 */
	@Transactional
	public boolean registerFromTelegram(Long chatId, Integer messageThreadId, String name) {
		Optional<TelegramDestination> existing = findByChat(chatId, messageThreadId);

		if (existing.isPresent()) {
			existing.get().renameFromTelegram(name);
			log.info("이미 등록된 Telegram 그룹입니다. 이름만 갱신했습니다. destination_id={}", existing.get().getId());
			return false;
		}

		TelegramDestination created = destinationRepository
				.save(new TelegramDestination(name, chatId, messageThreadId, true));
		log.info("Telegram 그룹을 등록했습니다. destination_id={} thread={}", created.getId(), messageThreadId);
		return true;
	}

	private Optional<TelegramDestination> findByChat(Long chatId, Integer messageThreadId) {
		return destinationRepository.findAllByChatId(chatId).stream()
				.filter(destination -> destination.sameChat(chatId, messageThreadId))
				.findFirst();
	}

	/** @param currentId 수정 중인 그룹의 id입니다. 자기 자신은 중복으로 보지 않습니다. */
	private void requireNotRegistered(Long chatId, Integer messageThreadId, Long currentId) {
		boolean duplicated = findByChat(chatId, messageThreadId)
				.filter(destination -> !destination.getId().equals(currentId))
				.isPresent();

		if (duplicated) {
			throw new DuplicateTelegramDestinationException(
					"이미 등록된 Telegram 그룹입니다. 목록에서 기존 그룹을 확인해 주세요.");
		}
	}

	private TelegramDestination getDestination(Long destinationId) {
		return destinationRepository.findById(destinationId)
				.orElseThrow(() -> new TelegramDestinationNotFoundException(destinationId));
	}

}
