/**
 * Manager Database에 저장하는 인증 정보를 암호화합니다.
 *
 * 평문은 저장하지 않고, 복호화는 실제 SNMP 연결이나 exporter auth config를 만들 때만 합니다.
 * 인증 정보는 마스킹한 값이라도 Frontend로 반환하지 않습니다. (CLAUDE.md 11번)
 */
package kr.co.unidocs.monitoring.common.security;
