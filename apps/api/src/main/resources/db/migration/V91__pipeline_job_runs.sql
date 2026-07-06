-- 스케줄 파이프라인 잡(가격/다나와/트렌드/릴리스 스캔/보존 정리)의 실행 이력.
-- 기존에는 결과가 서버 로그 한 줄뿐이라 잡이 3일 연속 죽어도 관리자 화면 어디에도 흔적이
-- 없었다(감사 O4). 실행마다 결과 요약·소요시간·실패 사유를 기록해 관리자 UI에서 조회한다.
CREATE TABLE pipeline_job_runs (
  id BIGSERIAL PRIMARY KEY,
  public_id UUID NOT NULL DEFAULT gen_random_uuid(),
  job_name TEXT NOT NULL,
  trigger_type TEXT NOT NULL DEFAULT 'SCHEDULED',
  status TEXT NOT NULL,
  result_summary JSONB,
  error_summary TEXT,
  started_at TIMESTAMPTZ NOT NULL,
  finished_at TIMESTAMPTZ,
  duration_ms BIGINT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE pipeline_job_runs
  ADD CONSTRAINT pipeline_job_runs_status_check
  CHECK (status IN ('SUCCEEDED', 'FAILED', 'SKIPPED_FROZEN'));

CREATE UNIQUE INDEX pipeline_job_runs_public_id_idx ON pipeline_job_runs (public_id);
CREATE INDEX pipeline_job_runs_job_name_created_idx ON pipeline_job_runs (job_name, created_at DESC);
CREATE INDEX pipeline_job_runs_created_idx ON pipeline_job_runs (created_at DESC);
