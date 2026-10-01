package kr.co.unidocs.monitoring;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import kr.co.unidocs.monitoring.alert.AlertEventRepository;
import kr.co.unidocs.monitoring.check.MonitoringCheckRepository;
import kr.co.unidocs.monitoring.notification.NotificationDeliveryRepository;
import kr.co.unidocs.monitoring.notification.routing.CheckNotificationBindingRepository;
import kr.co.unidocs.monitoring.notification.telegram.TelegramClient;
import kr.co.unidocs.monitoring.notification.telegram.TelegramDestinationRepository;
import kr.co.unidocs.monitoring.notification.telegram.TelegramSendResult;
import kr.co.unidocs.monitoring.prometheus.PrometheusQueryResult;
import kr.co.unidocs.monitoring.prometheus.PrometheusQueryService;
import kr.co.unidocs.monitoring.resource.MonitoredResourceRepository;
import kr.co.unidocs.monitoring.server.ServerTargetRepository;
import kr.co.unidocs.monitoring.snmp.ServerSnmpConfigRepository;
import kr.co.unidocs.monitoring.snmp.SnmpClient;
import kr.co.unidocs.monitoring.snmp.SnmpExporterReloader;
import kr.co.unidocs.monitoring.snmp.SnmpStorageEntry;
import kr.co.unidocs.monitoring.snmp.SnmpSystemInfo;
import kr.co.unidocs.monitoring.snmp.SnmpWalkResult;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MariaDB와 PostgreSQL에서 같은 검증을 실행합니다. (CLAUDE.md 7번)
 *
 * Prometheus와 Telegram은 Mock으로 대체합니다.
 * 테스트 결과가 외부 시스템 상태에 따라 달라지면 안 되고, 실제 Telegram 그룹으로 메시지가 가면 안 됩니다.
 */
@SpringBootTest(properties = { "monitoring-manager.secret-key=" + TestProperties.SECRET_KEY,
		// Manager는 token이 없으면 Alertmanager webhook 요청을 받지 않습니다.
		"monitoring-manager.notification.alertmanager-webhook-token=" + AbstractManagerDatabaseTest.WEBHOOK_TOKEN,
		"monitoring-manager.telegram.webhook-secret=" + AbstractManagerDatabaseTest.TELEGRAM_WEBHOOK_SECRET,
		"monitoring-manager.telegram.bot-token=123456:test-bot-token",
		// 실제 exporter가 없으므로 auth config는 임시 폴더에 씁니다. 내용은 테스트에서 직접 확인합니다.
		"monitoring-manager.snmp.auth-config-path=${java.io.tmpdir}/monitoring-manager-test/snmp-auth.yml" })
@AutoConfigureMockMvc
abstract class AbstractManagerDatabaseTest {

	protected static final String WEBHOOK_TOKEN = "test-alertmanager-token";

	protected static final String TELEGRAM_WEBHOOK_SECRET = "test-telegram-webhook-secret";

	@Autowired
	protected MockMvc mockMvc;

	@Autowired
	protected ObjectMapper objectMapper;

	@Autowired
	protected ServerTargetRepository serverTargetRepository;

	@Autowired
	protected MonitoringCheckRepository monitoringCheckRepository;

	@Autowired
	protected TelegramDestinationRepository telegramDestinationRepository;

	@Autowired
	protected CheckNotificationBindingRepository checkNotificationBindingRepository;

	@Autowired
	protected AlertEventRepository alertEventRepository;

	@Autowired
	protected NotificationDeliveryRepository notificationDeliveryRepository;

	@Autowired
	protected JdbcTemplate jdbcTemplate;

	@Autowired
	protected org.springframework.context.ApplicationContext applicationContext;

	@Autowired
	protected ServerSnmpConfigRepository serverSnmpConfigRepository;

	@Autowired
	protected MonitoredResourceRepository monitoredResourceRepository;

	@MockitoBean
	protected PrometheusQueryService prometheusQueryService;

	@MockitoBean
	protected TelegramClient telegramClient;

	/** 장비에 실제로 SNMP 요청을 보내지 않습니다. */
	@MockitoBean
	protected SnmpClient snmpClient;

	/** 테스트에는 snmp_exporter가 없습니다. 파일 생성은 실제로 하고 reload만 대신합니다. */
	@MockitoBean
	protected SnmpExporterReloader snmpExporterReloader;

	@BeforeEach
	void resetDatabaseAndPrometheus() {
		notificationDeliveryRepository.deleteAll();
		alertEventRepository.deleteAll();
		checkNotificationBindingRepository.deleteAll();
		telegramDestinationRepository.deleteAll();
		monitoringCheckRepository.deleteAll();
		monitoredResourceRepository.deleteAll();
		serverSnmpConfigRepository.deleteAll();
		serverTargetRepository.deleteAll();

		givenNoCollectedMetrics();
		givenTelegramSendsSuccessfully();
		givenSnmpUnreachable();
	}

	protected void givenTelegramSendsSuccessfully() {
		given(telegramClient.sendMessage(anyLong(), any(), anyString())).willReturn(TelegramSendResult.sent());
		given(telegramClient.configured()).willReturn(true);
	}

	protected void givenTelegramFails(String reason) {
		given(telegramClient.sendMessage(anyLong(), any(), anyString()))
				.willReturn(TelegramSendResult.failure(reason));
	}

	// ---------------------------------------------------------------------
	// Prometheus Mock 설정
	// ---------------------------------------------------------------------

	/** Prometheus에는 연결되지만 아직 수집된 Metric이 없는 상태입니다. */
	protected void givenNoCollectedMetrics() {
		given(prometheusQueryService.query(anyString())).willReturn(new PrometheusQueryResult(true, List.of()));
	}

	protected void givenPrometheusUnavailable() {
		given(prometheusQueryService.query(anyString())).willReturn(PrometheusQueryResult.unavailable());
	}

	/** probe는 Check가 아니라 주소(Target) 단위로 실행되므로 Mock도 Target id를 기준으로 만듭니다. */
	protected void givenProbeResults(Map<Long, Double> probeSuccessByTargetId) {
		List<PrometheusQueryResult.Sample> samples = new ArrayList<>();
		probeSuccessByTargetId.forEach((targetId, value) -> samples.add(new PrometheusQueryResult.Sample(
				Map.of("check_target_id", String.valueOf(targetId), "job", "manager-blackbox"), value,
				Instant.now())));

		given(prometheusQueryService.query(anyString())).willReturn(new PrometheusQueryResult(true, samples));
	}

	// ---------------------------------------------------------------------
	// SNMP Mock 설정
	// ---------------------------------------------------------------------

	protected void givenSnmpUnreachable() {
		given(snmpClient.probe(any())).willReturn(SnmpSystemInfo.unreachable());
		given(snmpClient.discover(any())).willReturn(SnmpWalkResult.unreachable());
	}

	protected void givenSnmpSystemInfo(String systemName) {
		given(snmpClient.probe(any()))
				.willReturn(new SnmpSystemInfo(true, systemName, "Linux test", 1200L, 12));
	}

	/**
	 * hrStorageTable에는 memory, swap, buffer, disk가 섞여 들어옵니다.
	 * 실제 장비가 주는 모양 그대로 만들어야 분류가 맞는지 확인할 수 있습니다.
	 */
	protected void givenSnmpWalk(int processorCount, SnmpStorageEntry... entries) {
		givenSnmpSystemInfo("test-device");
		given(snmpClient.discover(any())).willReturn(new SnmpWalkResult(true,
				new SnmpSystemInfo(true, "test-device", "Linux test", 1200L, 12), processorCount, List.of(entries)));
	}

	protected SnmpStorageEntry physicalMemory(int index, long totalBytes, long usedBytes) {
		return new SnmpStorageEntry(index, "1.3.6.1.2.1.25.2.1.2", "Physical memory", 1024, totalBytes / 1024,
				usedBytes / 1024);
	}

	protected SnmpStorageEntry fixedDisk(int index, String path, long totalBytes, long usedBytes) {
		return new SnmpStorageEntry(index, "1.3.6.1.2.1.25.2.1.4", path, 4096, totalBytes / 4096, usedBytes / 4096);
	}

	protected SnmpStorageEntry swapSpace(int index) {
		return new SnmpStorageEntry(index, "1.3.6.1.2.1.25.2.1.3", "Swap space", 1024, 2048, 0);
	}

	protected long configureSnmp(long serverId, String community) throws Exception {
		String body = """
				{"version":"V2C","port":161,"community":"%s","enabled":true}
				""".formatted(community);

		mockMvc.perform(put("/api/servers/" + serverId + "/snmp").contentType(MediaType.APPLICATION_JSON)
				.content(body)).andExpect(status().isOk());
		return serverId;
	}

	protected void selectResources(long serverId, String resourcesJson) throws Exception {
		mockMvc.perform(put("/api/servers/" + serverId + "/resources").contentType(MediaType.APPLICATION_JSON)
				.content("{\"resources\":[" + resourcesJson + "]}")).andExpect(status().isOk());
	}

	protected String resourceJson(String type, String key, Integer snmpIndex) {
		return """
				{"resourceType":"%s","resourceKey":"%s","displayName":"%s","description":null,"snmpIndex":%s}
				""".formatted(type, key, key, (snmpIndex == null) ? "null" : snmpIndex.toString()).trim();
	}

	/**
	 * SnmpMetricQueryService는 metric마다 따로 질의합니다.
	 * PromQL에 들어 있는 metric 이름으로 어떤 결과를 돌려줄지 고릅니다.
	 */
	protected void givenSnmpMetricQueries(Map<String, List<PrometheusQueryResult.Sample>> samplesByMetricName) {
		given(prometheusQueryService.query(anyString())).willAnswer(invocation -> {
			String promql = invocation.getArgument(0);
			for (Map.Entry<String, List<PrometheusQueryResult.Sample>> entry : samplesByMetricName.entrySet()) {
				if (promql.contains(entry.getKey())) {
					return new PrometheusQueryResult(true, entry.getValue());
				}
			}
			return new PrometheusQueryResult(true, List.of());
		});
	}

	protected PrometheusQueryResult.Sample snmpSample(long serverId, double value, String... extraLabels) {
		Map<String, String> labels = new java.util.LinkedHashMap<>();
		labels.put("server_id", String.valueOf(serverId));
		labels.put("job", "manager-snmp");
		for (int i = 0; i + 1 < extraLabels.length; i += 2) {
			labels.put(extraLabels[i], extraLabels[i + 1]);
		}
		return new PrometheusQueryResult.Sample(labels, value, Instant.now());
	}

	// ---------------------------------------------------------------------
	// 테스트용 helper
	// ---------------------------------------------------------------------

	protected long createServer(String name, String host) throws Exception {
		return createServer(name, host, true);
	}

	protected long createServer(String name, String host, boolean enabled) throws Exception {
		String body = """
				{"name":"%s","host":"%s","environment":"Development","description":null,"enabled":%s}
				""".formatted(name, host, enabled);

		String response = mockMvc.perform(post("/api/servers").contentType(MediaType.APPLICATION_JSON).content(body))
				.andReturn()
				.getResponse()
				.getContentAsString();

		return objectMapper.readTree(response).get("id").asLong();
	}

	protected long createCheck(String name, String type, String target, Integer port, boolean enabled)
			throws Exception {
		String response = performCreateCheck(name, type, enabled, targetJson(target, port, true))
				.getContentAsString();
		return objectMapper.readTree(response).get("id").asLong();
	}

	protected long createCheckAndTargetId(String name, String type, String target, Integer port, boolean enabled)
			throws Exception {
		String response = performCreateCheck(name, type, enabled, targetJson(target, port, true))
				.getContentAsString();
		return objectMapper.readTree(response).get("targets").get(0).get("id").asLong();
	}

	protected String targetJson(String target, Integer port, boolean enabled) {
		return """
				{"target":"%s","port":%s,"enabled":%s}
				""".formatted(target, (port == null) ? "null" : port.toString(), enabled).trim();
	}

	protected org.springframework.mock.web.MockHttpServletResponse performCreateCheck(String name, String type,
			boolean enabled, String... targets) throws Exception {
		String body = """
				{"name":"%s","type":"%s","environment":"Production","description":null,
				 "enabled":%s,"targets":[%s]}
				""".formatted(name, type, enabled, String.join(",", targets));

		return mockMvc.perform(post("/api/checks").contentType(MediaType.APPLICATION_JSON).content(body))
				.andReturn()
				.getResponse();
	}

	// ---------------------------------------------------------------------
	// 알림 테스트용 helper
	// ---------------------------------------------------------------------

	protected long createDestination(String name, long chatId) throws Exception {
		return createDestination(name, chatId, null, true);
	}

	protected long createDestination(String name, long chatId, Integer messageThreadId, boolean enabled)
			throws Exception {
		String body = """
				{"name":"%s","chatId":%d,"messageThreadId":%s,"enabled":%s}
				""".formatted(name, chatId, (messageThreadId == null) ? "null" : messageThreadId.toString(), enabled);

		String response = mockMvc
				.perform(post("/api/telegram/destinations").contentType(MediaType.APPLICATION_JSON).content(body))
				.andReturn()
				.getResponse()
				.getContentAsString();

		return objectMapper.readTree(response).get("id").asLong();
	}

	/** critical만 받고 복구 알림도 받는 기본 설정으로 연결합니다. */
	protected void bindNotification(long checkId, long destinationId) throws Exception {
		bindNotification(checkId, destinationId, false, true, true, true);
	}

	protected void bindNotification(long checkId, long destinationId, boolean warningEnabled, boolean criticalEnabled,
			boolean sendResolved, boolean enabled) throws Exception {

		String body = """
				{"bindings":[{"telegramDestinationId":%d,"warningEnabled":%s,"criticalEnabled":%s,
				 "sendResolved":%s,"enabled":%s}]}
				""".formatted(destinationId, warningEnabled, criticalEnabled, sendResolved, enabled);

		// 연결이 저장되지 않으면 "보내지 않았다"는 검증이 아무것도 확인하지 못한 채 통과합니다.
		mockMvc.perform(put("/api/checks/" + checkId + "/notifications").contentType(MediaType.APPLICATION_JSON)
				.content(body)).andExpect(status().isOk());
	}

	/** 실제 Alertmanager는 더 많은 field를 보냅니다. Manager가 읽는 값만 넣어도 처리돼야 합니다. */
	protected String alertmanagerPayload(String status, Long checkId, Long checkTargetId, String severity,
			String fingerprint, String startsAt, String endsAt) {

		return alertmanagerPayloadOf(status,
				alertJson(status, checkId, checkTargetId, severity, fingerprint, startsAt, endsAt));
	}

	/** Alertmanager는 같은 group의 Alert 여러 건을 한 요청에 묶어 보냅니다. */
	protected String alertmanagerPayloadOf(String status, String... alerts) {
		return """
				{
				  "version": "4",
				  "groupKey": "{}:{alertname=\\"MonitoringCheckDown\\"}",
				  "status": "%s",
				  "receiver": "manager-webhook",
				  "externalURL": "http://alertmanager:9093",
				  "alerts": [%s]
				}
				""".formatted(status, String.join(",", alerts));
	}

	protected String alertJson(String status, Long checkId, Long checkTargetId, String severity, String fingerprint,
			String startsAt, String endsAt) {

		return """
				    {
				      "status": "%s",
				      "labels": {
				        "alertname": "MonitoringCheckDown",
				        "check_id": "%s",
				        "check_target_id": "%s",
				        "check_name": "전자결재 Health",
				        "check_type": "HTTP",
				        "environment": "Production",
				        "severity": "%s",
				        "instance": "http://10.0.0.11:8080/health",
				        "job": "manager-blackbox"
				      },
				      "annotations": {
				        "summary": "전자결재 Health 확인에 실패했습니다",
				        "description": "확인이 1분 넘게 실패하고 있습니다."
				      },
				      "startsAt": "%s",
				      "endsAt": "%s",
				      "fingerprint": "%s",
				      "generatorURL": "http://prometheus:9090/graph"
				    }
				""".formatted(status, (checkId == null) ? "" : checkId.toString(),
				(checkTargetId == null) ? "" : checkTargetId.toString(), severity, startsAt, endsAt, fingerprint);
	}

	protected org.springframework.test.web.servlet.ResultActions postAlertmanagerWebhook(String payload)
			throws Exception {
		return mockMvc.perform(post("/api/integrations/alertmanager/webhook")
				.header(org.springframework.http.HttpHeaders.AUTHORIZATION, "Bearer " + WEBHOOK_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload));
	}

	protected org.springframework.test.web.servlet.ResultActions postTelegramWebhook(String payload, String secret)
			throws Exception {
		var request = post("/api/integrations/telegram/webhook").contentType(MediaType.APPLICATION_JSON)
				.content(payload);
		if (secret != null) {
			request = request.header("X-Telegram-Bot-Api-Secret-Token", secret);
		}
		return mockMvc.perform(request);
	}

	protected JsonNode getJson(String path) throws Exception {
		String response = mockMvc.perform(get(path)).andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response);
	}

	/**
	 * flyway_schema_history.success는 MariaDB에서 TINYINT, PostgreSQL에서 BOOLEAN입니다.
	 * 비교 조건만 제품별로 다르게 넣습니다.
	 */
	protected abstract String successLiteral();

}
