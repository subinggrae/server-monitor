package kr.co.unidocs.monitoring.check;

/**
 * blackboxModule은 infrastructure/blackbox/blackbox.yml에 정의된 이름과 같아야 합니다.
 * 없는 이름을 넘기면 Blackbox Exporter가 probe 요청을 400으로 거부합니다.
 */
public enum CheckType {

	ICMP("Ping", "icmp", false, false),
	TCP("TCP", "tcp_connect", true, false),
	HTTP("HTTP", "http_2xx", false, true),
	HTTPS("HTTPS", "http_2xx", false, true);

	private final String displayName;

	private final String blackboxModule;

	private final boolean requiresPort;

	private final boolean requiresUrl;

	CheckType(String displayName, String blackboxModule, boolean requiresPort, boolean requiresUrl) {
		this.displayName = displayName;
		this.blackboxModule = blackboxModule;
		this.requiresPort = requiresPort;
		this.requiresUrl = requiresUrl;
	}

	public String displayName() {
		return displayName;
	}

	public String blackboxModule() {
		return blackboxModule;
	}

	public boolean requiresPort() {
		return requiresPort;
	}

	public boolean requiresUrl() {
		return requiresUrl;
	}

	public String requiredUrlScheme() {
		return switch (this) {
			case HTTP -> "http";
			case HTTPS -> "https";
			default -> throw new IllegalStateException(this + " Type은 URL을 사용하지 않습니다.");
		};
	}

}
