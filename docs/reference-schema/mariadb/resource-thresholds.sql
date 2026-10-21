-- 서버 리소스 임계값 알림의 참고용 schema (MariaDB)
--
-- 코어를 붙이는 앱이 리소스 임계값을 판단할 때 참고하는 DDL입니다. 이 저장소의 앱은 이 테이블을 쓰지 않으므로
-- Manager Database migration에 넣지 않습니다. 결정과 이유는 docs/decisions/0008-resource-threshold-in-app.md,
-- 판단 규칙은 docs/INTEGRATION.md에 있습니다.
-- postgresql/resource-thresholds.sql과 논리 schema가 같아야 합니다.
--
-- 기준 migration(backend/src/main/resources/db/migration/mariadb/V1__baseline.sql)의 server_targets와
-- monitored_resources를 외래 키로 가리킵니다. 두 테이블의 이름을 바꿨으면 여기서도 바꿉니다.
--
-- 작성 규칙
--   * 시간 값은 DATETIME(6)에 UTC로 저장합니다.
--   * 허용 범위는 앱의 요청 검증에서 확인합니다.
--   * 단계(severity)는 WARNING 또는 CRITICAL입니다.

-- 감시 Resource당 0~1행입니다. 행이 없으면 임계값이 없는 상태이고 판단하지 않습니다.
-- 임계값은 1~100 정수 %이고 Warning은 Critical보다 작습니다. NULL인 단계는 판단하지 않습니다.
-- 지속 시간은 1~60분이고 화면의 처음 값은 5분입니다.
CREATE TABLE resource_thresholds (
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    monitored_resource_id BIGINT      NOT NULL,
    warning_percent       INT         NULL,
    critical_percent      INT         NULL,
    duration_minutes      INT         NOT NULL,
    created_at            DATETIME(6) NOT NULL,
    updated_at            DATETIME(6) NOT NULL,
    CONSTRAINT pk_resource_thresholds PRIMARY KEY (id),
    CONSTRAINT uk_resource_thresholds_resource UNIQUE (monitored_resource_id),
    CONSTRAINT fk_resource_thresholds_resource FOREIGN KEY (monitored_resource_id)
        REFERENCES monitored_resources (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 서버마다 리소스 알림 재발송 주기입니다. 행이 없는 서버는 60분입니다.
-- 허용 범위는 Check의 재발송 주기와 같은 5~1440분입니다.
CREATE TABLE server_resource_alert_settings (
    server_id               BIGINT      NOT NULL,
    repeat_interval_minutes INT         NOT NULL,
    created_at              DATETIME(6) NOT NULL,
    updated_at              DATETIME(6) NOT NULL,
    CONSTRAINT pk_server_resource_alert_settings PRIMARY KEY (server_id),
    CONSTRAINT fk_server_resource_alert_settings_server FOREIGN KEY (server_id)
        REFERENCES server_targets (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 앱을 다시 띄워도 판단을 이어 가기 위한 상태입니다.
-- 단계가 임계값을 넘기 시작했거나 발생 중일 때만 행이 있고, 정상이 되면 행을 지웁니다.
--   대기: resource_alert_event_id와 last_notified_at이 NULL
--   발생: 발생 기록의 id와 마지막으로 알림을 보낸 시각이 있음
-- Resource를 지우기 전에 발생 중인 단계는 앱이 복구 알림으로 끝냅니다.
CREATE TABLE resource_alert_states (
    id                      BIGINT      NOT NULL AUTO_INCREMENT,
    monitored_resource_id   BIGINT      NOT NULL,
    severity                VARCHAR(20) NOT NULL,
    exceeded_since          DATETIME(6) NOT NULL,
    resource_alert_event_id BIGINT      NULL,
    last_notified_at        DATETIME(6) NULL,
    updated_at              DATETIME(6) NOT NULL,
    CONSTRAINT pk_resource_alert_states PRIMARY KEY (id),
    CONSTRAINT uk_resource_alert_states_stage UNIQUE (monitored_resource_id, severity),
    CONSTRAINT fk_resource_alert_states_resource FOREIGN KEY (monitored_resource_id)
        REFERENCES monitored_resources (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 발생 한 번이 한 행입니다.
-- 서버와 Resource에 FK를 걸지 않습니다. 지운 뒤에도 무엇이 문제였는지 알 수 있게 발생 때의 값을 남깁니다.
CREATE TABLE resource_alert_events (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    server_id             BIGINT       NOT NULL,
    monitored_resource_id BIGINT       NOT NULL,
    -- CPU, MEMORY, STORAGE입니다.
    resource_type         VARCHAR(20)  NOT NULL,
    resource_key          VARCHAR(255) NOT NULL,
    display_name          VARCHAR(255) NOT NULL,
    severity              VARCHAR(20)  NOT NULL,
    -- 발생 때의 임계값과 사용률입니다.
    threshold_percent     INT          NOT NULL,
    usage_percent         DECIMAL(5,2) NOT NULL,
    fired_at              DATETIME(6)  NOT NULL,
    -- 복구 전에는 NULL입니다.
    resolved_at           DATETIME(6)  NULL,
    created_at            DATETIME(6)  NOT NULL,
    CONSTRAINT pk_resource_alert_events PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 최근 발생 목록에서 씁니다.
CREATE INDEX ix_resource_alert_events_fired_at ON resource_alert_events (fired_at);
