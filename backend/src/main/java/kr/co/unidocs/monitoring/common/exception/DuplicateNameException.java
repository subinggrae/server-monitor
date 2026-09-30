package kr.co.unidocs.monitoring.common.exception;

/** DB의 unique 제약 오류를 그대로 내보내면 사용자가 무슨 뜻인지 알 수 없어 Service에서 미리 확인합니다. */
public class DuplicateNameException extends RuntimeException {

	public DuplicateNameException(String message) {
		super(message);
	}

}
