package kr.co.unidocs.monitoring.prometheus;

import kr.co.unidocs.monitoring.TestProperties;
import kr.co.unidocs.monitoring.config.MonitoringManagerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Prometheus HTTP API를 호출하는 부분을 직접 확인합니다.
 *
 * 다른 테스트에서는 이 class를 Mock으로 바꿉니다.
 * 그래서 요청을 만드는 코드에 문제가 있어도 드러나지 않습니다.
 * PromQL의 중괄호를 URI template 변수로 잘못 해석해 요청이 나가지 않은 적이 있어 이 테스트를 추가했습니다.
 *
 * Docker가 필요하지 않습니다.
 */
class PrometheusQueryServiceTest {

	private static final String BASE_URL = "http://prometheus:9090";

	private PrometheusQueryService service;

	private MockRestServiceServer server;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		service = new PrometheusQueryService(builder, properties());
	}

	@Test
	void sendsPromqlWithBracesAsAnEncodedQueryParameter() {
		server.expect(requestTo(BASE_URL + "/api/v1/query?query=probe_success%7Bjob%3D%22manager-blackbox%22%7D"))
				.andRespond(withSuccess("""
						{"status":"success","data":{"resultType":"vector","result":[]}}
						""", MediaType.APPLICATION_JSON));

		PrometheusQueryResult result = service.query("probe_success{job=\"manager-blackbox\"}");

		assertThat(result.available()).isTrue();
		assertThat(result.samples()).isEmpty();
		server.verify();
	}

	@Test
	void parsesInstantVectorSamples() {
		server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL))).andRespond(withSuccess("""
				{"status":"success","data":{"resultType":"vector","result":[
				  {"metric":{"check_id":"12","job":"manager-blackbox"},"value":[1758512345.123,"1"]},
				  {"metric":{"check_id":"13","job":"manager-blackbox"},"value":[1758512345.123,"0"]}
				]}}
				""", MediaType.APPLICATION_JSON));

		PrometheusQueryResult result = service.query("probe_success");

		assertThat(result.available()).isTrue();
		assertThat(result.samples()).hasSize(2);
		assertThat(result.samples().get(0).label("check_id")).isEqualTo("12");
		assertThat(result.samples().get(0).value()).isEqualTo(1.0d);
		assertThat(result.samples().get(1).value()).isEqualTo(0.0d);
		assertThat(result.samples().get(0).sampledAt()).isNotNull();
	}

	@Test
	void returnsUnavailableWhenPrometheusFails() {
		server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL))).andRespond(withServerError());

		PrometheusQueryResult result = service.query("probe_success");

		// 예외를 밖으로 던지지 않습니다. 호출한 쪽은 상태를 UNKNOWN으로 처리합니다.
		assertThat(result.available()).isFalse();
		assertThat(result.samples()).isEmpty();
	}

	@Test
	void returnsUnavailableWhenResponseStatusIsNotSuccess() {
		server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL)))
				.andRespond(withSuccess("""
						{"status":"error","errorType":"bad_data","error":"invalid expression"}
						""", MediaType.APPLICATION_JSON));

		assertThat(service.query("nonsense{{").available()).isFalse();
	}

	private MonitoringManagerProperties properties() {
		return TestProperties.defaults(BASE_URL,
				TestProperties.telegram(null, "https://api.telegram.org"));
	}

}
