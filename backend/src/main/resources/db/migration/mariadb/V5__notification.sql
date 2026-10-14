-- 알림 경로를 Manager Database로 옮깁니다. (MariaDB / MySQL)
--
-- 지금까지는 Alertmanager가 Telegram으로 직접 보냈습니다.
-- 그래서 Telegram 그룹을 추가하려면 alertmanager.yml을 고쳐야 했습니다.
--
-- 앞으로는 이렇게 흐릅니다.
--
--   Prometheus → Alertmanager → Manager Webhook
--   → Notification Binding 조회 → Telegram Destination → Telegram Bot API
--
-- Alertmanager는 Webhook 하나만 알고 있습니다. 누구에게 보낼지는 이 table이 정합니다.
--
-- postgresql/V5__notification.sql과 논리 Schema가 같아야 합니다.

-- ---------------------------------------------------------------------------
-- 알림을 받을 Telegram 그룹
--
-- Bot Token은 여기에 넣지 않습니다. Token은 시스템 secret이라 환경변수나 파일로 받습니다.
-- ---------------------------------------------------------------------------
CREATE TABLE telegram_destinations (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    name              VARCHAR(100) NOT NULL,
    -- 그룹은 음수, supergroup은 -100으로 시작하는 큰 값이 들어옵니다. 그래서 BIGINT입니다.
    chat_id           BIGINT       NOT NULL,
    -- Forum topic에만 사용합니다. 일반 그룹은 NULL입니다.
    message_thread_id INT          NULL,
    enabled           TINYINT(1)   NOT NULL DEFAULT 1,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    -- 같은 그룹의 같은 topic을 두 번 등록하면 알림이 두 번 갑니다.
    --
    -- 다만 이 제약은 message_thread_id가 NULL이면 동작하지 않습니다.
    -- MariaDB와 PostgreSQL 모두 UNIQUE에서 NULL을 서로 다른 값으로 보기 때문입니다.
    -- topic이 없는 그룹의 중복은 애플리케이션에서 막습니다.
    CONSTRAINT uk_telegram_destinations_chat UNIQUE (chat_id, message_thread_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------------------
-- Monitoring Check와 Telegram 그룹의 연결
--
-- 하나의 Check가 그룹 여러 개에 알릴 수 있습니다.
-- 연결이 없으면 알림을 보내지 않습니다. 기본 그룹을 몰래 쓰지 않습니다.
--
-- 앞으로 Server Resource와 Database Target에도 알림이 필요하지만,
-- 지금 없는 대상까지 담는 구조를 미리 만들지 않습니다. (CLAUDE.md 13번)
-- ---------------------------------------------------------------------------
CREATE TABLE monitoring_check_notification_bindings (
    id                      BIGINT      NOT NULL AUTO_INCREMENT,
    check_id                BIGINT      NOT NULL,
    telegram_destination_id BIGINT      NOT NULL,
    -- Warning Alert는 Threshold 단계에서 생깁니다. 지금은 설정만 저장합니다.
    warning_enabled         TINYINT(1)  NOT NULL DEFAULT 0,
    critical_enabled        TINYINT(1)  NOT NULL DEFAULT 1,
    -- 복구 알림을 보낼지 정합니다.
    send_resolved           TINYINT(1)  NOT NULL DEFAULT 1,
    enabled                 TINYINT(1)  NOT NULL DEFAULT 1,
    created_at              DATETIME(6) NOT NULL,
    updated_at              DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    -- 같은 Check와 같은 그룹을 두 번 연결하면 알림이 두 번 갑니다.
    CONSTRAINT uk_check_notification_bindings UNIQUE (check_id, telegram_destination_id),
    -- Check나 그룹을 지우면 연결도 함께 지웁니다. 고아 데이터를 남기지 않습니다.
    CONSTRAINT fk_check_notification_bindings_check FOREIGN KEY (check_id)
        REFERENCES monitoring_checks (id) ON DELETE CASCADE,
    CONSTRAINT fk_check_notification_bindings_destination FOREIGN KEY (telegram_destination_id)
        REFERENCES telegram_destinations (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------------------
-- Alertmanager에서 받은 Alert
--
-- check_id와 check_target_id에 FK를 걸지 않습니다.
-- Check를 지워도 "그때 이런 장애가 있었다"는 기록은 남아야 하기 때문입니다.
-- ---------------------------------------------------------------------------
CREATE TABLE alert_events (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    -- Alertmanager가 label 조합으로 만드는 식별자입니다.
    fingerprint      VARCHAR(100) NOT NULL,
    alert_name       VARCHAR(200) NOT NULL,
    check_id         BIGINT       NULL,
    check_target_id  BIGINT       NULL,
    severity         VARCHAR(20)  NULL,
    -- FIRING 또는 RESOLVED입니다.
    status           VARCHAR(20)  NOT NULL,
    -- 실제로 확인하던 주소입니다. Target을 지운 뒤에도 무엇이 문제였는지 알 수 있게 남깁니다.
    instance         VARCHAR(500) NULL,
    summary          VARCHAR(500) NULL,
    description      VARCHAR(1000) NULL,
    starts_at        DATETIME(6)  NOT NULL,
    resolved_at      DATETIME(6)  NULL,
    -- 같은 Alert를 다시 받은 시각입니다. Alertmanager는 같은 내용을 반복해서 보냅니다.
    last_received_at DATETIME(6)  NOT NULL,
    created_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    -- 같은 장애 한 번을 한 줄로 유지합니다.
    --
    -- fingerprint만으로 묶으면 나중에 같은 대상이 다시 장애가 났을 때 기록이 덮입니다.
    -- Alertmanager는 장애가 해소된 뒤 다시 발생하면 starts_at을 새로 줍니다.
    -- 그래서 fingerprint와 starts_at을 함께 씁니다.
    CONSTRAINT uk_alert_events_fingerprint_starts_at UNIQUE (fingerprint, starts_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 최근 Alert 목록 화면에서 씁니다.
CREATE INDEX ix_alert_events_last_received_at ON alert_events (last_received_at);
CREATE INDEX ix_alert_events_check_id ON alert_events (check_id);

-- ---------------------------------------------------------------------------
-- Telegram 전송 결과
--
-- 알림이 안 왔다는 문의가 들어왔을 때 어디까지 진행됐는지 확인하는 용도입니다.
-- Bot Token이나 Telegram 응답 전체는 저장하지 않습니다. (CLAUDE.md 11번)
-- ---------------------------------------------------------------------------
CREATE TABLE notification_deliveries (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    alert_event_id          BIGINT       NOT NULL,
    -- 그룹을 지운 뒤에도 전송 기록은 남겨야 하므로 FK를 걸지 않습니다.
    telegram_destination_id BIGINT       NOT NULL,
    -- 어떤 상태의 Alert를 보냈는지입니다. FIRING 또는 RESOLVED입니다.
    alert_status            VARCHAR(20)  NOT NULL,
    -- SENT, FAILED, SKIPPED입니다.
    delivery_status         VARCHAR(20)  NOT NULL,
    attempted_at            DATETIME(6)  NOT NULL,
    -- 실패 이유입니다. Token이 섞여 들어가지 않도록 애플리케이션에서 걸러서 넣습니다.
    error_message           VARCHAR(500) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_notification_deliveries_alert_event FOREIGN KEY (alert_event_id)
        REFERENCES alert_events (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 같은 Alert를 짧은 시간에 다시 받았을 때 이미 보냈는지 확인합니다.
CREATE INDEX ix_notification_deliveries_lookup
    ON notification_deliveries (alert_event_id, telegram_destination_id, alert_status, attempted_at);
