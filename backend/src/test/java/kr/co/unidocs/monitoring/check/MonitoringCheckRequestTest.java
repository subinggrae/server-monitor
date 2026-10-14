package kr.co.unidocs.monitoring.check;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import kr.co.unidocs.monitoring.check.MonitoringCheckRequest.TargetRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Check 저장 요청의 수집 주기 검증")
class MonitoringCheckRequestTest {

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
	@ValueSource(ints = { 9, 241 })
	@DisplayName("10초보다 짧거나 240초보다 길면 수집 주기 오류가 난다")
	void rejectsOutOfRange(int seconds) {
		Set<ConstraintViolation<MonitoringCheckRequest>> violations = validator.validate(request(seconds));

		assertThat(violations)
				.extracting(violation -> violation.getPropertyPath().toString())
				.containsExactly("scrapeIntervalSeconds");
	}

	@ParameterizedTest(name = "{0}초")
	@ValueSource(ints = { 10, 240 })
	@DisplayName("10초와 240초는 받는다")
	void acceptsBoundaries(int seconds) {
		assertThat(validator.validate(request(seconds))).isEmpty();
	}

	@ParameterizedTest(name = "값 없음")
	@NullSource
	@DisplayName("수집 주기가 없어도 받는다")
	void acceptsMissing(Integer seconds) {
		assertThat(validator.validate(request(seconds))).isEmpty();
	}

	private MonitoringCheckRequest request(Integer scrapeIntervalSeconds) {
		return new MonitoringCheckRequest("전자결재 Health", CheckType.ICMP, "Production", null, true,
				scrapeIntervalSeconds, List.of(new TargetRequest(null, "10.0.0.1", null, true)));
	}

}
