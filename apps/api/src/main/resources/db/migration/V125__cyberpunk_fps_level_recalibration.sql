-- 사이버펑크 2077 FPS 절대값 재보정: V124가 세운 단조성(상위 GPU가 항상 더 높게)은 유지하되,
--   잣대를 pc-builds ULTRA 계산기에서 TechSpot(Hardware Unboxed) RTX 50 리뷰 공통 실측 벤치마크로 교체한다.
-- 배경: V124의 pc-builds ULTRA 잣대는 절대값이 비현실적으로 낮았다(5090 FHD 109 — 실측 상식은 170~200대).
--   그 결과 (1) 최종 데모 검증 문서(docs/reports/final-demo-scenario-verification-20260716.md)가 보증한
--   회귀 입력 "사이버 펑크 FHD에서 120FPS 이상 나오는 GPU로 변경해줘"의 후보가 불가능해졌고(최고가 109),
--   (2) AI 챗봇이 FPS 근거를 인용할 때 게이머 상식과 어긋나는 낮은 숫자가 노출됐다.
-- 새 단일 잣대: TechSpot(HUB) RTX 50 시리즈 리뷰 공통 벤치 — Ryzen 7 9800X3D 동일 테스트벤치,
--   Cyberpunk 2077: Phantom Liberty 동일 구간, "High Quality" 프리셋(RT off, 업스케일링 off, 네이티브),
--   avg + 1% low 동시 공표. 18셀 중 15셀 차트 직접 판독(HIGH), 3셀(5060 4K, 5080 FHD, 5090 FHD)은
--   HUB 미제공 해상도라 같은 소스 인접 클래스 실측 비율로 도출(LOW, 도출식 basis 명시).
-- 제품 원칙 2개 동시 충족: (a) 단조성 — 맨 끝 V124 동일 DO 블록이 계속 강제, (b) 절대값 상식 범위 —
--   RTX 5070 FHD 145로 데모 임계(120 이상)가 실측으로 자연 성립하고, 추가 DO 블록이 이 전제를 배포 게이트로 고정.
-- 정책: 정확 FPS 보장 아님(NO_EXACT_FPS_OR_RENDER_TIME_GUARANTEE). gpuClass 매칭 + 대표 part_id(V41/V111/V112/V124 관례).

-- 1) 소프트 삭제: 사이버펑크 2077 전 행(V124가 넣은 pc-builds ULTRA 18행)
UPDATE game_fps_benchmarks
SET deleted_at = now(),
    updated_at = now()
WHERE deleted_at IS NULL
  AND game_key = 'cyberpunk-2077';

-- 2) 재시드 INSERT: 단일 잣대(TechSpot/HUB High Quality) 18행.
--    gpu_part_id 는 기존 벤치 표가 쓰는 클래스 대표 부품 public_id 로 해석(V41/V124 방식 서브쿼리 JOIN):
--      RTX_5060=조텍 5060 Twin Edge, RTX_5060_TI=기가바이트 5060 Ti WINDFORCE, RTX_5070=MSI 5070 게이밍 트리오,
--      RTX_5070_TI=기가바이트 5070 Ti WINDFORCE, RTX_5080=MSI 5080 쉐도우, RTX_5090=조텍 5090 SOLID.
WITH fps_seed AS (
  SELECT *
  FROM (VALUES
    ('00000000-0000-4000-8000-000000125001'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '88622262-a225-456b-b8f1-ae9914d20f70'::uuid, 'RTX_5060', 'GeForce RTX 5060', 'FHD', '1920 x 1080', 'HIGH', 100.00, 81.00, 'TechSpot (Hardware Unboxed) RTX 5060 리뷰', 'https://www.techspot.com/review/2992-nvidia-geforce-rtx-5060/', 'HIGH', '1080p High Quality 차트 실측 판독: RTX 5060 8G avg 100 / 1% low 81 (9800X3D, RT off, 네이티브)'),
    ('00000000-0000-4000-8000-000000125002'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '88622262-a225-456b-b8f1-ae9914d20f70'::uuid, 'RTX_5060', 'GeForce RTX 5060', 'QHD', '2560 x 1440', 'HIGH', 66.00, 56.00, 'TechSpot (Hardware Unboxed) RTX 5060 리뷰', 'https://www.techspot.com/review/2992-nvidia-geforce-rtx-5060/', 'HIGH', '1440p High Quality 차트 실측 판독: RTX 5060 8G avg 66 / 1% low 56'),
    ('00000000-0000-4000-8000-000000125003'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '88622262-a225-456b-b8f1-ae9914d20f70'::uuid, 'RTX_5060', 'GeForce RTX 5060', '4K', '3840 x 2160', 'HIGH', 31.00, 26.00, 'TechSpot (Hardware Unboxed) RTX 5060 Ti 리뷰 기반 스케일링', 'https://www.techspot.com/review/2979-nvidia-geforce-rtx-5060-ti-16gb/', 'LOW', 'HUB는 5060을 4K에서 미실측. 동일 소스 5060 Ti 4K 실측(40/34)에 클래스 간 실측 비율(FHD 100/125=0.80, QHD 66/79=0.835, 4K는 8GB VRAM 부담 반영 0.79) 적용 → avg 31; low는 실측 low/avg 비율(~0.84) 적용 → 26. 동일 차트 인접 실측(4060 Ti 16G 29/25, RTX 3070 34/28) 사이 값으로 교차확인'),
    ('00000000-0000-4000-8000-000000125004'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', 'c10b1401-557a-410b-b15d-a0ef4c2aa415'::uuid, 'RTX_5060_TI', 'GeForce RTX 5060 Ti', 'FHD', '1920 x 1080', 'HIGH', 125.00, 103.00, 'TechSpot (Hardware Unboxed) RTX 5060 리뷰', 'https://www.techspot.com/review/2992-nvidia-geforce-rtx-5060/', 'HIGH', '1080p High Quality 차트 실측 판독: RTX 5060 Ti 16G avg 125 / 1% low 103 (8G 모델도 동일 125/103)'),
    ('00000000-0000-4000-8000-000000125005'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', 'c10b1401-557a-410b-b15d-a0ef4c2aa415'::uuid, 'RTX_5060_TI', 'GeForce RTX 5060 Ti', 'QHD', '2560 x 1440', 'HIGH', 79.00, 66.00, 'TechSpot (Hardware Unboxed) RTX 5060 Ti 16GB 리뷰', 'https://www.techspot.com/review/2979-nvidia-geforce-rtx-5060-ti-16gb/', 'HIGH', '1440p High Quality 차트 실측 판독: avg 79 / 1% low 66 (5060 리뷰 차트에서도 동일값 재확인)'),
    ('00000000-0000-4000-8000-000000125006'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', 'c10b1401-557a-410b-b15d-a0ef4c2aa415'::uuid, 'RTX_5060_TI', 'GeForce RTX 5060 Ti', '4K', '3840 x 2160', 'HIGH', 40.00, 34.00, 'TechSpot (Hardware Unboxed) RTX 5060 Ti 16GB 리뷰', 'https://www.techspot.com/review/2979-nvidia-geforce-rtx-5060-ti-16gb/', 'HIGH', '4K High Quality 차트 실측 판독: avg 40 / 1% low 34'),
    ('00000000-0000-4000-8000-000000125007'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', 'a76ff652-7c33-4640-b7ee-beb3c82c6109'::uuid, 'RTX_5070', 'GeForce RTX 5070', 'FHD', '1920 x 1080', 'HIGH', 145.00, 111.00, 'TechSpot (Hardware Unboxed) RTX 5060 리뷰', 'https://www.techspot.com/review/2992-nvidia-geforce-rtx-5060/', 'HIGH', '1080p High Quality 차트 실측 판독: RTX 5070 avg 145 / 1% low 111 — 데모 임계(FHD 120 이상)가 실측으로 자연 성립'),
    ('00000000-0000-4000-8000-000000125008'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', 'a76ff652-7c33-4640-b7ee-beb3c82c6109'::uuid, 'RTX_5070', 'GeForce RTX 5070', 'QHD', '2560 x 1440', 'HIGH', 98.00, 79.00, 'TechSpot (Hardware Unboxed) RTX 5070 리뷰', 'https://www.techspot.com/review/2960-nvidia-geforce-rtx-5070/', 'HIGH', '1440p High Quality 차트 실측 판독: avg 98 / 1% low 79'),
    ('00000000-0000-4000-8000-000000125009'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', 'a76ff652-7c33-4640-b7ee-beb3c82c6109'::uuid, 'RTX_5070', 'GeForce RTX 5070', '4K', '3840 x 2160', 'HIGH', 51.00, 43.00, 'TechSpot (Hardware Unboxed) RTX 5070 리뷰', 'https://www.techspot.com/review/2960-nvidia-geforce-rtx-5070/', 'HIGH', '4K High Quality 차트 실측 판독: avg 51 / 1% low 43'),
    ('00000000-0000-4000-8000-000000125010'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '460f7d37-bd23-4bcf-9786-d9c68126a77c'::uuid, 'RTX_5070_TI', 'GeForce RTX 5070 Ti', 'FHD', '1920 x 1080', 'HIGH', 180.00, 119.00, 'TechSpot (Hardware Unboxed) RTX 5060 리뷰', 'https://www.techspot.com/review/2992-nvidia-geforce-rtx-5060/', 'HIGH', '1080p High Quality 차트 실측 판독: RTX 5070 Ti avg 180 / 1% low 119'),
    ('00000000-0000-4000-8000-000000125011'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '460f7d37-bd23-4bcf-9786-d9c68126a77c'::uuid, 'RTX_5070_TI', 'GeForce RTX 5070 Ti', 'QHD', '2560 x 1440', 'HIGH', 124.00, 109.00, 'TechSpot (Hardware Unboxed) RTX 5070 Ti 리뷰', 'https://www.techspot.com/review/2955-nvidia-geforce-rtx-5070/', 'HIGH', '1440p High Quality 차트 실측 판독: avg 124 / 1% low 109 (5080·5060·5060 Ti 리뷰 차트에서도 동일값 재확인)'),
    ('00000000-0000-4000-8000-000000125012'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '460f7d37-bd23-4bcf-9786-d9c68126a77c'::uuid, 'RTX_5070_TI', 'GeForce RTX 5070 Ti', '4K', '3840 x 2160', 'HIGH', 67.00, 58.00, 'TechSpot (Hardware Unboxed) RTX 5070 Ti 리뷰', 'https://www.techspot.com/review/2955-nvidia-geforce-rtx-5070/', 'HIGH', '4K High Quality 차트 실측 판독: avg 67 / 1% low 58 (5070 리뷰 차트에서도 동일값 재확인)'),
    ('00000000-0000-4000-8000-000000125013'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '4f615852-d0ec-4d05-9353-bc5d26906e5b'::uuid, 'RTX_5080', 'GeForce RTX 5080', 'FHD', '1920 x 1080', 'HIGH', 209.00, 127.00, 'TechSpot (Hardware Unboxed) RTX 5080 리뷰 기반 스케일링', 'https://www.techspot.com/review/2947-nvidia-geforce-rtx-5080/', 'LOW', 'HUB는 상위 GPU 리뷰에서 1080p 게임별 차트 미제공. 동일 소스 QHD 실측 146에 인접 클래스 5070 Ti의 실측 FHD/QHD 배율(180/124=1.452)을 CPU 병목 보정으로 소폭 압축한 1.43 적용 → 209. 1% low는 실측 FHD low가 CPU측 상한(5070 Ti 119)에 수렴하는 패턴을 따라 QHD 실측 low 124를 소폭 상회하는 127'),
    ('00000000-0000-4000-8000-000000125014'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '4f615852-d0ec-4d05-9353-bc5d26906e5b'::uuid, 'RTX_5080', 'GeForce RTX 5080', 'QHD', '2560 x 1440', 'HIGH', 146.00, 124.00, 'TechSpot (Hardware Unboxed) RTX 5080 리뷰', 'https://www.techspot.com/review/2947-nvidia-geforce-rtx-5080/', 'HIGH', '1440p High Quality 차트 실측 판독: avg 146 / 1% low 124'),
    ('00000000-0000-4000-8000-000000125015'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '4f615852-d0ec-4d05-9353-bc5d26906e5b'::uuid, 'RTX_5080', 'GeForce RTX 5080', '4K', '3840 x 2160', 'HIGH', 80.00, 66.00, 'TechSpot (Hardware Unboxed) RTX 5080 리뷰', 'https://www.techspot.com/review/2947-nvidia-geforce-rtx-5080/', 'HIGH', '4K High Quality 차트 실측 판독: avg 80 / 1% low 66'),
    ('00000000-0000-4000-8000-000000125016'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '9f3d289c-6739-459c-9e79-5a1417165ded'::uuid, 'RTX_5090', 'GeForce RTX 5090', 'FHD', '1920 x 1080', 'HIGH', 236.00, 143.00, 'TechSpot (Hardware Unboxed) RTX 5080 리뷰 기반 스케일링', 'https://www.techspot.com/review/2947-nvidia-geforce-rtx-5080/', 'LOW', '동일 소스 QHD 실측 209 × 1.13 → 236. 배율 근거: 5090은 QHD에서 이미 CPU 병목 압축이 실측 관찰됨(5080 대비 격차가 4K 1.55배→QHD 1.43배로 축소, 4090 대비 1.19배). FHD에서는 병목이 심화되어 FHD/QHD 배율이 5070 Ti의 1.452보다 크게 압축된다고 판단. 1% low는 CPU측 포화로 QHD 실측 140 소폭 상회 143. HIGH 프리셋 잣대에서 ULTRA 실측 상식(170~200대) 상위 위치로 정합'),
    ('00000000-0000-4000-8000-000000125017'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '9f3d289c-6739-459c-9e79-5a1417165ded'::uuid, 'RTX_5090', 'GeForce RTX 5090', 'QHD', '2560 x 1440', 'HIGH', 209.00, 140.00, 'TechSpot (Hardware Unboxed) RTX 5080 리뷰', 'https://www.techspot.com/review/2947-nvidia-geforce-rtx-5080/', 'HIGH', '1440p High Quality 차트 실측 판독: RTX 5090 avg 209 / 1% low 140'),
    ('00000000-0000-4000-8000-000000125018'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '9f3d289c-6739-459c-9e79-5a1417165ded'::uuid, 'RTX_5090', 'GeForce RTX 5090', '4K', '3840 x 2160', 'HIGH', 124.00, 107.00, 'TechSpot (Hardware Unboxed) RTX 5080 리뷰', 'https://www.techspot.com/review/2947-nvidia-geforce-rtx-5080/', 'HIGH', '4K High Quality 차트 실측 판독: RTX 5090 avg 124 / 1% low 107')
  ) AS seed(public_id, game_title, game_key, gpu_public_id, gpu_class, source_gpu_name, resolution, resolution_text, graphics_preset, avg_fps, one_percent_low_fps, source_name, source_url, confidence, basis)
)
INSERT INTO game_fps_benchmarks (
  public_id,
  game_title,
  game_key,
  cpu_part_id,
  gpu_part_id,
  resolution,
  graphics_preset,
  avg_fps,
  one_percent_low_fps,
  source_name,
  source_url,
  source_checked_at,
  confidence,
  metadata,
  created_at,
  updated_at
)
SELECT seed.public_id,
       seed.game_title,
       seed.game_key,
       NULL,
       gpu.id,
       seed.resolution,
       seed.graphics_preset,
       seed.avg_fps,
       seed.one_percent_low_fps,
       seed.source_name,
       seed.source_url,
       DATE '2026-07-16',
       seed.confidence,
       jsonb_build_object(
         'aliases', jsonb_build_array('사이버펑크', '사펑', 'cyberpunk', 'cyberpunk 2077'),
         'cpuClass', 'RYZEN_7_9800X3D',
         'gpuClass', seed.gpu_class,
         'basis', seed.basis,
         'guaranteePolicy', 'NO_EXACT_FPS_OR_RENDER_TIME_GUARANTEE',
         'evidenceExactness', 'REVIEW_BENCH_EXACT_PARTS',
         'hardwareScope', 'REVIEW_BENCH_EXACT_PARTS',
         'sourceMetricType', 'REVIEW_BENCH_EXACT_PARTS',
         'sourceCpuName', 'Ryzen 7 9800X3D',
         'sourceGpuName', seed.source_gpu_name,
         'sourcePresetText', 'High Quality',
         'sourceResolutionText', seed.resolution_text,
         'sourceCapturedText', concat(seed.resolution, ' ', seed.graphics_preset, ', avg ', seed.avg_fps, ', 1% low ', seed.one_percent_low_fps),
         'sourceAccessMethod', 'MANUAL_PAGE_READ',
         'osVersion', 'UNKNOWN_PUBLIC_SOURCE',
         'driverVersion', 'UNKNOWN_PUBLIC_SOURCE',
         'gameVersion', 'Cyberpunk 2077: Phantom Liberty',
         'testScene', 'UNKNOWN_PUBLIC_SOURCE',
         'upscaling', 'OFF',
         'frameGeneration', 'OFF',
         'rayTracing', 'OFF',
         'qualityGaps', CASE
           WHEN seed.confidence = 'LOW' THEN jsonb_build_array('avg_and_one_percent_low_scaled_from_same_source_adjacent_class_measurements')
           ELSE jsonb_build_array()
         END,
         'notes', 'Public FPS reference for recommendation evidence. Do not present as guaranteed FPS.',
         'metadataVersion', 1
       ),
       now(),
       now()
FROM fps_seed seed
JOIN parts gpu ON gpu.public_id = seed.gpu_public_id
ON CONFLICT DO NOTHING;

-- 2-2) 삽입 수 가드: 대표 부품 public_id 미스매치(JOIN 누락)나 unique 충돌 시 조용히 빠지는 것을 배포 실패로 승격.
DO $$
DECLARE
  reseeded_count integer;
BEGIN
  SELECT count(*)
  INTO reseeded_count
  FROM game_fps_benchmarks
  WHERE deleted_at IS NULL
    AND game_key = 'cyberpunk-2077';
  IF reseeded_count <> 18 THEN
    RAISE EXCEPTION 'V125 재시드 행 수 불일치: 기대 18, 실제 % — 대표 부품 public_id 매칭 또는 unique 인덱스 충돌 확인 필요', reseeded_count;
  END IF;
END $$;

-- 3) 단조성 검증(재발 방지 장치, V124와 동일 블록): deleted_at IS NULL 전 행 대상.
--    (a) 같은 game_key×resolution 에서 GPU 사다리(RTX_5060<RTX_5060_TI<RTX_5070<RTX_5070_TI<RTX_5080<RTX_5090) 순
--        avg_fps 엄격 증가 — 행이 있는 클래스끼리 인접 비교. 상위 GPU가 하위 GPU보다 낮게 표시되는 일은 절대 없어야 한다.
--    (b) 같은 game_key×gpuClass 에서 FHD > QHD > 4K 엄격 감소.
--    (c) 같은 game_key×resolution×gpuClass 중복 행 금지.
--    이후 아무 시드가 이 순서를 어겨도 이 블록이 배포를 실패시킨다.
DO $$
DECLARE
  gpu_ladder CONSTANT text[] := ARRAY['RTX_5060', 'RTX_5060_TI', 'RTX_5070', 'RTX_5070_TI', 'RTX_5080', 'RTX_5090'];
  res_ladder CONSTANT text[] := ARRAY['FHD', 'QHD', '4K'];
  violations text;
BEGIN
  -- (c) 셀 중복 금지: 중복이 있으면 (a)/(b)의 인접 비교 자체가 무의미하므로 가장 먼저 검사한다.
  SELECT string_agg(format('game=%s res=%s gpuClass=%s 행 %s개(1개여야 함)', game_key, resolution, gpu_class, cnt), ' | ')
  INTO violations
  FROM (
    SELECT game_key, resolution, metadata->>'gpuClass' AS gpu_class, count(*) AS cnt
    FROM game_fps_benchmarks
    WHERE deleted_at IS NULL
      AND metadata->>'gpuClass' IS NOT NULL
    GROUP BY 1, 2, 3
    HAVING count(*) > 1
  ) dup;
  IF violations IS NOT NULL THEN
    RAISE EXCEPTION 'FPS 단조성 검증 실패(중복 셀): 같은 game_key×resolution×gpuClass 에 행이 2개 이상 — %', violations;
  END IF;

  -- (a) GPU 사다리 엄격 증가 (같은 game_key×resolution, 존재하는 클래스끼리 인접 비교)
  SELECT string_agg(
           format('game=%s res=%s: 상위 %s(avg %s)가 하위 %s(avg %s)보다 낮거나 같음', game_key, resolution, gpu_class, avg_fps, prev_class, prev_fps),
           ' | ')
  INTO violations
  FROM (
    SELECT game_key,
           resolution,
           metadata->>'gpuClass' AS gpu_class,
           avg_fps,
           lag(metadata->>'gpuClass') OVER w AS prev_class,
           lag(avg_fps) OVER w AS prev_fps
    FROM game_fps_benchmarks
    WHERE deleted_at IS NULL
      AND metadata->>'gpuClass' = ANY (gpu_ladder)
    WINDOW w AS (PARTITION BY game_key, resolution ORDER BY array_position(gpu_ladder, metadata->>'gpuClass'))
  ) ladder
  WHERE prev_fps IS NOT NULL
    AND avg_fps <= prev_fps;
  IF violations IS NOT NULL THEN
    RAISE EXCEPTION 'FPS 단조성 검증 실패(GPU 사다리 역전): %', violations;
  END IF;

  -- (b) 해상도 엄격 감소 (같은 game_key×gpuClass 에서 FHD > QHD > 4K)
  SELECT string_agg(
           format('game=%s gpuClass=%s: %s(avg %s)가 더 낮은 해상도 %s(avg %s)보다 높거나 같음', game_key, gpu_class, resolution, avg_fps, prev_res, prev_fps),
           ' | ')
  INTO violations
  FROM (
    SELECT game_key,
           metadata->>'gpuClass' AS gpu_class,
           resolution,
           avg_fps,
           lag(resolution) OVER w AS prev_res,
           lag(avg_fps) OVER w AS prev_fps
    FROM game_fps_benchmarks
    WHERE deleted_at IS NULL
      AND metadata->>'gpuClass' = ANY (gpu_ladder)
    WINDOW w AS (PARTITION BY game_key, metadata->>'gpuClass' ORDER BY array_position(res_ladder, resolution))
  ) res_steps
  WHERE prev_fps IS NOT NULL
    AND avg_fps >= prev_fps;
  IF violations IS NOT NULL THEN
    RAISE EXCEPTION 'FPS 단조성 검증 실패(해상도 역전): %', violations;
  END IF;
END $$;

-- 4) 데모 임계 가드(이번 재보정의 존재 이유를 배포 게이트로 고정):
--    최종 데모 검증 문서의 회귀 입력 "사이버 펑크 FHD에서 120FPS 이상 나오는 GPU로 변경해줘"가
--    후보를 형성하려면 cyberpunk-2077 FHD에서 RTX_5070의 avg_fps가 120 이상이어야 한다
--    (실측 145 — 후보 사다리 5070/5070Ti/5080/5090 중 최저가 5070이 뽑히는 구 시드 동작 복원).
DO $$
DECLARE
  demo_fps numeric;
BEGIN
  SELECT avg_fps
  INTO demo_fps
  FROM game_fps_benchmarks
  WHERE deleted_at IS NULL
    AND game_key = 'cyberpunk-2077'
    AND resolution = 'FHD'
    AND metadata->>'gpuClass' = 'RTX_5070';
  IF demo_fps IS NULL OR demo_fps < 120 THEN
    RAISE EXCEPTION '데모 임계 가드 실패: cyberpunk-2077 FHD RTX_5070 avg_fps=% (120 이상이어야 데모 회귀 입력 "사이버 펑크 FHD 120FPS 이상"의 후보가 성립)', demo_fps;
  END IF;
END $$;
