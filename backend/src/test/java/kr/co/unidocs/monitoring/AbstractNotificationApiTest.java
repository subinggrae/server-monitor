package kr.co.unidocs.monitoring;

import kr.co.unidocs.monitoring.notification.DeliveryStatus;
import kr.co.unidocs.monitoring.notification.NotificationDelivery;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Alertmanager → Manager Webhook → Notification Binding → Telegram 경로를 검증합니다. */
abstract class AbstractNotificationApiTest extends AbstractMonitoringApiTest {

	private static final String FIRING_STARTS_AT = "2026-09-22T07:20:00.000Z";

	/** 발생 중인 Alert의 endsAt입니다. Alertmanager가 "값 없음"으로 보냅니다. */
	private static final String NO_END = "0001-01-01T00:00:00Z";

	// =====================================================================
	// Telegram Destination
	// =====================================================================

	@Test
	void createsTelegramDestination() throws Exception {
		String body = """
				{"name":"서비스 운영팀","chatId":-1001234567890,"messageThreadId":null,"enabled":true}
				""";

		mockMvc.perform(post("/api/telegram/destinations").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("서비스 운영팀"))
				.andExpect(jsonPath("$.chatId").value(-1001234567890L))
				.andExpect(jsonPath("$.messageThreadId").doesNotExist())
				.andExpect(jsonPath("$.enabled").value(true));
	}

	@Test
	void telegramDestinationResponseNeverContainsBotToken() throws Exception {
		createDestination("서비스 운영팀", -1001234567890L);

		String response = mockMvc.perform(get("/api/telegram/destinations"))
				.andReturn()
				.getResponse()
				.getContentAsString();

		// application.yml에 test-bot-token을 넣고 실행합니다. 응답에 섞여 나오면 안 됩니다. (CLAUDE.md 11번)
		assertThat(response).doesNotContain("test-bot-token").doesNotContain("botToken");
	}

	@Test
	void telegramStatusDoesNotExposeBotToken() throws Exception {
		String response = mockMvc.perform(get("/api/telegram/status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.configured").exists())
				.andExpect(jsonPath("$.webhookConfigured").exists())
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertThat(response).doesNotContain("test-bot-token");
	}

	@Test
	void updatesTelegramDestination() throws Exception {
		long destinationId = createDestination("서비스 운영팀", -1001234567890L);

		String body = """
				{"name":"야간 장애 대응팀","chatId":-1001234567890,"messageThreadId":42,"enabled":false}
				""";

		mockMvc.perform(put("/api/telegram/destinations/" + destinationId).contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("야간 장애 대응팀"))
				.andExpect(jsonPath("$.messageThreadId").value(42))
				.andExpect(jsonPath("$.enabled").value(false));
	}

	@Test
	void deletesTelegramDestination() throws Exception {
		long destinationId = createDestination("서비스 운영팀", -1001234567890L);

		mockMvc.perform(delete("/api/telegram/destinations/" + destinationId)).andExpect(status().isNoContent());
		mockMvc.perform(get("/api/telegram/destinations/" + destinationId)).andExpect(status().isNotFound());
	}

	@Test
	void rejectsDuplicatedChatId() throws Exception {
		createDestination("서비스 운영팀", -1001234567890L);

		String body = """
				{"name":"다른 이름","chatId":-1001234567890,"messageThreadId":null,"enabled":true}
				""";

		// topic이 없는 그룹은 DB의 UNIQUE로 막히지 않습니다. 애플리케이션에서 확인합니다.
		mockMvc.perform(post("/api/telegram/destinations").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("이미 등록된 Telegram 그룹입니다. 목록에서 기존 그룹을 확인해 주세요."));
	}

	@Test
	void allowsSameChatWithDifferentTopic() throws Exception {
		createDestination("일반 알림", -1001234567890L, 10, true);

		// 같은 그룹이라도 topic이 다르면 서로 다른 대상입니다.
		mockMvc.perform(post("/api/telegram/destinations").contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"야간 알림","chatId":-1001234567890,"messageThreadId":20,"enabled":true}
				""")).andExpect(status().isCreated());
	}

	@Test
	void sendsTestMessageOnRequest() throws Exception {
		long destinationId = createDestination("서비스 운영팀", -1001234567890L);

		mockMvc.perform(post("/api/telegram/destinations/" + destinationId + "/test"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.message").value("테스트 메시지를 보냈습니다."));

		verify(telegramClient).sendMessage(eq(-1001234567890L), any(), anyString());
	}

	@Test
	void testMessageFailureDoesNotExposeInternalDetails() throws Exception {
		long destinationId = createDestination("서비스 운영팀", -1001234567890L);
		givenTelegramFails("Forbidden: bot was kicked from the supergroup chat");

		mockMvc.perform(post("/api/telegram/destinations/" + destinationId + "/test"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.message")
						.value("Telegram 메시지를 보내지 못했습니다. Bot 설정과 그룹 권한을 확인해 주세요."));
	}

	// =====================================================================
	// Telegram /register
	// =====================================================================

	@Test
	void registersGroupFromTelegramCommand() throws Exception {
		postTelegramWebhook(telegramUpdate("group", -100123L, "운영팀", "/register", null, false),
				TELEGRAM_WEBHOOK_SECRET).andExpect(status().isOk());

		assertThat(telegramDestinationRepository.findAll()).hasSize(1);
		assertThat(telegramDestinationRepository.findAll().get(0).getName()).isEqualTo("운영팀");
		// 등록했다고 답장합니다.
		verify(telegramClient).sendMessage(eq(-100123L), any(), anyString());
	}

	@Test
	void registersSupergroupWithBotNameSuffix() throws Exception {
		postTelegramWebhook(telegramUpdate("supergroup", -1001234567890L, "운영 supergroup",
				"/register@monitoring_manager_bot", null, false), TELEGRAM_WEBHOOK_SECRET)
				.andExpect(status().isOk());

		assertThat(telegramDestinationRepository.findAll()).hasSize(1);
	}

	@Test
	void doesNotCreateDuplicateWhenRegisteredTwice() throws Exception {
		String update = telegramUpdate("supergroup", -1001234567890L, "운영팀", "/register", null, false);

		postTelegramWebhook(update, TELEGRAM_WEBHOOK_SECRET).andExpect(status().isOk());
		postTelegramWebhook(update, TELEGRAM_WEBHOOK_SECRET).andExpect(status().isOk());

		assertThat(telegramDestinationRepository.findAll()).hasSize(1);
	}

	@Test
	void storesMessageThreadIdForForumTopic() throws Exception {
		postTelegramWebhook(telegramUpdate("supergroup", -1001234567890L, "운영 forum", "/register", 77, true),
				TELEGRAM_WEBHOOK_SECRET).andExpect(status().isOk());

		assertThat(telegramDestinationRepository.findAll().get(0).getMessageThreadId()).isEqualTo(77);
	}

	@Test
	void ignoresThreadIdWhenChatIsNotForum() throws Exception {
		// 일반 supergroup에서는 답글에도 message_thread_id가 붙습니다. topic으로 보면 안 됩니다.
		postTelegramWebhook(telegramUpdate("supergroup", -1001234567890L, "운영팀", "/register", 77, false),
				TELEGRAM_WEBHOOK_SECRET).andExpect(status().isOk());

		assertThat(telegramDestinationRepository.findAll().get(0).getMessageThreadId()).isNull();
	}

	@Test
	void rejectsTelegramWebhookWithWrongSecret() throws Exception {
		postTelegramWebhook(telegramUpdate("group", -100123L, "운영팀", "/register", null, false), "wrong-secret")
				.andExpect(status().isUnauthorized());

		assertThat(telegramDestinationRepository.findAll()).isEmpty();
	}

	@Test
	void ignoresOrdinaryTelegramMessage() throws Exception {
		postTelegramWebhook(telegramUpdate("group", -100123L, "운영팀", "오늘 배포 언제 하나요?", null, false),
				TELEGRAM_WEBHOOK_SECRET).andExpect(status().isOk());

		assertThat(telegramDestinationRepository.findAll()).isEmpty();
		verify(telegramClient, never()).sendMessage(anyLong(), any(), anyString());
	}

	@Test
	void ignoresRegisterFromPrivateChat() throws Exception {
		postTelegramWebhook(telegramUpdate("private", 12345L, null, "/register", null, false),
				TELEGRAM_WEBHOOK_SECRET).andExpect(status().isOk());

		assertThat(telegramDestinationRepository.findAll()).isEmpty();
	}

	// =====================================================================
	// Notification Binding
	// =====================================================================

	@Test
	void connectsOneTelegramGroupToCheck() throws Exception {
		long checkId = createCheck("전자결재 Health", "HTTP", "http://10.0.0.11:8080/health", null, true);
		long destinationId = createDestination("서비스 운영팀", -1001234567890L);

		bindNotification(checkId, destinationId);

		mockMvc.perform(get("/api/checks/" + checkId + "/notifications"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.bindings").isArray())
				.andExpect(jsonPath("$.bindings[0].telegramDestinationId").value(destinationId))
				.andExpect(jsonPath("$.bindings[0].telegramDestinationName").value("서비스 운영팀"))
				.andExpect(jsonPath("$.bindings[0].criticalEnabled").value(true));
	}

	@Test
	void connectsSeveralTelegramGroupsToCheck() throws Exception {
		long checkId = createCheck("전자결재 Health", "HTTP", "http://10.0.0.11:8080/health", null, true);
		long first = createDestination("서비스 운영팀", -1001L);
		long second = createDestination("야간 장애 대응팀", -1002L);

		String body = """
				{"bindings":[
				  {"telegramDestinationId":%d,"warningEnabled":false,"criticalEnabled":true,"sendResolved":true,"enabled":true},
				  {"telegramDestinationId":%d,"warningEnabled":true,"criticalEnabled":true,"sendResolved":false,"enabled":true}
				]}
				""".formatted(first, second);

		mockMvc.perform(put("/api/checks/" + checkId + "/notifications").contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.bindings.length()").value(2))
				.andExpect(jsonPath("$.bindings[1].warningEnabled").value(true))
				.andExpect(jsonPath("$.bindings[1].sendResolved").value(false));
	}

	@Test
	void removesBindingWhenNotInRequest() throws Exception {
		long checkId = createCheck("전자결재 Health", "HTTP", "http://10.0.0.11:8080/health", null, true);
		long first = createDestination("서비스 운영팀", -1001L);
		long second = createDestination("야간 장애 대응팀", -1002L);
		bindNotification(checkId, first);
		bindNotification(checkId, second);

		// 목록을 전체로 다룹니다. 빠진 그룹은 연결이 끊깁니다.
		mockMvc.perform(put("/api/checks/" + checkId + "/notifications").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"bindings":[]}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.bindings.length()").value(0));

		assertThat(checkNotificationBindingRepository.findAllByCheckId(checkId)).isEmpty();
	}

	@Test
	void rejectsSameDestinationTwiceInOneRequest() throws Exception {
		long checkId = createCheck("전자결재 Health", "HTTP", "http://10.0.0.11:8080/health", null, true);
		long destinationId = createDestination("서비스 운영팀", -1001L);

		String body = """
				{"bindings":[
				  {"telegramDestinationId":%d,"warningEnabled":false,"criticalEnabled":true,"sendResolved":true,"enabled":true},
				  {"telegramDestinationId":%d,"warningEnabled":false,"criticalEnabled":true,"sendResolved":true,"enabled":true}
				]}
				""".formatted(destinationId, destinationId);

		mockMvc.perform(put("/api/checks/" + checkId + "/notifications").contentType(MediaType.APPLICATION_JSON)
				.content(body)).andExpect(status().isBadRequest());
	}

	@Test
	void rejectsBindingToUnknownDestination() throws Exception {
		long checkId = createCheck("전자결재 Health", "HTTP", "http://10.0.0.11:8080/health", null, true);

		mockMvc.perform(put("/api/checks/" + checkId + "/notifications").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"bindings":[{"telegramDestinationId":999999,"warningEnabled":false,"criticalEnabled":true,
						 "sendResolved":true,"enabled":true}]}
						"""))
				.andExpect(status().isNotFound());
	}

	@Test
	void deletingCheckRemovesItsBindings() throws Exception {
		long checkId = createCheck("전자결재 Health", "HTTP", "http://10.0.0.11:8080/health", null, true);
		long destinationId = createDestination("서비스 운영팀", -1001L);
		bindNotification(checkId, destinationId);

		mockMvc.perform(delete("/api/checks/" + checkId)).andExpect(status().isNoContent());

		// 고아 데이터가 남으면 안 됩니다. DB의 ON DELETE CASCADE가 처리합니다.
		assertThat(checkNotificationBindingRepository.count()).isZero();
		// 그룹 자체는 남습니다. 다른 Check가 쓰고 있을 수 있습니다.
		assertThat(telegramDestinationRepository.count()).isEqualTo(1);
	}

	@Test
	void deletingDestinationRemovesItsBindings() throws Exception {
		long checkId = createCheck("전자결재 Health", "HTTP", "http://10.0.0.11:8080/health", null, true);
		long destinationId = createDestination("서비스 운영팀", -1001L);
		bindNotification(checkId, destinationId);

		mockMvc.perform(delete("/api/telegram/destinations/" + destinationId)).andExpect(status().isNoContent());

		assertThat(checkNotificationBindingRepository.count()).isZero();
		assertThat(monitoringCheckRepository.count()).isEqualTo(1);
	}

	@Test
	void bindingListIncludesChecksWithoutDestination() throws Exception {
		long withBinding = createCheck("전자결재 Health", "HTTP", "http://10.0.0.11:8080/health", null, true);
		createCheck("사내 포털", "ICMP", "10.0.0.20", null, true);
		bindNotification(withBinding, createDestination("서비스 운영팀", -1001L));

		JsonNode summaries = getJson("/api/notifications/bindings");

		// 알림 대상이 없는 Check를 찾을 수 있어야 합니다.
		assertThat(summaries).hasSize(2);
		int withoutBinding = 0;
		for (JsonNode summary : summaries) {
			if (summary.get("bindings").isEmpty()) {
				withoutBinding++;
			}
		}
		assertThat(withoutBinding).isEqualTo(1);
	}

	// =====================================================================
	// Alertmanager Webhook
	// =====================================================================

	@Test
	void rejectsAlertmanagerWebhookWithoutToken() throws Exception {
		mockMvc.perform(post("/api/integrations/alertmanager/webhook").contentType(MediaType.APPLICATION_JSON)
				.content(alertmanagerPayload("firing", 1L, 1L, "critical", "abc", FIRING_STARTS_AT, NO_END)))
				.andExpect(status().isUnauthorized());

		assertThat(alertEventRepository.count()).isZero();
	}

	@Test
	void rejectsAlertmanagerWebhookWithWrongToken() throws Exception {
		mockMvc.perform(post("/api/integrations/alertmanager/webhook")
				.header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(alertmanagerPayload("firing", 1L, 1L, "critical", "abc", FIRING_STARTS_AT, NO_END)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void storesFiringAlert() throws Exception {
		long checkId = createCheck("전자결재 Health", "HTTP", "http://10.0.0.11:8080/health", null, true);
		long targetId = firstTargetId(checkId);

		postAlertmanagerWebhook(
				alertmanagerPayload("firing", checkId, targetId, "critical", "fp-1", FIRING_STARTS_AT, NO_END))
				.andExpect(status().isOk());

		assertThat(alertEventRepository.count()).isEqualTo(1);
		mockMvc.perform(get("/api/alerts"))
				.andExpect(jsonPath("$[0].status").value("FIRING"))
				.andExpect(jsonPath("$[0].alertName").value("MonitoringCheckDown"))
				.andExpect(jsonPath("$[0].checkId").value(checkId))
				.andExpect(jsonPath("$[0].checkTargetId").value(targetId))
				.andExpect(jsonPath("$[0].severity").value("critical"))
				// 발생 중인 Alert의 endsAt은 해소 시각이 아닙니다.
				.andExpect(jsonPath("$[0].resolvedAt").doesNotExist());
	}

	@Test
	void updatesSameAlertInsteadOfCreatingNewRow() throws Exception {
		long checkId = createCheck("전자결재 Health", "HTTP", "http://10.0.0.11:8080/health", null, true);
		long targetId = firstTargetId(checkId);

		postAlertmanagerWebhook(
				alertmanagerPayload("firing", checkId, targetId, "critical", "fp-1", FIRING_STARTS_AT, NO_END));
		postAlertmanagerWebhook(
				alertmanagerPayload("resolved", checkId, targetId, "critical", "fp-1", FIRING_STARTS_AT,
						"2026-09-22T07:25:00.000Z"));

		// 같은 장애 한 번은 한 줄입니다.
		assertThat(alertEventRepository.count()).isEqualTo(1);
		mockMvc.perform(get("/api/alerts"))
				.andExpect(jsonPath("$[0].status").value("RESOLVED"))
				.andExpect(jsonPath("$[0].resolvedAt").exists());
	}

	@Test
	void createsNewRowWhenSameTargetFailsAgainLater() throws Exception {
		long checkId = createCheck("전자결재 Health", "HTTP", "http://10.0.0.11:8080/health", null, true);
		long targetId = firstTargetId(checkId);

		postAlertmanagerWebhook(
				alertmanagerPayload("firing", checkId, targetId, "critical", "fp-1", FIRING_STARTS_AT, NO_END));
		// 해소된 뒤 다시 발생하면 Alertmanager가 startsAt을 새로 줍니다.
		postAlertmanagerWebhook(alertmanagerPayload("firing", checkId, targetId, "critical", "fp-1",
				"2026-09-22T09:00:00.000Z", NO_END));

		assertThat(alertEventRepository.count()).isEqualTo(2);
	}

	@Test
	void sendsTelegramMessageWhenBindingExists() throws Exception {
		long checkId = createCheck("전자결재 Health", "HTTP", "http://10.0.0.11:8080/health", null, true);
		long targetId = firstTargetId(checkId);
		long destinationId = createDestination("서비스 운영팀", -1001L);
		bindNotification(checkId, destinationId);

		postAlertmanagerWebhook(
				alertmanagerPayload("firing", checkId, targetId, "critical", "fp-1", FIRING_STARTS_AT, NO_END))
				.andExpect(status().isOk());

		verify(telegramClient).sendMessage(eq(-1001L), any(), anyString());
		assertThat(deliveriesOf(DeliveryStatus.SENT)).hasSize(1);
	}

	@Test
	void doesNotSendWhenNoBindingExists() throws Exception {
		long checkId = createCheck("전자결재 Health", "HTTP", "http://10.0.0.11:8080/health", null, true);
		long targetId = firstTargetId(checkId);
		createDestination("서비스 운영팀", -1001L);

		postAlertmanagerWebhook(
				alertmanagerPayload("firing", checkId, targetId, "critical", "fp-1", FIRING_STARTS_AT, NO_END))
				.andExpect(status().isOk());

		// 연결이 없으면 아무 그룹에도 보내지 않습니다. 기본 그룹을 몰래 쓰지 않습니다.
		verify(telegramClient, never()).sendMessage(anyLong(), any(), anyString());
		// 그래도 Alert 기록은 남습니다.
		assertThat(alertEventRepository.count()).isEqualTo(1);
	}

	@Test
	void doesNotSendWhenDestinationIsDisabled() throws Exception {
		long checkId = createCheck("전자결재 Health", "HTTP", "http://10.0.0.11:8080/health", null, true);
		long targetId = firstTargetId(checkId);
		long destinationId = createDestination("서비스 운영팀", -1001L, null, false);
		bindNotification(checkId, destinationId);

		postAlertmanagerWebhook(
				alertmanagerPayload("firing", checkId, targetId, "critical", "fp-1", FIRING_STARTS_AT, NO_END))
				.andExpect(status().isOk());

		verify(telegramClient, never()).sendMessage(anyLong(), any(), anyString());
		assertThat(alertEventRepository.count()).isEqualTo(1);
	}

	@Test
	void doesNotSendWhenBindingIsDisabled() throws Exception {
		long checkId = createCheck("전자결재 Health", "HTTP", "http://10.0.0.11:8080/health", null, true);
		long targetId = firstTargetId(checkId);
		long destinationId = createDestination("서비스 운영팀", -1001L);
		bindNotification(checkId, destinationId, false, true, true, false);

		postAlertmanagerWebhook(
				alertmanagerPayload("firing", checkId, targetId, "critical", "fp-1", FIRING_STARTS_AT, NO_END))
				.andExpect(status().isOk());

		verify(telegramClient, never()).sendMessage(anyLong(), any(), anyString());
		assertThat(alertEventRepository.count()).isEqualTo(1);
	}

	@Test
	void doesNotSendCriticalWhenCriticalIsOff() throws Exception {
		long checkId = createCheck("전자결재 Health", "HTTP", "http://10.0.0.11:8080/health", null, true);
		long targetId = firstTargetId(checkId);
		long destinationId = createDestination("서비스 운영팀", -1001L);
		bindNotification(checkId, destinationId, true, false, true, true);

		postAlertmanagerWebhook(
				alertmanagerPayload("firing", checkId, targetId, "critical", "fp-1", FIRING_STARTS_AT, NO_END))
				.andExpect(status().isOk());

		verify(telegramClient, never()).sendMessage(anyLong(), any(), anyString());
		assertThat(alertEventRepository.count()).isEqualTo(1);
	}

	@Test
	void sendsWarningWhenWarningIsEnabled() throws Exception {
		long checkId = createCheck("전자결재 Health", "HTTP", "http://10.0.0.11:8080/health", null, true);
		long targetId = firstTargetId(checkId);
		// critical은 끕니다. warning 설정만 보고 보내는지 확인합니다.
		bindNotification(checkId, createDestination("서비스 운영팀", -1001L), true, false, true, true);

		postAlertmanagerWebhook(
				alertmanagerPayload("firing", checkId, targetId, "warning", "fp-1", FIRING_STARTS_AT, NO_END))
				.andExpect(status().isOk());

		verify(telegramClient).sendMessage(eq(-1001L), any(), anyString());
	}

	@Test
	void doesNotSendWarningWhenWarningIsOff() throws Exception {
		long checkId = createCheck("전자결재 Health", "HTTP", "http://10.0.0.11:8080/health", null, true);
		long targetId = firstTargetId(checkId);
		// 기본 연결은 critical만 받습니다.
		bindNotification(checkId, createDestination("서비스 운영팀", -1001L));

		postAlertmanagerWebhook(
				alertmanagerPayload("firing", checkId, targetId, "warning", "fp-1", FIRING_STARTS_AT, NO_END))
				.andExpect(status().isOk());

		verify(telegramClient, never()).sendMessage(anyLong(), any(), anyString());
		assertThat(alertEventRepository.count()).isEqualTo(1);
	}

	@Test
	void skipsAlertWithUnknownSeverity() throws Exception {
		long checkId = createCheck("전자결재 Health", "HTTP", "http://10.0.0.11:8080/health", null, true);
		long targetId = firstTargetId(checkId);
		long destinationId = createDestination("서비스 운영팀", -1001L);
		bindNotification(checkId, destinationId);

		postAlertmanagerWebhook(
				alertmanagerPayload("firing", checkId, targetId, "page", "fp-1", FIRING_STARTS_AT, NO_END))
				.andExpect(status().isOk());

		// 모르는 severity를 critical로 바꿔서 보내지 않습니다.
		verify(telegramClient, never()).sendMessage(anyLong(), any(), anyString());
		assertThat(alertEventRepository.count()).isEqualTo(1);
	}

	@Test
	void sendsResolvedNotificationWhenEnabled() throws Exception {
		long checkId = createCheck("전자결재 Health", "HTTP", "http://10.0.0.11:8080/health", null, true);
		long targetId = firstTargetId(checkId);
		bindNotification(checkId, createDestination("서비스 운영팀", -1001L), false, true, true, true);

		postAlertmanagerWebhook(alertmanagerPayload("resolved", checkId, targetId, "critical", "fp-1",
				FIRING_STARTS_AT, "2026-09-22T07:25:00.000Z"));

		verify(telegramClient).sendMessage(eq(-1001L), any(), anyString());
	}

	@Test
	void doesNotSendResolvedNotificationWhenDisabled() throws Exception {
		long checkId = createCheck("전자결재 Health", "HTTP", "http://10.0.0.11:8080/health", null, true);
		long targetId = firstTargetId(checkId);
		bindNotification(checkId, createDestination("서비스 운영팀", -1001L), false, true, false, true);

		postAlertmanagerWebhook(alertmanagerPayload("resolved", checkId, targetId, "critical", "fp-1",
				FIRING_STARTS_AT, "2026-09-22T07:25:00.000Z")).andExpect(status().isOk());

		verify(telegramClient, never()).sendMessage(anyLong(), any(), anyString());
		// 전송은 안 해도 상태는 갱신합니다.
		mockMvc.perform(get("/api/alerts")).andExpect(jsonPath("$[0].status").value("RESOLVED"));
	}

	@Test
	void doesNotFailWholeWebhookWhenCheckDoesNotExist() throws Exception {
		// 이미 지운 Check의 Alert가 뒤늦게 도착할 수 있습니다.
		postAlertmanagerWebhook(
				alertmanagerPayload("firing", 999999L, 999999L, "critical", "fp-1", FIRING_STARTS_AT, NO_END))
				.andExpect(status().isOk());

		// 기록은 남기고 전송만 건너뜁니다.
		assertThat(alertEventRepository.count()).isEqualTo(1);
		verify(telegramClient, never()).sendMessage(anyLong(), any(), anyString());
	}

	@Test
	void doesNotFailWhenCheckIdLabelIsMissing() throws Exception {
		postAlertmanagerWebhook(
				alertmanagerPayload("firing", null, null, "critical", "fp-1", FIRING_STARTS_AT, NO_END))
				.andExpect(status().isOk());

		assertThat(alertEventRepository.count()).isEqualTo(1);
		verify(telegramClient, never()).sendMessage(anyLong(), any(), anyString());
	}

	@Test
	void keepsProcessingOtherAlertsWhenOneAlertFails() throws Exception {
		long failingCheckId = createCheck("전자결재 Health", "HTTP", "http://10.0.0.11:8080/health", null, true);
		long healthyCheckId = createCheck("사내 포털", "ICMP", "10.0.0.20", null, true);
		bindNotification(failingCheckId, createDestination("서비스 운영팀", -1001L));
		bindNotification(healthyCheckId, createDestination("야간 장애 대응팀", -1002L));
		given(telegramClient.sendMessage(eq(-1001L), any(), anyString()))
				.willThrow(new IllegalStateException("예상하지 못한 오류"));

		// 실패하는 Alert를 앞에 둡니다. 뒤의 Alert가 처리되지 않으면 그 장애 알림은 사라집니다.
		postAlertmanagerWebhook(alertmanagerPayloadOf("firing",
				alertJson("firing", failingCheckId, firstTargetId(failingCheckId), "critical", "fp-1",
						FIRING_STARTS_AT, NO_END),
				alertJson("firing", healthyCheckId, firstTargetId(healthyCheckId), "critical", "fp-2",
						FIRING_STARTS_AT, NO_END)))
				.andExpect(status().isOk());

		assertThat(alertEventRepository.count()).isEqualTo(2);
		verify(telegramClient).sendMessage(eq(-1002L), any(), anyString());
		assertThat(deliveriesOf(DeliveryStatus.SENT)).hasSize(1);
	}

	@Test
	void recordsFailedDeliveryWhenTelegramRejects() throws Exception {
		long checkId = createCheck("전자결재 Health", "HTTP", "http://10.0.0.11:8080/health", null, true);
		long targetId = firstTargetId(checkId);
		bindNotification(checkId, createDestination("서비스 운영팀", -1001L));
		givenTelegramFails("Bad Request: chat not found");

		// Telegram이 실패해도 Alertmanager에는 성공으로 답합니다.
		// 실패를 돌려주면 Alertmanager가 같은 payload를 계속 다시 보냅니다.
		postAlertmanagerWebhook(
				alertmanagerPayload("firing", checkId, targetId, "critical", "fp-1", FIRING_STARTS_AT, NO_END))
				.andExpect(status().isOk());

		List<NotificationDelivery> failed = deliveriesOf(DeliveryStatus.FAILED);
		assertThat(failed).hasSize(1);
		assertThat(failed.get(0).getErrorMessage()).isEqualTo("Bad Request: chat not found");
	}

	// =====================================================================
	// 중복 전송 방지
	// =====================================================================

	@Test
	void doesNotSendTwiceWhenSameWebhookArrivesAgain() throws Exception {
		long checkId = createCheck("전자결재 Health", "HTTP", "http://10.0.0.11:8080/health", null, true);
		long targetId = firstTargetId(checkId);
		bindNotification(checkId, createDestination("서비스 운영팀", -1001L));

		String payload = alertmanagerPayload("firing", checkId, targetId, "critical", "fp-1", FIRING_STARTS_AT,
				NO_END);

		// Alertmanager는 응답이 늦으면 같은 payload를 곧바로 다시 보냅니다.
		postAlertmanagerWebhook(payload).andExpect(status().isOk());
		postAlertmanagerWebhook(payload).andExpect(status().isOk());

		verify(telegramClient, times(1)).sendMessage(anyLong(), any(), anyString());
		assertThat(deliveriesOf(DeliveryStatus.SENT)).hasSize(1);
		assertThat(deliveriesOf(DeliveryStatus.SKIPPED)).hasSize(1);
	}

	@Test
	void sendsResolvedEvenAfterFiringWasSent() throws Exception {
		long checkId = createCheck("전자결재 Health", "HTTP", "http://10.0.0.11:8080/health", null, true);
		long targetId = firstTargetId(checkId);
		bindNotification(checkId, createDestination("서비스 운영팀", -1001L));

		postAlertmanagerWebhook(
				alertmanagerPayload("firing", checkId, targetId, "critical", "fp-1", FIRING_STARTS_AT, NO_END));
		postAlertmanagerWebhook(alertmanagerPayload("resolved", checkId, targetId, "critical", "fp-1",
				FIRING_STARTS_AT, "2026-09-22T07:25:00.000Z"));

		// 중복 방지는 같은 상태끼리만 봅니다. 복구 알림까지 막으면 안 됩니다.
		verify(telegramClient, times(2)).sendMessage(anyLong(), any(), anyString());
	}

	@Test
	void retriesSendingWhenPreviousAttemptFailed() throws Exception {
		long checkId = createCheck("전자결재 Health", "HTTP", "http://10.0.0.11:8080/health", null, true);
		long targetId = firstTargetId(checkId);
		bindNotification(checkId, createDestination("서비스 운영팀", -1001L));

		String payload = alertmanagerPayload("firing", checkId, targetId, "critical", "fp-1", FIRING_STARTS_AT,
				NO_END);

		givenTelegramFails("Timeout");
		postAlertmanagerWebhook(payload);

		// 실패한 전송은 중복으로 보지 않습니다. 다시 보낼 수 있어야 합니다.
		givenTelegramSendsSuccessfully();
		postAlertmanagerWebhook(payload);

		verify(telegramClient, times(2)).sendMessage(anyLong(), any(), anyString());
		assertThat(deliveriesOf(DeliveryStatus.SENT)).hasSize(1);
		assertThat(deliveriesOf(DeliveryStatus.FAILED)).hasSize(1);
	}

	// =====================================================================
	// helper
	// =====================================================================

	private long firstTargetId(long checkId) throws Exception {
		return getJson("/api/checks/" + checkId).get("targets").get(0).get("id").asLong();
	}

	private List<NotificationDelivery> deliveriesOf(DeliveryStatus status) {
		return notificationDeliveryRepository.findAll().stream()
				.filter(delivery -> delivery.getDeliveryStatus() == status)
				.toList();
	}

	private String telegramUpdate(String chatType, long chatId, String title, String text, Integer messageThreadId,
			boolean forum) {

		return """
				{
				  "update_id": 100,
				  "message": {
				    "message_id": 5,
				    "message_thread_id": %s,
				    "from": {"id": 42, "is_bot": false, "first_name": "운영자"},
				    "chat": {"id": %d, "type": "%s", "title": %s, "is_forum": %s},
				    "date": 1758512345,
				    "text": "%s"
				  }
				}
				""".formatted((messageThreadId == null) ? "null" : messageThreadId.toString(), chatId, chatType,
				(title == null) ? "null" : "\"" + title + "\"", forum, text);
	}

}
