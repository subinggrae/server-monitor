-- Monitoring Check를 Server Target에서 분리합니다. (PostgreSQL)
--
-- 하나의 Check가 여러 서버의 주소를 확인할 수 있습니다.
--
--   전자결재 Health (HTTP)
--     ├─ http://10.0.0.11:8080/actuator/health
--     ├─ http://10.0.0.12:8080/actuator/health
--     └─ http://10.0.0.13:8080/actuator/health
--
-- 이 Check를 서버 한 대(10.0.0.11)의 하위 항목으로 두면 의미가 맞지 않습니다.
-- 나머지 두 주소는 그 서버와 관계가 없기 때문입니다.
--
-- 그래서 Blackbox Check와 Server를 서로 다른 도메인으로 분리합니다.
--
--   Monitoring Check  →  Blackbox 기반 URL / Port / Ping 확인
--   Server Target     →  향후 SNMP 기반 CPU / Memory / Storage 확인
--
-- 기존 Check와 Server는 모두 그대로 남습니다. 둘의 연결만 사라집니다.
--
-- mariadb/V4__decouple_checks_from_servers.sql과 논리 Schema가 같아야 합니다.

-- Server에 있던 environment를 Check가 직접 가집니다.
ALTER TABLE monitoring_checks ADD COLUMN environment VARCHAR(50) NULL;
ALTER TABLE monitoring_checks ADD COLUMN description VARCHAR(500) NULL;

-- 기존 Check는 소속 서버의 environment를 물려받습니다.
-- 이렇게 해야 운영자가 보던 분류가 그대로 유지됩니다.
UPDATE monitoring_checks c
SET environment = s.environment
FROM server_targets s
WHERE s.id = c.server_id;

-- 연결된 서버가 없던 Check가 있다면 기본값을 넣습니다.
UPDATE monitoring_checks SET environment = 'Other' WHERE environment IS NULL;

ALTER TABLE monitoring_checks ALTER COLUMN environment SET NOT NULL;

-- Server와의 연결을 끊습니다.
-- Server를 지워도 Check는 남아야 하므로 FK 자체를 제거합니다.
ALTER TABLE monitoring_checks DROP CONSTRAINT fk_monitoring_checks_server;
DROP INDEX IF EXISTS ix_monitoring_checks_server_id;
ALTER TABLE monitoring_checks DROP COLUMN server_id;
