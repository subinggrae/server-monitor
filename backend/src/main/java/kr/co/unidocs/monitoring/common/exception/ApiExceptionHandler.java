package kr.co.unidocs.monitoring.common.exception;

import kr.co.unidocs.monitoring.check.InvalidCheckConfigurationException;
import kr.co.unidocs.monitoring.common.security.SecretEncryptionException;
import kr.co.unidocs.monitoring.notification.routing.DuplicateNotificationBindingException;
import kr.co.unidocs.monitoring.notification.telegram.DuplicateTelegramDestinationException;
import kr.co.unidocs.monitoring.snmp.SnmpConfigurationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring Boot의 기본 handler는 "Invalid request content."처럼 사용자가 이해하기 어려운 메시지를 돌려줍니다.
 * 그래서 이 handler가 먼저 처리하도록 우선순위를 가장 높게 둡니다.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class ApiExceptionHandler {

	@ExceptionHandler(NotFoundException.class)
	ProblemDetail handleNotFound(NotFoundException ex) {
		return problem(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다", ex.getMessage());
	}

	@ExceptionHandler(DuplicateNameException.class)
	ProblemDetail handleDuplicateName(DuplicateNameException ex) {
		return problem(HttpStatus.CONFLICT, "이름이 중복되었습니다", ex.getMessage());
	}

	@ExceptionHandler(InvalidCheckConfigurationException.class)
	ProblemDetail handleInvalidCheckConfiguration(InvalidCheckConfigurationException ex) {
		return problem(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요", ex.getMessage());
	}

	@ExceptionHandler(DuplicateTelegramDestinationException.class)
	ProblemDetail handleDuplicateTelegramDestination(DuplicateTelegramDestinationException ex) {
		return problem(HttpStatus.CONFLICT, "이미 등록된 그룹입니다", ex.getMessage());
	}

	@ExceptionHandler(DuplicateNotificationBindingException.class)
	ProblemDetail handleDuplicateNotificationBinding(DuplicateNotificationBindingException ex) {
		return problem(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요", ex.getMessage());
	}

	@ExceptionHandler(SnmpConfigurationException.class)
	ProblemDetail handleSnmpConfiguration(SnmpConfigurationException ex) {
		return problem(HttpStatus.BAD_REQUEST, "SNMP 설정을 확인해 주세요", ex.getMessage());
	}

	/** 메시지에 평문이나 키가 들어 있지 않은지는 예외를 만드는 쪽에서 지킵니다. */
	@ExceptionHandler(SecretEncryptionException.class)
	ProblemDetail handleSecretEncryption(SecretEncryptionException ex) {
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "인증 정보를 처리하지 못했습니다", ex.getMessage());
	}

	/** Frontend가 어느 입력 칸에 오류를 표시할지 알 수 있도록 field별 메시지를 함께 돌려줍니다. */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
		Map<String, String> fieldErrors = new LinkedHashMap<>();
		for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
			fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
		}

		String firstMessage = fieldErrors.values().stream().findFirst().orElse("입력값을 확인해 주세요.");
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요", firstMessage);
		problem.setProperty("fieldErrors", fieldErrors);
		return problem;
	}

	private ProblemDetail problem(HttpStatus status, String title, String detail) {
		ProblemDetail problem = ProblemDetail.forStatus(status);
		problem.setTitle(title);
		problem.setDetail(detail);
		return problem;
	}

}
