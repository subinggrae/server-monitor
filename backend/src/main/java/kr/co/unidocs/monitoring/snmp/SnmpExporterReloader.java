package kr.co.unidocs.monitoring.snmp;

import kr.co.unidocs.monitoring.config.MonitoringManagerProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * snmp_exporter는 auth config를 다시 읽으라는 신호를 POST /-/reload로만 받습니다. GET은 400입니다.
 *
 * 파일을 바꾸는 것과 반영을 알리는 것을 나눠 두면 테스트에서 파일 생성만 따로 확인할 수 있습니다.
 */
@Component
public class SnmpExporterReloader {

	private final RestClient restClient;

	private final String reloadUrl;

	SnmpExporterReloader(RestClient.Builder restClientBuilder, MonitoringManagerProperties properties) {
		this.restClient = restClientBuilder.build();
		this.reloadUrl = properties.snmp().reloadUrl();
	}

	public void reload() {
		restClient.post().uri(reloadUrl).retrieve().toBodilessEntity();
	}

}
