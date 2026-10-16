package kr.co.unidocs.monitoring.exporter;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("수집 설정 저장 요청의 검증")
class ServerExporterRequestTest {

	private static ValidatorFactory validatorFactory;

	private static Validator validator;

	@BeforeAll
	static void createValidator() {
		validatorFactory = Validation.buildDefaultValidatorFactory();
		validator = validatorFactory.getValidator();
	}

	@AfterAll
	static void closeValidator() {
		validatorFactory.close();
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(ints = { 1, 9100, 65535 })
	@DisplayName("포트 1~65535는 받는다")
	void acceptsPortInRange(int port) {
		assertThat(validator.validate(new ServerExporterRequest(true, port, new BigDecimal("15")))).isEmpty();
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(ints = { 0, 65536 })
	@DisplayName("범위 밖의 포트는 port 칸 오류가 난다")
	void rejectsPortOutOfRange(int port) {
		assertThat(fields(new ServerExporterRequest(true, port, new BigDecimal("15")))).containsExactly("port");
	}

	@ParameterizedTest(name = "{0}초")
	@ValueSource(strings = { "10", "240" })
	@DisplayName("수집 주기 10초와 240초는 받는다")
	void acceptsIntervalBoundaries(String seconds) {
		assertThat(validator.validate(new ServerExporterRequest(true, 9100, new BigDecimal(seconds)))).isEmpty();
	}

	@ParameterizedTest(name = "{0}초")
	@ValueSource(strings = { "9", "241", "15.5" })
	@DisplayName("10초보다 짧거나 240초보다 길거나 정수가 아니면 수집 주기 칸 오류가 난다")
	void rejectsIntervalOutOfRange(String seconds) {
		assertThat(fields(new ServerExporterRequest(true, 9100, new BigDecimal(seconds))))
			.containsExactly("scrapeIntervalSeconds");
	}

	@Test
	@DisplayName("사용 여부, 포트, 수집 주기가 없으면 각 칸 오류가 난다")
	void rejectsMissingValues() {
		assertThat(fields(new ServerExporterRequest(null, null, null)))
			.containsExactlyInAnyOrder("enabled", "port", "scrapeIntervalSeconds");
	}

	@Test
	@DisplayName("연결 시험 요청은 포트 범위를 검증한다")
	void validatesTestRequestPort() {
		assertThat(validator.validate(new ExporterTestRequest(65536)))
			.extracting(violation -> violation.getPropertyPath().toString())
			.containsExactly("port");
		assertThat(validator.validate(new ExporterTestRequest(null)))
			.extracting(violation -> violation.getPropertyPath().toString())
			.containsExactly("port");
	}

	private static Set<String> fields(ServerExporterRequest request) {
		Set<ConstraintViolation<ServerExporterRequest>> violations = validator.validate(request);
		return violations.stream()
			.map(violation -> violation.getPropertyPath().toString())
			.collect(Collectors.toSet());
	}

}
