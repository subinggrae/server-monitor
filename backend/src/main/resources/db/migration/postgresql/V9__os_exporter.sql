-- 서버 자원 수집을 SNMP에서 OS exporter(node_exporter, windows_exporter)로 바꿉니다. (PostgreSQL)
--
-- 기존 SNMP 설정(community 암호문 포함)과 고른 리소스는 옮기지 않고 지웁니다. (요구사항 0006)
-- 서버는 남고 OS는 Linux로 시작합니다. 사용자가 OS를 확인하고 수집을 새로 설정합니다.
--
-- mariadb/V9__os_exporter.sql과 논리 Schema가 같아야 합니다.

ALTER TABLE server_targets
    ADD COLUMN IF NOT EXISTS os VARCHAR(10) NOT NULL DEFAULT 'LINUX';

DELETE FROM monitored_resources;

ALTER TABLE monitored_resources
    DROP COLUMN IF EXISTS snmp_index;

DROP TABLE IF EXISTS server_snmp_configs;

-- 서버당 0~1행입니다. 행이 없으면 "설정 안 함"입니다.
-- 허용 범위(포트 1~65535, 수집 주기 10~240초)는 요청 DTO에서 검증합니다.
CREATE TABLE IF NOT EXISTS server_exporter_configs (
    id                      BIGSERIAL    NOT NULL,
    server_id               BIGINT       NOT NULL,
    enabled                 BOOLEAN      NOT NULL DEFAULT FALSE,
    port                    INT          NOT NULL,
    scrape_interval_seconds INT          NOT NULL DEFAULT 15,
    created_at              TIMESTAMP(6) NOT NULL,
    updated_at              TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_server_exporter_configs PRIMARY KEY (id),
    CONSTRAINT uk_server_exporter_configs_server UNIQUE (server_id),
    CONSTRAINT fk_server_exporter_configs_server FOREIGN KEY (server_id)
        REFERENCES server_targets (id) ON DELETE CASCADE
);
