package kr.co.unidocs.monitoring.common.security;

/** 메시지에 평문이나 키를 넣지 않습니다. 이 예외는 사용자 응답에도 그대로 나갈 수 있습니다. */
public class SecretEncryptionException extends RuntimeException {

	public SecretEncryptionException(String message) {
		super(message);
	}

}
