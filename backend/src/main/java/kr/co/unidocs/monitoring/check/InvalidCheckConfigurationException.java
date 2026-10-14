package kr.co.unidocs.monitoring.check;

/**
 * Check Type과 입력값이 맞지 않을 때 사용합니다.
 * 메시지는 사용자에게 그대로 보이므로 무엇을 고쳐야 하는지 적습니다.
 */
public class InvalidCheckConfigurationException extends RuntimeException {

	public InvalidCheckConfigurationException(String message) {
		super(message);
	}

}
