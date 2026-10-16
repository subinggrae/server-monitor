package kr.co.unidocs.monitoring.server;

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

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("서버 저장 요청의 OS 검증")
class ServerTargetRequestTest {

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
	@ValueSource(strings = { "LINUX", "WINDOWS" })
	@DisplayName("LINUX와 WINDOWS는 받는다")
	void acceptsKnownOs(String os) {
		assertThat(validator.validate(request(os))).isEmpty();
	}

	@Test
	@DisplayName("OS가 없으면 os 칸 오류가 난다")
	void rejectsMissingOs() {
		assertThat(fields(validator.validate(request(null)))).containsExactly("os");
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "MAC", "linux", "", "LINUX " })
	@DisplayName("정한 값이 아니면 os 칸 오류가 난다")
	void rejectsUnknownOs(String os) {
		assertThat(fields(validator.validate(request(os)))).containsExactly("os");
	}

	@Test
	@DisplayName("검증을 통과한 값은 ServerOs로 바꿀 수 있다")
	void convertsToServerOs() {
		assertThat(request("WINDOWS").serverOs()).isEqualTo(ServerOs.WINDOWS);
	}

	private static ServerTargetRequest request(String os) {
		return new ServerTargetRequest("web01", "10.0.0.5", "Production", os, null, true);
	}

	private static Set<String> fields(Set<ConstraintViolation<ServerTargetRequest>> violations) {
		return violations.stream()
			.map(violation -> violation.getPropertyPath().toString())
			.collect(java.util.stream.Collectors.toSet());
	}

}
