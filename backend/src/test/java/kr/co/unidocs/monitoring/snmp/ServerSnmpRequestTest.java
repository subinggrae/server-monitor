package kr.co.unidocs.monitoring.snmp;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SNMP 설정 저장 요청의 수집 주기 검증")
class ServerSnmpRequestTest {

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

	@ParameterizedTest(name = "{0}초")
	@ValueSource(strings = { "9", "241", "15.5" })
	@DisplayName("10초보다 짧거나 240초보다 길거나 정수가 아니면 수집 주기 오류가 난다")
	void rejectsOutOfRange(String seconds) {
		Set<ConstraintViolation<ServerSnmpRequest>> violations = validator.validate(request(new BigDecimal(seconds)));

		assertThat(violations)
				.extracting(violation -> violation.getPropertyPath().toString())
				.containsOnly("scrapeIntervalSeconds");
	}

	@ParameterizedTest(name = "{0}초")
	@ValueSource(strings = { "10", "240" })
	@DisplayName("10초와 240초는 받는다")
	void acceptsBoundaries(String seconds) {
		assertThat(validator.validate(request(new BigDecimal(seconds)))).isEmpty();
	}

	@ParameterizedTest(name = "값 없음")
	@NullSource
	@DisplayName("수집 주기가 없어도 받는다")
	void acceptsMissing(BigDecimal seconds) {
		assertThat(validator.validate(request(seconds))).isEmpty();
	}

	@Test
	@DisplayName("JSON의 15.5를 15로 잘라 받지 않고 수집 주기 오류를 낸다")
	void rejectsFractionFromJson() {
		ServerSnmpRequest request = JsonMapper.builder().build().readValue("""
				{"version": "V2C", "port": 161, "enabled": true, "scrapeIntervalSeconds": 15.5}
				""", ServerSnmpRequest.class);

		assertThat(validator.validate(request))
				.extracting(violation -> violation.getPropertyPath().toString())
				.containsExactly("scrapeIntervalSeconds");
	}

	private ServerSnmpRequest request(BigDecimal scrapeIntervalSeconds) {
		return new ServerSnmpRequest(SnmpVersion.V2C, 161, null, true, scrapeIntervalSeconds);
	}

}
