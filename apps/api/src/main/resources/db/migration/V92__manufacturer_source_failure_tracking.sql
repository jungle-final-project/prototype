-- 소스별 연속 실패 추적: 봇 차단(403/429 등)이 반복되는 소스를 자동 PAUSED로 전환해
-- 차단당한 사이트를 계속 두드리지 않고(예의 있는 철수) 관리자에게 가시화한다.
-- 실측: 첫 실스캔에서 GIGABYTE/MSI/ZOTAC가 403/468로 차단됨.
ALTER TABLE manufacturer_sources
  ADD COLUMN consecutive_failures INT NOT NULL DEFAULT 0;

-- 다중 인스턴스에서 같은 크론이 중복 실행될 때 advisory lock 미획득 스킵을 이력에 남기기 위한 상태 추가.
ALTER TABLE pipeline_job_runs
  DROP CONSTRAINT pipeline_job_runs_status_check;

ALTER TABLE pipeline_job_runs
  ADD CONSTRAINT pipeline_job_runs_status_check
  CHECK (status IN ('SUCCEEDED', 'FAILED', 'SKIPPED_FROZEN', 'SKIPPED_LOCKED'));
