-- Check마다 알림 재발송 주기를 둡니다. (MariaDB / MySQL)
--
-- Alert가 이어지는 동안 Alertmanager는 5분마다 webhook을 보내고,
-- Manager는 마지막으로 보낸 뒤 이 주기가 지났을 때만 Telegram으로 다시 보냅니다.
-- 행이 없는 Check는 60분입니다. 그래서 기존 Check에 행을 미리 만들지 않습니다.
-- 허용 범위(5~1440분)는 요청 DTO에서 검증합니다.
--
-- Monitoring Check가 알림을 몰라도 되도록 monitoring_checks에 열을 더하지 않고 테이블을 나눕니다.
--
-- postgresql/V8__check_notification_settings.sql과 논리 Schema가 같아야 합니다.

CREATE TABLE check_notification_settings (
    check_id                BIGINT      NOT NULL,
    repeat_interval_minutes INT         NOT NULL,
    created_at              DATETIME(6) NOT NULL,
    updated_at              DATETIME(6) NOT NULL,
    PRIMARY KEY (check_id),
    -- Check를 지우면 설정도 함께 지웁니다.
    CONSTRAINT fk_check_notification_settings_check FOREIGN KEY (check_id)
        REFERENCES monitoring_checks (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
