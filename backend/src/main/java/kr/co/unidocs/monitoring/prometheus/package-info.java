/**
 * Prometheus 연동입니다. Target 목록 제공과 PromQL 조회를 담당합니다.
 *
 * Target을 등록하면 다음 Service Discovery 조회부터 반영됩니다.
 * prometheus.yml을 수정하거나 Prometheus를 다시 시작하지 않습니다. (CLAUDE.md 1번, 2번)
 *
 * PromQL은 이 package 안에서만 만듭니다. Frontend로 내보내지 않습니다. (CLAUDE.md 12번)
 */
package kr.co.unidocs.monitoring.prometheus;
