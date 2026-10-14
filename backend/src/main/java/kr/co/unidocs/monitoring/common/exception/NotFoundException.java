package kr.co.unidocs.monitoring.common.exception;

/**
 * getMessage()는 사용자에게 그대로 보입니다.
 * 내부 id는 사용자에게 의미가 없어 메시지에 넣지 않고 로그용으로만 보관합니다.
 */
public class NotFoundException extends RuntimeException {

	private final Object resourceId;

	public NotFoundException(String message) {
		this(message, null);
	}

	public NotFoundException(String message, Object resourceId) {
		super(message);
		this.resourceId = resourceId;
	}

	public Object getResourceId() {
		return resourceId;
	}

	public static NotFoundException of(String resourceName, Object resourceId) {
		return new NotFoundException(resourceName + "을(를) 찾을 수 없습니다. 목록에서 다시 확인해 주세요.", resourceId);
	}

}
