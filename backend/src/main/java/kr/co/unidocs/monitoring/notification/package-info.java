/**
 * Alert를 Telegram 메시지로 바꿔서 보내고 결과를 기록합니다.
 *
 * routing은 누구에게 보낼지 결정하고, telegram은 실제로 전송합니다.
 * 알림 대상은 Manager Database에서 조회합니다. alertmanager.yml을 건드리지 않습니다.
 */
package kr.co.unidocs.monitoring.notification;
