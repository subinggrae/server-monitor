package kr.co.unidocs.monitoring.system;

import kr.co.unidocs.monitoring.config.ManagerSecrets;
import kr.co.unidocs.monitoring.config.MonitoringManagerProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** APP_SECRET_KEY, Telegram Bot Token, DB 계정 정보는 어떤 형태로도 넣지 않습니다. (CLAUDE.md 11번) */
@RestController
@RequestMapping("/api/system")
class SystemInfoController {

	private final String applicationName;

	private final MonitoringManagerProperties properties;

	private final ManagerSecrets secrets;

	SystemInfoController(@Value("${spring.application.name}") String applicationName,
			MonitoringManagerProperties properties, ManagerSecrets secrets) {
		this.applicationName = applicationName;
		this.properties = properties;
		this.secrets = secrets;
	}

	@GetMapping("/info")
	SystemInfo info() {
		// "설정했다"가 아니라 "실제로 읽었다"를 알려 줍니다. 파일이 없으면 Token은 없는 것과 같습니다.
		return new SystemInfo(applicationName, properties.grafana().baseUrl(), secrets.telegramBotTokenConfigured());
	}

	record SystemInfo(String application, String grafanaUrl, boolean telegramConfigured) {
	}

}
