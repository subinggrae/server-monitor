package kr.co.unidocs.monitoring;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Check와 Server의 도메인 경계가 유지되는지도 함께 확인합니다. */
abstract class AbstractMonitoringApiTest extends AbstractManagerDatabaseTest {

	// =====================================================================
	// 플랫폼 기본 동작
	// =====================================================================

	@Test
	void flywayMigrationsApplied() {
		Integer applied = jdbcTemplate.queryForObject(
				"select count(*) from flyway_schema_history where success = " + successLiteral(), Integer.class);

		// V1 baseline, V2 domain, V3 target 분리, V4 Server 분리, V5 알림이 모두 적용되어야 합니다.
		assertThat(applied).isGreaterThanOrEqualTo(5);
	}

	@Test
	void monitoringChecksTableHasNoServerColumn() {
		// V4에서 Server 의존을 끊었습니다. column이 남아 있으면 관계가 되살아납니다.
		Integer serverColumns = jdbcTemplate.queryForObject("""
				select count(*) from information_schema.columns
				where table_name = 'monitoring_checks' and column_name = 'server_id'
				""", Integer.class);

		assertThat(serverColumns).isZero();
	}

	@Test
	void healthEndpointReportsUp() throws Exception {
		mockMvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void prometheusEndpointExposesApplicationMetrics() throws Exception {
		mockMvc.perform(get("/actuator/prometheus"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("jvm_memory_used_bytes")))
				.andExpect(content().string(containsString("application=\"monitoring-manager\"")));
	}

	@Test
	void actuatorDoesNotExposeUnlistedEndpoints() throws Exception {
		mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
		mockMvc.perform(get("/actuator/beans")).andExpect(status().isNotFound());
	}

	@Test
	void restClientBuilderIsAvailableForPrometheusClient() {
		assertThat(applicationContext.getBeanProvider(RestClient.Builder.class).getIfAvailable())
				.as("Prometheus HTTP API 호출에 필요한 RestClient.Builder bean이 있어야 합니다.")
				.isNotNull();
	}

	@Test
	void systemInfoDoesNotExposeSecrets() throws Exception {
		mockMvc.perform(get("/api/system/info"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.secretKey").doesNotExist())
				.andExpect(jsonPath("$.botToken").doesNotExist());
	}

	// =====================================================================
	// Monitoring Check는 Server 없이 동작한다
	// =====================================================================

	@Test
	void createsCheckWithoutAnyServer() throws Exception {
		assertThat(serverTargetRepository.count()).isZero();

		MockHttpServletResponse response = performCreateCheck("전자결재 Health", "HTTP", true,
				targetJson("http://10.0.0.11:8080/actuator/health", null, true));

		assertThat(response.getStatus()).isEqualTo(201);
		JsonNode body = objectMapper.readTree(response.getContentAsString());
		assertThat(body.get("name").asText()).isEqualTo("전자결재 Health");
		assertThat(body.get("environment").asText()).isEqualTo("Production");
		// 응답에 Server 정보가 섞이면 안 됩니다.
		assertThat(body.has("serverId")).isFalse();
		assertThat(body.has("serverName")).isFalse();
	}

	@Test
	void rejectsCheckWithoutEnvironment() throws Exception {
		mockMvc.perform(post("/api/checks").contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"No Env","type":"ICMP","environment":"","description":null,"enabled":true,
				 "targets":[{"target":"10.0.0.11","port":null,"enabled":true}]}
				"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors.environment").exists());
	}

	@Test
	void serverApiNoLongerExposesChecks() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");

		// Check가 Server 소유가 아니므로 이 API는 제거했습니다.
		mockMvc.perform(get("/api/servers/" + serverId + "/checks")).andExpect(status().isNotFound());
	}

	// =====================================================================
	// Server와 Check의 독립성
	// =====================================================================

	@Test
	void deletingServerKeepsChecksAndTargets() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");
		performCreateCheck("전자결재 Health", "HTTP", true,
				targetJson("http://10.0.0.11:8080/health", null, true),
				targetJson("http://10.0.0.12:8080/health", null, true));

		mockMvc.perform(delete("/api/servers/" + serverId)).andExpect(status().isNoContent());

		// 서버를 지워도 Check와 Target은 그대로 남아야 합니다.
		assertThat(monitoringCheckRepository.count()).isEqualTo(1);
		assertThat(countCheckTargets()).isEqualTo(2);
		assertThat(getJson("/api/checks")).hasSize(1);
		assertThat(getJson("/api/prometheus/sd/blackbox")).hasSize(2);
	}

	@Test
	void serverStatusIsNotCalculatedFromBlackboxChecks() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");
		long targetId = createCheckAndTargetId("Ping", "ICMP", "10.0.0.11", null, true);
		// Check가 CRITICAL이어도 서버 상태는 영향을 받지 않아야 합니다.
		givenProbeResults(Map.of(targetId, 0.0d));

		mockMvc.perform(get("/api/servers/" + serverId))
				.andExpect(jsonPath("$.status").value("UNKNOWN"))
				// 서버 응답에 Check 개수가 남아 있으면 소유 관계가 있는 것처럼 보입니다.
				.andExpect(jsonPath("$.checkCount").doesNotExist());
	}

	@Test
	void disabledServerIsReportedAsDisabled() throws Exception {
		long serverId = createServer("WAS-OFF", "10.0.0.11", false);

		mockMvc.perform(get("/api/servers/" + serverId)).andExpect(jsonPath("$.status").value("DISABLED"));
	}

	// =====================================================================
	// Server CRUD
	// =====================================================================

	@Test
	void createsServer() throws Exception {
		mockMvc.perform(post("/api/servers").contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"PROD-WEB-01","host":"10.10.10.10","environment":"Production",
				 "description":"웹 서버","enabled":true}
				"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("PROD-WEB-01"))
				.andExpect(jsonPath("$.status").value("UNKNOWN"));
	}

	@Test
	void listsServersSortedByName() throws Exception {
		createServer("B-SERVER", "10.0.0.2");
		createServer("A-SERVER", "10.0.0.1");

		JsonNode servers = getJson("/api/servers");

		assertThat(servers).hasSize(2);
		assertThat(servers.get(0).get("name").asText()).isEqualTo("A-SERVER");
	}

	@Test
	void updatesServer() throws Exception {
		long serverId = createServer("OLD-NAME", "10.0.0.1");

		mockMvc.perform(put("/api/servers/" + serverId).contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"NEW-NAME","host":"10.0.0.2","environment":"Staging",
				 "description":null,"enabled":false}
				"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("NEW-NAME"))
				.andExpect(jsonPath("$.status").value("DISABLED"));
	}

	@Test
	void rejectsServerWithUrlAsHost() throws Exception {
		mockMvc.perform(post("/api/servers").contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"BAD-HOST","host":"http://10.0.0.1:8080/","environment":"Development",
				 "description":null,"enabled":true}
				"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors.host").exists());
	}

	@Test
	void rejectsDuplicateServerName() throws Exception {
		createServer("SAME-NAME", "10.0.0.1");

		mockMvc.perform(post("/api/servers").contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"SAME-NAME","host":"10.0.0.2","environment":"Development","description":null,"enabled":true}
				"""))
				.andExpect(status().isConflict());
	}

	@Test
	void returnsNotFoundForMissingServer() throws Exception {
		mockMvc.perform(get("/api/servers/999999")).andExpect(status().isNotFound());
	}

	// =====================================================================
	// Check CRUD와 Target
	// =====================================================================

	@Test
	void createsIcmpCheckWithSingleTarget() throws Exception {
		MockHttpServletResponse response = performCreateCheck("Ping", "ICMP", true,
				targetJson("10.10.10.15", null, true));

		assertThat(response.getStatus()).isEqualTo(201);
		JsonNode body = objectMapper.readTree(response.getContentAsString());
		assertThat(body.get("typeDisplayName").asText()).isEqualTo("Ping");
		assertThat(body.get("targetCount").asInt()).isEqualTo(1);
		assertThat(body.get("targets").get(0).get("blackboxTarget").asText()).isEqualTo("10.10.10.15");
	}

	@Test
	void createsTcpCheckAndCombinesHostAndPortPerTarget() throws Exception {
		MockHttpServletResponse response = performCreateCheck("MariaDB Port", "TCP", true,
				targetJson("10.10.10.15", 3306, true));

		JsonNode target = objectMapper.readTree(response.getContentAsString()).get("targets").get(0);
		assertThat(target.get("blackboxTarget").asText()).isEqualTo("10.10.10.15:3306");
	}

	@Test
	void createsCheckWithMultipleTargets() throws Exception {
		MockHttpServletResponse response = performCreateCheck("전자결재 Health", "HTTP", true,
				targetJson("http://10.0.0.11:8080/actuator/health", null, true),
				targetJson("http://10.0.0.12:8080/actuator/health", null, true),
				targetJson("http://10.0.0.13:8080/actuator/health", null, true));

		assertThat(response.getStatus()).isEqualTo(201);
		JsonNode body = objectMapper.readTree(response.getContentAsString());
		assertThat(body.get("targetCount").asInt()).isEqualTo(3);
		assertThat(body.get("targets").get(0).get("id").asLong())
				.isNotEqualTo(body.get("targets").get(1).get("id").asLong());
	}

	@Test
	void createsTcpCheckWithDifferentPortPerTarget() throws Exception {
		MockHttpServletResponse response = performCreateCheck("DB Port", "TCP", true,
				targetJson("10.0.0.11", 3306, true),
				targetJson("10.0.0.12", 3307, true));

		JsonNode targets = objectMapper.readTree(response.getContentAsString()).get("targets");
		assertThat(targets.get(0).get("blackboxTarget").asText()).isEqualTo("10.0.0.11:3306");
		assertThat(targets.get(1).get("blackboxTarget").asText()).isEqualTo("10.0.0.12:3307");
	}

	@Test
	void addsTargetWhileKeepingExistingTargetIds() throws Exception {
		long checkId = createCheck("Health", "HTTP", "http://10.0.0.11:8080/health", null, true);
		long existingTargetId = getJson("/api/checks/" + checkId).get("targets").get(0).get("id").asLong();

		String body = """
				{"name":"Health","type":"HTTP","environment":"Production","description":null,"enabled":true,
				 "targets":[
				  {"id":%d,"target":"http://10.0.0.11:8080/health","port":null,"enabled":true},
				  {"target":"http://10.0.0.12:8080/health","port":null,"enabled":true}
				]}
				""".formatted(existingTargetId);

		mockMvc.perform(put("/api/checks/" + checkId).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.targetCount").value(2))
				// 기존 Target의 id가 바뀌면 Prometheus time series가 끊깁니다.
				.andExpect(jsonPath("$.targets[0].id").value(existingTargetId));
	}

	@Test
	void removesTargetThatIsNotInTheRequest() throws Exception {
		MockHttpServletResponse created = performCreateCheck("Health", "HTTP", true,
				targetJson("http://10.0.0.11:8080/health", null, true),
				targetJson("http://10.0.0.12:8080/health", null, true));

		JsonNode body = objectMapper.readTree(created.getContentAsString());
		long checkId = body.get("id").asLong();
		long keptTargetId = body.get("targets").get(0).get("id").asLong();

		String request = """
				{"name":"Health","type":"HTTP","environment":"Production","description":null,"enabled":true,
				 "targets":[{"id":%d,"target":"http://10.0.0.11:8080/health","port":null,"enabled":true}]}
				""".formatted(keptTargetId);

		mockMvc.perform(put("/api/checks/" + checkId).contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.targetCount").value(1));

		assertThat(countCheckTargets()).isEqualTo(1);
	}

	@Test
	void disablesSingleTargetWithoutTouchingOthers() throws Exception {
		MockHttpServletResponse created = performCreateCheck("Health", "HTTP", true,
				targetJson("http://10.0.0.11:8080/health", null, true),
				targetJson("http://10.0.0.12:8080/health", null, true));

		JsonNode body = objectMapper.readTree(created.getContentAsString());
		long checkId = body.get("id").asLong();
		long firstId = body.get("targets").get(0).get("id").asLong();
		long secondId = body.get("targets").get(1).get("id").asLong();

		String request = """
				{"name":"Health","type":"HTTP","environment":"Production","description":null,"enabled":true,
				 "targets":[
				  {"id":%d,"target":"http://10.0.0.11:8080/health","port":null,"enabled":true},
				  {"id":%d,"target":"http://10.0.0.12:8080/health","port":null,"enabled":false}
				]}
				""".formatted(firstId, secondId);

		mockMvc.perform(put("/api/checks/" + checkId).contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.targets[1].enabled").value(false))
				.andExpect(jsonPath("$.targets[1].status").value("DISABLED"));
	}

	@Test
	void deletesCheckTogetherWithItsTargets() throws Exception {
		MockHttpServletResponse created = performCreateCheck("Health", "HTTP", true,
				targetJson("http://10.0.0.11:8080/health", null, true),
				targetJson("http://10.0.0.12:8080/health", null, true));
		long checkId = objectMapper.readTree(created.getContentAsString()).get("id").asLong();

		mockMvc.perform(delete("/api/checks/" + checkId)).andExpect(status().isNoContent());

		mockMvc.perform(get("/api/checks/" + checkId)).andExpect(status().isNotFound());
		assertThat(countCheckTargets()).isZero();
	}

	// =====================================================================
	// Check 검증
	// =====================================================================

	@Test
	void rejectsCheckWithoutAnyTarget() throws Exception {
		MockHttpServletResponse response = performCreateCheck("Empty", "ICMP", true);

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(response.getContentAsString()).contains("하나 이상");
	}

	@Test
	void rejectsDuplicateTargetsInTheSameCheck() throws Exception {
		MockHttpServletResponse response = performCreateCheck("Dup", "ICMP", true,
				targetJson("10.10.10.15", null, true),
				targetJson("10.10.10.15", null, true));

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(response.getContentAsString()).contains("두 번");
	}

	@Test
	void rejectsTcpTargetWithoutPort() throws Exception {
		MockHttpServletResponse response = performCreateCheck("No Port", "TCP", true,
				targetJson("10.10.10.15", null, true));

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(response.getContentAsString()).contains("Port가 필요합니다");
	}

	@Test
	void rejectsTargetWithPortOutOfRange() throws Exception {
		MockHttpServletResponse response = performCreateCheck("Bad Port", "TCP", true,
				targetJson("10.10.10.15", 70000, true));

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(response.getContentAsString()).contains("65535");
	}

	@Test
	void rejectsHttpsCheckWithHttpUrl() throws Exception {
		MockHttpServletResponse response = performCreateCheck("Wrong Scheme", "HTTPS", true,
				targetJson("http://service.example.com/health", null, true));

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(response.getContentAsString()).contains("https://");
	}

	@Test
	void rejectsNonHttpScheme() throws Exception {
		MockHttpServletResponse response = performCreateCheck("File", "HTTP", true,
				targetJson("file:///etc/passwd", null, true));

		assertThat(response.getStatus()).isEqualTo(400);
	}

	@Test
	void rejectsIcmpTargetWithUrl() throws Exception {
		MockHttpServletResponse response = performCreateCheck("Bad Ping", "ICMP", true,
				targetJson("http://10.10.10.15/health", null, true));

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(response.getContentAsString()).contains("IP 또는 hostname");
	}

	@Test
	void rejectsTargetIdThatBelongsToAnotherCheck() throws Exception {
		long firstCheckId = createCheck("First", "ICMP", "10.0.0.11", null, true);
		long otherTargetId = createCheckAndTargetId("Second", "ICMP", "10.0.0.12", null, true);

		String request = """
				{"name":"First","type":"ICMP","environment":"Production","description":null,"enabled":true,
				 "targets":[{"id":%d,"target":"10.0.0.11","port":null,"enabled":true}]}
				""".formatted(otherTargetId);

		mockMvc.perform(put("/api/checks/" + firstCheckId).contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value(containsString("속하지 않는")));
	}

	@Test
	void returnsNotFoundForMissingCheck() throws Exception {
		mockMvc.perform(get("/api/checks/999999")).andExpect(status().isNotFound());
	}

	@Test
	void exposesCheckTypeCapabilities() throws Exception {
		JsonNode types = getJson("/api/check-types");

		assertThat(types).hasSize(4);
		JsonNode tcp = null;
		for (JsonNode type : types) {
			if ("TCP".equals(type.get("type").asText())) {
				tcp = type;
			}
		}
		assertThat(tcp).isNotNull();
		assertThat(tcp.get("requiresPort").asBoolean()).isTrue();
	}

	// =====================================================================
	// Prometheus HTTP Service Discovery
	// =====================================================================

	@Test
	void serviceDiscoveryWorksWithoutAnyServer() throws Exception {
		performCreateCheck("전자결재 Health", "HTTP", true,
				targetJson("http://10.0.0.11:8080/health", null, true),
				targetJson("http://10.0.0.12:8080/health", null, true));

		assertThat(serverTargetRepository.count()).isZero();
		assertThat(getJson("/api/prometheus/sd/blackbox")).hasSize(2);
	}

	@Test
	void serviceDiscoveryReturnsOneEntryPerTarget() throws Exception {
		performCreateCheck("전자결재 Health", "HTTP", true,
				targetJson("http://10.0.0.11:8080/health", null, true),
				targetJson("http://10.0.0.12:8080/health", null, true),
				targetJson("http://10.0.0.13:8080/health", null, true));

		JsonNode entries = getJson("/api/prometheus/sd/blackbox");

		assertThat(entries).hasSize(3);
		for (JsonNode entry : entries) {
			assertThat(entry.get("targets")).hasSize(1);
		}
		// 같은 Check에 속한다는 것은 check_id로 알 수 있습니다.
		assertThat(entries.get(0).get("labels").get("check_id").asText())
				.isEqualTo(entries.get(2).get("labels").get("check_id").asText());
		// Target 식별자는 서로 달라야 상태를 구분할 수 있습니다.
		assertThat(entries.get(0).get("labels").get("check_target_id").asText())
				.isNotEqualTo(entries.get(1).get("labels").get("check_target_id").asText());
	}

	@Test
	void serviceDiscoveryUsesCheckCentricLabels() throws Exception {
		long checkId = createCheck("전자결재 Health", "ICMP", "10.0.0.11", null, true);

		JsonNode labels = getJson("/api/prometheus/sd/blackbox").get(0).get("labels");

		// Prometheus는 label 값이 문자열이 아니면 SD 응답 전체를 거부합니다.
		labels.properties().forEach(field -> assertThat(field.getValue().isTextual())
				.as("label %s는 문자열이어야 합니다", field.getKey()).isTrue());

		assertThat(labels.get("check_id").asText()).isEqualTo(String.valueOf(checkId));
		assertThat(labels.get("check_name").asText()).isEqualTo("전자결재 Health");
		assertThat(labels.get("environment").asText()).isEqualTo("Production");
		// Server에 의존하던 label은 더 이상 넣지 않습니다.
		assertThat(labels.has("server_id")).isFalse();
		assertThat(labels.has("server_name")).isFalse();

		// 알림 대상은 Manager Database가 정합니다. label로 정하지 않습니다.
		assertThat(labels.has("service")).isFalse();
		assertThat(labels.has("notification_group")).isFalse();
	}

	@Test
	void serviceDiscoveryMapsEachTypeToItsBlackboxModule() throws Exception {
		createCheck("Ping", "ICMP", "10.10.10.15", null, true);
		createCheck("SSH", "TCP", "10.10.10.15", 22, true);
		createCheck("Health", "HTTP", "http://10.10.10.15:8080/health", null, true);
		createCheck("Public", "HTTPS", "https://service.example.com/health", null, true);

		JsonNode entries = getJson("/api/prometheus/sd/blackbox");

		assertThat(entries).hasSize(4);
		assertThat(moduleOfType(entries, "ICMP")).isEqualTo("icmp");
		assertThat(moduleOfType(entries, "TCP")).isEqualTo("tcp_connect");
		assertThat(moduleOfType(entries, "HTTP")).isEqualTo("http_2xx");
		assertThat(moduleOfType(entries, "HTTPS")).isEqualTo("http_2xx");
		assertThat(targetOfType(entries, "TCP")).isEqualTo("10.10.10.15:22");
	}

	@Test
	void serviceDiscoveryExcludesDisabledTarget() throws Exception {
		performCreateCheck("Health", "HTTP", true,
				targetJson("http://10.0.0.11:8080/health", null, true),
				targetJson("http://10.0.0.12:8080/health", null, false));

		JsonNode entries = getJson("/api/prometheus/sd/blackbox");

		assertThat(entries).hasSize(1);
		assertThat(entries.get(0).get("targets").get(0).asText()).isEqualTo("http://10.0.0.11:8080/health");
	}

	@Test
	void serviceDiscoveryExcludesAllTargetsOfDisabledCheck() throws Exception {
		performCreateCheck("Health", "HTTP", false,
				targetJson("http://10.0.0.11:8080/health", null, true),
				targetJson("http://10.0.0.12:8080/health", null, true));

		assertThat(getJson("/api/prometheus/sd/blackbox")).isEmpty();
	}

	@Test
	void serviceDiscoveryExcludesDeletedCheck() throws Exception {
		long checkId = createCheck("Ping", "ICMP", "10.10.10.15", null, true);

		assertThat(getJson("/api/prometheus/sd/blackbox")).hasSize(1);
		mockMvc.perform(delete("/api/checks/" + checkId)).andExpect(status().isNoContent());
		assertThat(getJson("/api/prometheus/sd/blackbox")).isEmpty();
	}

	// =====================================================================
	// Target 상태
	// =====================================================================

	@Test
	void targetStatusIsOkWhenProbeSucceeded() throws Exception {
		long checkId = createCheck("Ping", "ICMP", "10.10.10.15", null, true);
		long targetId = getJson("/api/checks/" + checkId).get("targets").get(0).get("id").asLong();
		givenProbeResults(Map.of(targetId, 1.0d));

		mockMvc.perform(get("/api/checks/" + checkId + "/status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("OK"))
				.andExpect(jsonPath("$.targets[0].status").value("OK"))
				.andExpect(jsonPath("$.targets[0].checkedAt").exists());
	}

	@Test
	void targetStatusIsCriticalWhenProbeFailed() throws Exception {
		long checkId = createCheck("Ping", "ICMP", "10.10.10.15", null, true);
		long targetId = getJson("/api/checks/" + checkId).get("targets").get(0).get("id").asLong();
		givenProbeResults(Map.of(targetId, 0.0d));

		mockMvc.perform(get("/api/checks/" + checkId + "/status"))
				.andExpect(jsonPath("$.status").value("CRITICAL"))
				.andExpect(jsonPath("$.targets[0].status").value("CRITICAL"));
	}

	@Test
	void targetStatusIsUnknownWhenNoMetricCollectedYet() throws Exception {
		long checkId = createCheck("Ping", "ICMP", "10.10.10.15", null, true);
		givenNoCollectedMetrics();

		mockMvc.perform(get("/api/checks/" + checkId + "/status"))
				.andExpect(jsonPath("$.status").value("UNKNOWN"))
				.andExpect(jsonPath("$.message").value(containsString("아직 수집된 Metric이 없습니다")));
	}

	@Test
	void targetStatusIsUnknownWhenPrometheusIsUnavailable() throws Exception {
		long checkId = createCheck("Ping", "ICMP", "10.10.10.15", null, true);
		givenPrometheusUnavailable();

		mockMvc.perform(get("/api/checks/" + checkId + "/status"))
				.andExpect(jsonPath("$.status").value("UNKNOWN"))
				.andExpect(jsonPath("$.message").value(containsString("Prometheus에 연결할 수 없어")));
	}

	// =====================================================================
	// Check 상태 집계 (Target → Check)
	// =====================================================================

	@Test
	void checkStatusIsOkWhenAllTargetsAreOk() throws Exception {
		long[] ids = createCheckWithThreeTargets();
		givenProbeResults(Map.of(ids[1], 1.0d, ids[2], 1.0d, ids[3], 1.0d));

		mockMvc.perform(get("/api/checks/" + ids[0] + "/status")).andExpect(jsonPath("$.status").value("OK"));
	}

	@Test
	void checkStatusIsCriticalWhenOneTargetFails() throws Exception {
		long[] ids = createCheckWithThreeTargets();
		givenProbeResults(Map.of(ids[1], 1.0d, ids[2], 1.0d, ids[3], 0.0d));

		mockMvc.perform(get("/api/checks/" + ids[0] + "/status"))
				.andExpect(jsonPath("$.status").value("CRITICAL"))
				.andExpect(jsonPath("$.targets[0].status").value("OK"))
				.andExpect(jsonPath("$.targets[2].status").value("CRITICAL"));
	}

	@Test
	void checkStatusIsUnknownWhenOkAndUnknownAreMixed() throws Exception {
		long[] ids = createCheckWithThreeTargets();
		givenProbeResults(Map.of(ids[1], 1.0d, ids[2], 1.0d));

		mockMvc.perform(get("/api/checks/" + ids[0] + "/status")).andExpect(jsonPath("$.status").value("UNKNOWN"));
	}

	@Test
	void checkStatusIsUnknownWithoutAnyActiveTarget() throws Exception {
		MockHttpServletResponse created = performCreateCheck("Health", "HTTP", true,
				targetJson("http://10.0.0.11:8080/health", null, false));
		long checkId = objectMapper.readTree(created.getContentAsString()).get("id").asLong();

		mockMvc.perform(get("/api/checks/" + checkId + "/status")).andExpect(jsonPath("$.status").value("UNKNOWN"));
	}

	@Test
	void checkStatusIsDisabledWhenCheckIsTurnedOff() throws Exception {
		MockHttpServletResponse created = performCreateCheck("Ping", "ICMP", false,
				targetJson("10.10.10.15", null, true));
		JsonNode body = objectMapper.readTree(created.getContentAsString());
		long checkId = body.get("id").asLong();
		long targetId = body.get("targets").get(0).get("id").asLong();

		givenProbeResults(Map.of(targetId, 1.0d));

		mockMvc.perform(get("/api/checks/" + checkId + "/status")).andExpect(jsonPath("$.status").value("DISABLED"));
	}

	@Test
	void checkListStillWorksWhenPrometheusIsUnavailable() throws Exception {
		createCheck("Ping", "ICMP", "10.10.10.15", null, true);
		givenPrometheusUnavailable();

		mockMvc.perform(get("/api/checks"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].name").value("Ping"))
				.andExpect(jsonPath("$[0].status").value("UNKNOWN"));

		assertThat(getJson("/api/prometheus/sd/blackbox")).hasSize(1);
	}

	// =====================================================================
	// helper
	// =====================================================================

	/** Target 3개짜리 HTTP Check를 만듭니다. {checkId, target1, target2, target3} 순으로 돌려줍니다. */
	private long[] createCheckWithThreeTargets() throws Exception {
		MockHttpServletResponse created = performCreateCheck("전자결재 Health", "HTTP", true,
				targetJson("http://10.0.0.11:8080/health", null, true),
				targetJson("http://10.0.0.12:8080/health", null, true),
				targetJson("http://10.0.0.13:8080/health", null, true));

		JsonNode body = objectMapper.readTree(created.getContentAsString());
		return new long[] { body.get("id").asLong(), body.get("targets").get(0).get("id").asLong(),
				body.get("targets").get(1).get("id").asLong(), body.get("targets").get(2).get("id").asLong() };
	}

	private long countCheckTargets() {
		Long count = jdbcTemplate.queryForObject("select count(*) from monitoring_check_targets", Long.class);
		return (count == null) ? 0L : count;
	}

	private String moduleOfType(JsonNode entries, String checkType) {
		for (JsonNode entry : entries) {
			if (checkType.equals(entry.get("labels").get("check_type").asText())) {
				return entry.get("labels").get("module").asText();
			}
		}
		throw new AssertionError(checkType + " Target을 찾지 못했습니다.");
	}

	private String targetOfType(JsonNode entries, String checkType) {
		for (JsonNode entry : entries) {
			if (checkType.equals(entry.get("labels").get("check_type").asText())) {
				return entry.get("targets").get(0).asText();
			}
		}
		throw new AssertionError(checkType + " Target을 찾지 못했습니다.");
	}

}
