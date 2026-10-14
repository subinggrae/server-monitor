-- Check와 SNMP 설정마다 수집 주기를 둡니다. (MariaDB / MySQL)
--
-- Prometheus는 HTTP SD의 __scrape_interval__로 이 값을 받아 대상마다 다른 주기로 수집합니다.
-- 기존 행은 prometheus.yml의 전역 scrape_interval과 같은 15초로 시작합니다.
-- 허용 범위(10~240초)는 요청 DTO에서 검증합니다.
--
-- postgresql/V7__scrape_interval.sql과 논리 Schema가 같아야 합니다.

ALTER TABLE monitoring_checks
    ADD COLUMN scrape_interval_seconds INT NOT NULL DEFAULT 15;

ALTER TABLE server_snmp_configs
    ADD COLUMN scrape_interval_seconds INT NOT NULL DEFAULT 15;
