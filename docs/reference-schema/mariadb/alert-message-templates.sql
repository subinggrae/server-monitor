-- 알림 메시지 템플릿의 참고용 schema (MariaDB)
--
-- 코어를 붙이는 관리자 서버가 알림 메시지 템플릿을 저장할 때 참고하는 DDL이다. 이 저장소의 앱은 이 테이블을 쓰지 않으므로
-- Manager Database migration에 넣지 않는다. 템플릿 종류, 변수, 검사 규칙은 docs/ADMIN-SERVER.md의 알림 메시지 템플릿에 있다.
-- postgresql/alert-message-templates.sql과 논리 schema가 같아야 한다.
--
-- 모든 감시 항목과 서버가 함께 쓰는 템플릿이라 다른 테이블을 외래 키로 가리키지 않는다.
--
-- 작성 규칙
--   * 시간 값은 DATETIME(6)에 UTC로 저장한다.
--   * 종류 값과 템플릿 길이는 앱의 요청 검증에서 확인한다.

-- 템플릿 종류당 0~1행이다. 행이 없는 종류는 관리자 서버가 가진 기본 템플릿을 쓴다.
-- 종류(template_type)는 CHECK_FIRING, CHECK_RESOLVED, RESOURCE_FIRING, RESOURCE_RESOLVED다.
-- 템플릿(body)은 1~1000자이고 글자 수는 code point로 센다. 템플릿을 비우면 빈 문자열을 저장하지 않고 행을 지운다.
CREATE TABLE alert_message_templates (
    template_type VARCHAR(30)   NOT NULL,
    body          VARCHAR(1000) NOT NULL,
    created_at    DATETIME(6)   NOT NULL,
    updated_at    DATETIME(6)   NOT NULL,
    CONSTRAINT pk_alert_message_templates PRIMARY KEY (template_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
