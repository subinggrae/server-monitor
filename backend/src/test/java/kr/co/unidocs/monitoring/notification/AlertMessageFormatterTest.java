package kr.co.unidocs.monitoring.notification;

import kr.co.unidocs.monitoring.TestProperties;
import kr.co.unidocs.monitoring.notification.routing.AlertSeverity;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Telegram으로 보낼 문구를 확인합니다.
 *
 * 운영자는 메시지만 보고 어디가 문제인지 판단합니다.
 * 대상 주소와 시각이 빠지면 화면을 다시 열어 봐야 합니다.
 */
class AlertMessageFormatterTest {

	private final AlertMessageFormatter formatter = new AlertMessageFormatter(
			TestProperties.defaults("http://prometheus:9090",
					TestProperties.telegram(null, "https://api.telegram.org")));

	@Test
	void writesFiringMessage() {
		String message = formatter.format(notification(false));

		assertThat(message).startsWith("🚨 [CRITICAL] 전자결재 Health");
		assertThat(message).contains("환경: Production");
		assertThat(message).contains("대상: http://10.0.0.13:8080/actuator/health");
		assertThat(message).contains("전자결재 Health 확인에 실패했습니다");
		// 시간대는 설정값을 따릅니다. 기본은 Asia/Seoul이라 UTC 07:20은 16:20입니다.
		assertThat(message).contains("발생: 2026-09-22 16:20:00");
	}

	@Test
	void writesResolvedMessage() {
		String message = formatter.format(notification(true));

		assertThat(message).startsWith("✅ [RESOLVED] 전자결재 Health");
		assertThat(message).contains("상태가 정상으로 돌아왔습니다.");
		assertThat(message).contains("복구: 2026-09-22 16:25:00");
	}

	@Test
	void doesNotUseMarkdownOrHtml() {
		String message = formatter.format(notification(false));

		// 서식 문자를 넣지 않습니다. 주소에 들어 있는 기호 때문에 메시지가 거부될 수 있습니다.
		assertThat(message).doesNotContain("*").doesNotContain("_").doesNotContain("<b>");
	}

	private AlertNotification notification(boolean resolved) {
		return new AlertNotification(1L, 10L, "전자결재 Health", "Production",
				"http://10.0.0.13:8080/actuator/health", AlertSeverity.CRITICAL, "critical", resolved,
				"전자결재 Health 확인에 실패했습니다", Instant.parse("2026-09-22T07:20:00Z"),
				resolved ? Instant.parse("2026-09-22T07:25:00Z") : null);
	}

}
