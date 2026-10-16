/**
 * 서버의 OS exporter(node_exporter, windows_exporter)입니다.
 *
 * 지속적인 수집은 Prometheus가 HTTP SD로 받은 대상을 직접 합니다.
 * Manager는 연결 시험과 리소스 찾기에서만 exporter의 /metrics를 한 번 읽습니다.
 * 그 응답은 신뢰할 수 없는 입력이므로 크기와 시간에 한도를 두고, 본문을 화면과 로그에 넣지 않습니다.
 */
package kr.co.unidocs.monitoring.exporter;
