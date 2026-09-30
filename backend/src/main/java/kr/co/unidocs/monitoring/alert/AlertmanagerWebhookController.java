package kr.co.unidocs.monitoring.alert;

import kr.co.unidocs.monitoring.config.ManagerSecrets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Docker network 안에서만 부르는 주소여도 token을 확인합니다.
 * 같은 network의 다른 container가 가짜 Alert를 보내면 운영자에게 잘못된 장애 알림이 갑니다.
 *
 * token이 없으면 요청을 받지 않습니다. 받아 주면 설정을 빠뜨린 것을 아무도 모르게 됩니다.
 */
@RestController
@RequestMapping("/api/integrations/alertmanager")
class AlertmanagerWebhookController {

	private static final String BEARER_PREFIX = "Bearer ";

	private static final Logger log = LoggerFactory.getLogger(AlertmanagerWebhookController.class);

	private final AlertIngestService alertIngestService;

	private final ManagerSecrets secrets;

	AlertmanagerWebhookController(AlertIngestService alertIngestService, ManagerSecrets secrets) {
		this.alertIngestService = alertIngestService;
		this.secrets = secrets;
	}

	@PostMapping("/webhook")
	ResponseEntity<Void> receive(@RequestBody(required = false) AlertmanagerWebhookPayload payload,
			@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {

		if (!authorized(authorization)) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}

		// Telegram 전송에 실패해도 성공으로 답합니다. 실패를 돌려주면 Alertmanager가 계속 재시도합니다.
		alertIngestService.handle(payload);
		return ResponseEntity.ok().build();
	}

	private boolean authorized(String authorization) {
		String expected = secrets.alertmanagerWebhookToken();
		if (expected == null) {
			log.warn("Alertmanager webhook token이 설정되지 않아 요청을 거부했습니다. "
					+ "Docker Secret(monitoring-manager.notification.alertmanager-webhook-token) 또는 "
					+ "ALERTMANAGER_WEBHOOK_TOKEN을 설정해 주세요.");
			return false;
		}

		if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
			log.warn("Alertmanager webhook 요청에 Authorization header가 없어 거부했습니다.");
			return false;
		}

		String provided = authorization.substring(BEARER_PREFIX.length()).trim();
		// 앞부분만 보고 빨리 실패하지 않도록 고정 시간 비교를 씁니다.
		boolean matched = MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),
				expected.getBytes(StandardCharsets.UTF_8));

		if (!matched) {
			log.warn("Alertmanager webhook 요청의 token이 맞지 않아 거부했습니다.");
		}
		return matched;
	}

}
