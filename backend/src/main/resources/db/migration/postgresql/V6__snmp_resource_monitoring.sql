-- SNMP 기반 Resource 모니터링을 추가합니다.
--
-- community는 평문으로 저장하지 않습니다. APP_SECRET_KEY로 암호화한 값만 둡니다.
-- Source of Truth는 이 table입니다. snmp_exporter의 auth config는 여기에서 만들어 냅니다.

CREATE TABLE server_snmp_configs (
    id                BIGSERIAL    NOT NULL,
    server_id         BIGINT       NOT NULL,
    snmp_version      VARCHAR(10)  NOT NULL,
    port              INT          NOT NULL,
    community_cipher  VARCHAR(512) NOT NULL,
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP(6) NOT NULL,
    updated_at        TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_server_snmp_configs PRIMARY KEY (id),
    CONSTRAINT uk_server_snmp_configs_server UNIQUE (server_id),
    CONSTRAINT fk_server_snmp_configs_server FOREIGN KEY (server_id)
        REFERENCES server_targets (id) ON DELETE CASCADE
);

-- 사용자가 모니터링하기로 고른 Resource입니다.
-- 장비에서 발견한 목록 전체가 아닙니다. 발견 결과는 저장하지 않습니다.
--
-- resource_key는 SNMP index가 아니라 장비가 준 식별자(Storage는 hrStorageDescr)입니다.
-- index는 재부팅이나 Agent 설정 변경으로 바뀔 수 있어 기준으로 쓸 수 없습니다.
CREATE TABLE monitored_resources (
    id            BIGSERIAL    NOT NULL,
    server_id     BIGINT       NOT NULL,
    resource_type VARCHAR(20)  NOT NULL,
    resource_key  VARCHAR(255) NOT NULL,
    display_name  VARCHAR(255) NOT NULL,
    description   VARCHAR(500),
    snmp_index    INT,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP(6) NOT NULL,
    updated_at    TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_monitored_resources PRIMARY KEY (id),
    CONSTRAINT uk_monitored_resources_key UNIQUE (server_id, resource_type, resource_key),
    CONSTRAINT fk_monitored_resources_server FOREIGN KEY (server_id)
        REFERENCES server_targets (id) ON DELETE CASCADE
);

CREATE INDEX ix_monitored_resources_server ON monitored_resources (server_id, enabled);
