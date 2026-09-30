package kr.co.unidocs.monitoring;

import tools.jackson.databind.JsonNode;
import kr.co.unidocs.monitoring.prometheus.PrometheusQueryResult;
import kr.co.unidocs.monitoring.resource.MonitoredResource;
import kr.co.unidocs.monitoring.snmp.ServerSnmpConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SNMP 설정, Resource Discovery, 선택 저장, Service Discovery를 두 DB 제품에서 같이 검증합니다.
 *
 * 실제 장비와 snmp_exporter는 Mock입니다. 그래야 테스트가 외부 상태에 흔들리지 않습니다.
 */
abstract class AbstractSnmpApiTest extends AbstractNotificationApiTest {

	private static final String COMMUNITY = "test-community-value";

	// ---------------------------------------------------------------------
	// SNMP 설정
	// ---------------------------------------------------------------------

	@Test
	void savesSnmpConfigurationWithoutReturningCommunity() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");

		mockMvc.perform(put("/api/servers/" + serverId + "/snmp").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"version":"V2C","port":161,"community":"%s","enabled":true}
						""".formatted(COMMUNITY)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.configured").value(true))
				.andExpect(jsonPath("$.communityConfigured").value(true))
				.andExpect(jsonPath("$.port").value(161))
				.andExpect(jsonPath("$.community").doesNotExist());

		String response = getJson("/api/servers/" + serverId + "/snmp").toString();
		assertThat(response).doesNotContain(COMMUNITY);
	}

	@Test
	void storesCommunityEncryptedInDatabase() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");
		configureSnmp(serverId, COMMUNITY);

		String cipher = jdbcTemplate.queryForObject(
				"select community_cipher from server_snmp_configs where server_id = ?", String.class, serverId);

		assertThat(cipher).isNotNull().startsWith("v1:").doesNotContain(COMMUNITY);
	}

	@Test
	void keepsExistingCommunityWhenRequestOmitsIt() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");
		configureSnmp(serverId, COMMUNITY);
		String before = communityCipherOf(serverId);

		mockMvc.perform(put("/api/servers/" + serverId + "/snmp").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"version":"V2C","port":1610,"community":null,"enabled":true}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.port").value(1610))
				.andExpect(jsonPath("$.communityConfigured").value(true));

		assertThat(communityCipherOf(serverId)).isEqualTo(before);
	}

	@Test
	void rejectsFirstConfigurationWithoutCommunity() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");

		mockMvc.perform(put("/api/servers/" + serverId + "/snmp").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"version":"V2C","port":161,"community":null,"enabled":true}
						"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	void changesCommunityWhenNewValueGiven() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");
		configureSnmp(serverId, COMMUNITY);
		String before = communityCipherOf(serverId);

		configureSnmp(serverId, "another-community-value");

		assertThat(communityCipherOf(serverId)).isNotEqualTo(before);
		assertThat(authConfigContent()).contains("another-community-value").doesNotContain(COMMUNITY);
	}

	@Test
	void removesSnmpConfigurationWithoutDeletingServer() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");
		configureSnmp(serverId, COMMUNITY);

		mockMvc.perform(delete("/api/servers/" + serverId + "/snmp")).andExpect(status().isNoContent());

		assertThat(serverSnmpConfigRepository.findByServerId(serverId)).isEmpty();
		assertThat(serverTargetRepository.findById(serverId)).isPresent();
		assertThat(authConfigContent()).doesNotContain(COMMUNITY);
	}

	/** 서버를 지우면 exporter의 auth config에서도 빠져야 합니다. 남으면 지운 서버의 community가 계속 남습니다. */
	@Test
	void removesSnmpConfigurationWhenServerIsDeleted() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");
		configureSnmp(serverId, COMMUNITY);
		selectResources(serverId, resourceJson("CPU", "cpu", null));

		mockMvc.perform(delete("/api/servers/" + serverId)).andExpect(status().isNoContent());

		assertThat(serverSnmpConfigRepository.findByServerId(serverId)).isEmpty();
		assertThat(monitoredResourceRepository.findByServerIdOrderByResourceTypeAscResourceKeyAsc(serverId)).isEmpty();
		assertThat(getJson("/api/prometheus/sd/snmp")).isEmpty();
		assertThat(authConfigContent()).doesNotContain("manager_server_" + serverId).doesNotContain(COMMUNITY);
	}

	// ---------------------------------------------------------------------
	// Exporter auth config
	// ---------------------------------------------------------------------

	@Test
	void writesAuthEntryPerServer() throws Exception {
		long first = createServer("WAS-01", "10.0.0.11");
		long second = createServer("WAS-02", "10.0.0.12");
		configureSnmp(first, COMMUNITY);
		configureSnmp(second, "second-community-value");

		String content = authConfigContent();
		assertThat(content).contains("manager_server_" + first).contains("manager_server_" + second);
		assertThat(content).contains("version: 2");
	}

	@Test
	void dropsAuthEntryWhenSnmpIsDisabled() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");
		configureSnmp(serverId, COMMUNITY);

		mockMvc.perform(put("/api/servers/" + serverId + "/snmp").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"version":"V2C","port":161,"community":null,"enabled":false}
						"""))
				.andExpect(status().isOk());

		assertThat(authConfigContent()).doesNotContain("manager_server_" + serverId);
	}

	/** DB 저장은 성공했는데 exporter 반영이 실패한 상태를 숨기지 않아야 합니다. */
	@Test
	void reportsExporterSyncFailureWithoutLosingSavedConfiguration() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");
		willThrow(new IllegalStateException("reload 실패")).given(snmpExporterReloader).reload();

		mockMvc.perform(put("/api/servers/" + serverId + "/snmp").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"version":"V2C","port":161,"community":"%s","enabled":true}
						""".formatted(COMMUNITY)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.configured").value(true))
				.andExpect(jsonPath("$.exporterSynced").value(false))
				.andExpect(jsonPath("$.exporterMessage").isNotEmpty());

		assertThat(serverSnmpConfigRepository.findByServerId(serverId)).isPresent();
	}

	@Test
	void neverWritesCommunityIntoServerApiResponses() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");
		configureSnmp(serverId, COMMUNITY);

		assertThat(getJson("/api/servers").toString()).doesNotContain(COMMUNITY);
		assertThat(getJson("/api/servers/" + serverId).toString()).doesNotContain(COMMUNITY);
		assertThat(getJson("/api/prometheus/sd/snmp").toString()).doesNotContain(COMMUNITY);
	}

	// ---------------------------------------------------------------------
	// SNMP 연결 테스트
	// ---------------------------------------------------------------------

	@Test
	void reportsSuccessfulSnmpTest() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");
		configureSnmp(serverId, COMMUNITY);
		givenSnmpSystemInfo("was-01");

		mockMvc.perform(post("/api/servers/" + serverId + "/snmp/test"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.systemName").value("was-01"));
	}

	@Test
	void reportsFailedSnmpTestWithoutLeakingDetails() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");
		configureSnmp(serverId, COMMUNITY);
		givenSnmpUnreachable();

		String response = mockMvc.perform(post("/api/servers/" + serverId + "/snmp/test"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(false))
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertThat(response).doesNotContain(COMMUNITY).doesNotContain("Exception");
	}

	@Test
	void refusesSnmpTestWhenNotConfigured() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");

		mockMvc.perform(post("/api/servers/" + serverId + "/snmp/test"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(false));
	}

	// ---------------------------------------------------------------------
	// Resource Discovery
	// ---------------------------------------------------------------------

	@Test
	void discoversCpuMemoryAndStorage() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");
		configureSnmp(serverId, COMMUNITY);
		givenSnmpWalk(4, physicalMemory(1, 8L * 1024 * 1024 * 1024, 4L * 1024 * 1024 * 1024), swapSpace(3),
				fixedDisk(33, "/", 100L * 1024 * 1024 * 1024, 42L * 1024 * 1024 * 1024),
				fixedDisk(35, "/data", 500L * 1024 * 1024 * 1024, 320L * 1024 * 1024 * 1024));

		JsonNode result = postJson("/api/servers/" + serverId + "/resources/discover");

		assertThat(result.get("success").asBoolean()).isTrue();
		assertThat(typesOf(result)).containsExactlyInAnyOrder("CPU", "MEMORY", "STORAGE", "STORAGE");
		assertThat(keysOf(result)).contains("/", "/data").doesNotContain("Swap space");
	}

	/** hrStorageTable 첫 행을 memory로 보면 안 됩니다. hrStorageType으로 갈라야 합니다. */
	@Test
	void doesNotTreatSwapOrBuffersAsMemory() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");
		configureSnmp(serverId, COMMUNITY);
		givenSnmpWalk(2, swapSpace(1), physicalMemory(6, 4L * 1024 * 1024 * 1024, 1L * 1024 * 1024 * 1024));

		JsonNode result = postJson("/api/servers/" + serverId + "/resources/discover");

		List<String> memoryIndexes = result.get("resources")
				.valueStream()
				.filter(node -> "MEMORY".equals(node.get("resourceType").asString()))
				.map(node -> node.get("snmpIndex").asString())
				.toList();
		assertThat(memoryIndexes).containsExactly("6");
	}

	@Test
	void reportsNoCpuWhenDeviceDoesNotExposeProcessorTable() throws Exception {
		long serverId = createServer("NAS-01", "10.0.0.20");
		configureSnmp(serverId, COMMUNITY);
		givenSnmpWalk(0, fixedDisk(33, "/volume1", 1000L * 1024 * 1024 * 1024, 100L * 1024 * 1024 * 1024));

		JsonNode result = postJson("/api/servers/" + serverId + "/resources/discover");

		assertThat(typesOf(result)).doesNotContain("CPU").contains("STORAGE");
	}

	@Test
	void reportsEmptyDiscoveryWhenDeviceHasNoSupportedMib() throws Exception {
		long serverId = createServer("SW-01", "10.0.0.30");
		configureSnmp(serverId, COMMUNITY);
		givenSnmpWalk(0);

		JsonNode result = postJson("/api/servers/" + serverId + "/resources/discover");

		assertThat(result.get("success").asBoolean()).isTrue();
		assertThat(result.get("resources")).isEmpty();
	}

	@Test
	void reportsDiscoveryFailureWhenDeviceDoesNotRespond() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");
		configureSnmp(serverId, COMMUNITY);
		givenSnmpUnreachable();

		JsonNode result = postJson("/api/servers/" + serverId + "/resources/discover");

		assertThat(result.get("success").asBoolean()).isFalse();
		assertThat(result.toString()).doesNotContain(COMMUNITY);
	}

	@Test
	void refusesDiscoveryWhenSnmpIsNotConfigured() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");

		JsonNode result = postJson("/api/servers/" + serverId + "/resources/discover");

		assertThat(result.get("success").asBoolean()).isFalse();
	}

	@Test
	void marksAlreadySelectedResourcesInDiscoveryResult() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");
		configureSnmp(serverId, COMMUNITY);
		givenSnmpWalk(2, fixedDisk(33, "/", 100L, 42L), fixedDisk(35, "/data", 500L, 320L));
		selectResources(serverId, resourceJson("STORAGE", "/data", 35));

		JsonNode result = postJson("/api/servers/" + serverId + "/resources/discover");

		Map<String, Boolean> selection = result.get("resources")
				.valueStream()
				.collect(java.util.stream.Collectors.toMap(node -> node.get("resourceKey").asString(),
						node -> node.get("selected").asBoolean()));
		assertThat(selection).containsEntry("/data", true).containsEntry("/", false);
	}

	// ---------------------------------------------------------------------
	// Resource 선택
	// ---------------------------------------------------------------------

	@Test
	void savesOnlySelectedStorages() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");
		configureSnmp(serverId, COMMUNITY);

		selectResources(serverId, String.join(",", resourceJson("CPU", "cpu", null),
				resourceJson("MEMORY", "memory", 1), resourceJson("STORAGE", "/", 33)));

		List<MonitoredResource> saved = monitoredResourceRepository
				.findByServerIdOrderByResourceTypeAscResourceKeyAsc(serverId);
		assertThat(saved).extracting(MonitoredResource::getResourceKey).containsExactlyInAnyOrder("cpu", "memory", "/");
	}

	@Test
	void removesResourcesLeftOutOfNewSelection() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");
		configureSnmp(serverId, COMMUNITY);
		selectResources(serverId,
				String.join(",", resourceJson("STORAGE", "/", 33), resourceJson("STORAGE", "/data", 35)));

		selectResources(serverId, resourceJson("STORAGE", "/", 33));

		assertThat(monitoredResourceRepository.findByServerIdOrderByResourceTypeAscResourceKeyAsc(serverId))
				.extracting(MonitoredResource::getResourceKey)
				.containsExactly("/");
	}

	/** 다시 검색했다고 이미 고른 Resource가 사라지면 안 됩니다. */
	@Test
	void keepsSelectionWhenDiscoveryRunsAgain() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");
		configureSnmp(serverId, COMMUNITY);
		selectResources(serverId, resourceJson("STORAGE", "/data", 35));
		givenSnmpWalk(2, fixedDisk(33, "/", 100L, 42L));

		postJson("/api/servers/" + serverId + "/resources/discover");

		assertThat(monitoredResourceRepository.findByServerIdOrderByResourceTypeAscResourceKeyAsc(serverId))
				.extracting(MonitoredResource::getResourceKey)
				.containsExactly("/data");
	}

	/** 장비에서 사라진 Resource는 지우지 않고 값이 없는 상태로 둡니다. */
	@Test
	void marksVanishedResourceAsUnavailable() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");
		configureSnmp(serverId, COMMUNITY);
		selectResources(serverId, resourceJson("STORAGE", "/gone", 99));
		givenSnmpMetricQueries(Map.of("up{", List.of(snmpSample(serverId, 1))));

		JsonNode resources = getJson("/api/servers/" + serverId + "/resources");

		assertThat(resources.get(0).get("status").asString()).isEqualTo("UNAVAILABLE");
		assertThat(resources.get(0).get("usagePercent").isNull()).isTrue();
	}

	// ---------------------------------------------------------------------
	// Resource 현재 값
	// ---------------------------------------------------------------------

	@Test
	void calculatesUsageFromCollectedMetrics() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");
		configureSnmp(serverId, COMMUNITY);
		selectResources(serverId, String.join(",", resourceJson("CPU", "cpu", null),
				resourceJson("MEMORY", "memory", 1), resourceJson("STORAGE", "/", 33)));

		givenSnmpMetricQueries(Map.of(
				"up{", List.of(snmpSample(serverId, 1)),
				"hrProcessorLoad", List.of(snmpSample(serverId, 32)),
				"hrStorageSize", List.of(
						snmpSample(serverId, 8_388_608, "hrStorageIndex", "1", "hrStorageDescr", "Physical memory",
								"hrStorageType", "1.3.6.1.2.1.25.2.1.2"),
						snmpSample(serverId, 26_214_400, "hrStorageIndex", "33", "hrStorageDescr", "/",
								"hrStorageType", "1.3.6.1.2.1.25.2.1.4")),
				"hrStorageUsed", List.of(
						snmpSample(serverId, 4_194_304, "hrStorageIndex", "1", "hrStorageDescr", "Physical memory",
								"hrStorageType", "1.3.6.1.2.1.25.2.1.2"),
						snmpSample(serverId, 11_010_048, "hrStorageIndex", "33", "hrStorageDescr", "/",
								"hrStorageType", "1.3.6.1.2.1.25.2.1.4")),
				"hrStorageAllocationUnits", List.of(
						snmpSample(serverId, 1024, "hrStorageIndex", "1", "hrStorageDescr", "Physical memory",
								"hrStorageType", "1.3.6.1.2.1.25.2.1.2"),
						snmpSample(serverId, 4096, "hrStorageIndex", "33", "hrStorageDescr", "/",
								"hrStorageType", "1.3.6.1.2.1.25.2.1.4"))));

		Map<String, JsonNode> byKey = byResourceKey(getJson("/api/servers/" + serverId + "/resources"));

		assertThat(byKey.get("cpu").get("usagePercent").asDouble()).isEqualTo(32.0);
		assertThat(byKey.get("memory").get("totalBytes").asLong()).isEqualTo(8_589_934_592L);
		assertThat(byKey.get("memory").get("usagePercent").asDouble()).isEqualTo(50.0);
		assertThat(byKey.get("/").get("totalBytes").asLong()).isEqualTo(107_374_182_400L);
		assertThat(byKey.get("/").get("usagePercent").asDouble()).isEqualTo(42.0);
	}

	@Test
	void reportsUnavailableWhenPrometheusIsDown() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");
		configureSnmp(serverId, COMMUNITY);
		selectResources(serverId, resourceJson("CPU", "cpu", null));
		givenPrometheusUnavailable();

		JsonNode resources = getJson("/api/servers/" + serverId + "/resources");

		assertThat(resources.get(0).get("status").asString()).isEqualTo("UNAVAILABLE");
	}

	// ---------------------------------------------------------------------
	// Server 상태
	// ---------------------------------------------------------------------

	@Test
	void marksServerOkWhenSnmpMetricsArriveAndUnknownWhenTheyDoNot() throws Exception {
		long collecting = createServer("WAS-01", "10.0.0.11");
		long silent = createServer("WAS-02", "10.0.0.12");
		configureSnmp(collecting, COMMUNITY);
		configureSnmp(silent, COMMUNITY);
		selectResources(collecting, resourceJson("CPU", "cpu", null));
		selectResources(silent, resourceJson("CPU", "cpu", null));

		givenSnmpMetricQueries(Map.of("up{", List.of(snmpSample(collecting, 1)), "hrProcessorLoad",
				List.of(snmpSample(collecting, 12))));

		Map<String, JsonNode> byName = byName(getJson("/api/servers"));

		assertThat(byName.get("WAS-01").get("status").asString()).isEqualTo("OK");
		assertThat(byName.get("WAS-01").get("snmpState").asString()).isEqualTo("CONNECTED");
		assertThat(byName.get("WAS-02").get("status").asString()).isEqualTo("UNKNOWN");
		assertThat(byName.get("WAS-02").get("snmpState").asString()).isEqualTo("NO_RESPONSE");
	}

	/** Threshold가 없으므로 사용률이 아무리 높아도 CRITICAL이 되면 안 됩니다. */
	@Test
	void neverMarksServerCriticalFromHighUsage() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");
		configureSnmp(serverId, COMMUNITY);
		selectResources(serverId, resourceJson("CPU", "cpu", null));
		givenSnmpMetricQueries(
				Map.of("up{", List.of(snmpSample(serverId, 1)), "hrProcessorLoad", List.of(snmpSample(serverId, 99))));

		assertThat(getJson("/api/servers/" + serverId).get("status").asString()).isEqualTo("OK");
	}

	@Test
	void marksServerNotConfiguredWithoutSnmp() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");

		JsonNode server = getJson("/api/servers/" + serverId);

		assertThat(server.get("snmpState").asString()).isEqualTo("NOT_CONFIGURED");
		assertThat(server.get("status").asString()).isEqualTo("UNKNOWN");
		assertThat(server.get("monitoredResourceCount").asInt()).isZero();
	}

	// ---------------------------------------------------------------------
	// Service Discovery
	// ---------------------------------------------------------------------

	@Test
	void publishesOnlyServersWithSnmpAndSelectedResources() throws Exception {
		long ready = createServer("WAS-01", "10.0.0.11");
		long withoutResources = createServer("WAS-02", "10.0.0.12");
		long withoutSnmp = createServer("WAS-03", "10.0.0.13");
		configureSnmp(ready, COMMUNITY);
		configureSnmp(withoutResources, COMMUNITY);
		selectResources(ready, resourceJson("CPU", "cpu", null));

		JsonNode targets = getJson("/api/prometheus/sd/snmp");

		assertThat(targets).hasSize(1);
		assertThat(targets.get(0).get("targets").get(0).asString()).isEqualTo("10.0.0.11:161");
		assertThat(targets.get(0).get("labels").get("server_id").asString()).isEqualTo(String.valueOf(ready));
		assertThat(targets.get(0).get("labels").get("snmp_auth").asString())
				.isEqualTo("manager_server_" + ready);
		assertThat(targets.toString()).doesNotContain(String.valueOf(withoutSnmp) + ":161");
	}

	@Test
	void excludesDisabledSnmpFromServiceDiscovery() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");
		configureSnmp(serverId, COMMUNITY);
		selectResources(serverId, resourceJson("CPU", "cpu", null));

		mockMvc.perform(put("/api/servers/" + serverId + "/snmp").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"version":"V2C","port":161,"community":null,"enabled":false}
						"""));

		assertThat(getJson("/api/prometheus/sd/snmp")).isEmpty();
	}

	@Test
	void picksModulesFromSelectedResourceTypes() throws Exception {
		long cpuOnly = createServer("WAS-01", "10.0.0.11");
		long storageOnly = createServer("WAS-02", "10.0.0.12");
		configureSnmp(cpuOnly, COMMUNITY);
		configureSnmp(storageOnly, COMMUNITY);
		selectResources(cpuOnly, resourceJson("CPU", "cpu", null));
		selectResources(storageOnly, resourceJson("STORAGE", "/", 33));

		Map<String, JsonNode> byServerId = getJson("/api/prometheus/sd/snmp").valueStream()
				.collect(java.util.stream.Collectors
						.toMap(node -> node.get("labels").get("server_id").asString(), node -> node));

		assertThat(byServerId.get(String.valueOf(cpuOnly)).get("labels").get("snmp_modules").asString())
				.isEqualTo("system,hrDevice");
		assertThat(byServerId.get(String.valueOf(storageOnly)).get("labels").get("snmp_modules").asString())
				.isEqualTo("system,hrStorage");
	}

	@Test
	void keepsServiceDiscoveryLabelsAsStrings() throws Exception {
		long serverId = createServer("WAS-01", "10.0.0.11");
		configureSnmp(serverId, COMMUNITY);
		selectResources(serverId, resourceJson("CPU", "cpu", null));

		JsonNode labels = getJson("/api/prometheus/sd/snmp").get(0).get("labels");

		labels.propertyStream().forEach(entry -> assertThat(entry.getValue().isString())
				.as("label %s", entry.getKey())
				.isTrue());
	}

	// ---------------------------------------------------------------------
	// helper
	// ---------------------------------------------------------------------

	private String communityCipherOf(long serverId) {
		return serverSnmpConfigRepository.findByServerId(serverId)
				.map(ServerSnmpConfig::getCommunityCipher)
				.orElse(null);
	}

	private String authConfigContent() throws Exception {
		Path path = Path.of(System.getProperty("java.io.tmpdir"), "monitoring-manager-test", "snmp-auth.yml");
		return Files.exists(path) ? Files.readString(path) : "";
	}

	private JsonNode postJson(String path) throws Exception {
		String response = mockMvc.perform(post(path)).andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response);
	}

	private List<String> typesOf(JsonNode discovery) {
		return discovery.get("resources").valueStream().map(node -> node.get("resourceType").asString()).toList();
	}

	private List<String> keysOf(JsonNode discovery) {
		return discovery.get("resources").valueStream().map(node -> node.get("resourceKey").asString()).toList();
	}

	private Map<String, JsonNode> byResourceKey(JsonNode resources) {
		return resources.valueStream()
				.collect(java.util.stream.Collectors.toMap(node -> node.get("resourceKey").asString(), node -> node));
	}

	private Map<String, JsonNode> byName(JsonNode servers) {
		return servers.valueStream()
				.collect(java.util.stream.Collectors.toMap(node -> node.get("name").asString(), node -> node));
	}

}
