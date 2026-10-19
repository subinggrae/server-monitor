/**
 * Telegram 그룹 관리와 Bot API 호출입니다.
 *
 * Bot Token은 Manager Database에 저장하지 않고 API 응답에도 넣지 않습니다.
 * chat id는 alertmanager.yml이 아니라 Manager Database가 관리합니다.
 */
package kr.co.unidocs.monitoring.notification.telegram;
