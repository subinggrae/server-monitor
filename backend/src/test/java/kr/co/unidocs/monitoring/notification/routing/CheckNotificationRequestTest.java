package kr.co.unidocs.monitoring.notification.routing;

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
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("알림 설정 저장 요청의 재발송 주기 검증")
class CheckNotificationRequestTest {

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

	@ParameterizedTest(name = "{0}분")
	@ValueSource(strings = { "4", "1441", "15.5" })
	@DisplayName("5분보다 짧거나 1440분보다 길거나 정수가 아니면 재발송 주기 오류가 난다")
	void rejectsOutOfRange(String minutes) {
		Set<ConstraintViolation<CheckNotificationRequest>> violations = validator.validate(request(new BigDecimal(minutes)));

		assertThat(violations)
				.extracting(violation -> violation.getPropertyPath().toString())
				.containsOnly("repeatIntervalMinutes");
	}

	@ParameterizedTest(name = "{0}분")
	@ValueSource(strings = { "5", "1440" })
	@DisplayName("5분과 1440분은 받는다")
	void acceptsBoundaries(String minutes) {
		assertThat(validator.validate(request(new BigDecimal(minutes)))).isEmpty();
	}

	@Test
	@DisplayName("재발송 주기가 없어도 받는다")
	void acceptsMissing() {
		assertThat(validator.validate(request(null))).isEmpty();
	}

	@Test
	@DisplayName("JSON의 15.5를 15로 잘라 받지 않고 재발송 주기 오류를 낸다")
	void rejectsFractionFromJson() {
		CheckNotificationRequest request = JsonMapper.builder().build().readValue("""
				{"bindings": [], "repeatIntervalMinutes": 15.5}
				""", CheckNotificationRequest.class);

		assertThat(validator.validate(request))
				.extracting(violation -> violation.getPropertyPath().toString())
				.containsExactly("repeatIntervalMinutes");
	}

	private CheckNotificationRequest request(BigDecimal repeatIntervalMinutes) {
		return new CheckNotificationRequest(List.of(), repeatIntervalMinutes);
	}

}
