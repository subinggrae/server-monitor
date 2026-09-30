-- Monitoring Check 하나가 여러 Target을 가질 수 있게 바꿉니다. (MariaDB / MySQL)
--
-- 같은 애플리케이션이 서버 여러 대에서 동작할 때,
-- 사용자는 하나의 Check로 관리하고 싶어 합니다.
--
--   전자결재 Health (HTTP)
--     ├─ http://10.0.0.11:8080/actuator/health
--     ├─ http://10.0.0.12:8080/actuator/health
--     └─ http://10.0.0.13:8080/actuator/health
--
-- 기존에는 monitoring_checks.target / port에 주소 하나만 넣었습니다.
-- 이 Migration은 그 값을 monitoring_check_targets로 옮긴 뒤 기존 column을 지웁니다.
-- 기존 Check의 id와 의미는 그대로 유지됩니다.
--
-- postgresql/V3__monitoring_check_targets.sql과 논리 Schema가 같아야 합니다.

CREATE TABLE monitoring_check_targets (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    check_id   BIGINT       NOT NULL,
    -- ICMP와 TCP는 host 또는 IP, HTTP와 HTTPS는 전체 URL이 들어갑니다.
    target     VARCHAR(500) NOT NULL,
    -- TCP Target에만 사용합니다. 나머지 Type은 NULL입니다.
    port       INT          NULL,
    enabled    TINYINT(1)   NOT NULL DEFAULT 1,
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    -- Check를 지우면 딸린 Target도 함께 지웁니다.
    CONSTRAINT fk_monitoring_check_targets_check FOREIGN KEY (check_id)
        REFERENCES monitoring_checks (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Service Discovery와 Check 상세에서 Check별 Target을 조회합니다.
CREATE INDEX ix_monitoring_check_targets_check_id ON monitoring_check_targets (check_id);

-- 기존 Check마다 Target을 한 개씩 만들어 값을 그대로 옮깁니다.
-- 여기서 빠뜨리면 운영 중인 모니터링 대상이 사라집니다.
INSERT INTO monitoring_check_targets (check_id, target, port, enabled, created_at, updated_at)
SELECT id, target, port, enabled, created_at, updated_at
FROM monitoring_checks;

-- 값을 모두 옮긴 뒤에 기존 column을 지웁니다.
ALTER TABLE monitoring_checks DROP COLUMN target;
ALTER TABLE monitoring_checks DROP COLUMN port;
