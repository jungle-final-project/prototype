-- Run against the BuildGraph PostgreSQL database after Flyway migration.
-- Example:
-- docker exec -i buildgraph-postgres psql -U buildgraph -d buildgraph < tools/check_game_fps_seed_quality.sql

\echo '1. Game FPS rows missing required references or values'
SELECT public_id, game_title, resolution, graphics_preset, avg_fps, source_url
FROM game_fps_benchmarks
WHERE deleted_at IS NULL
  AND (
    game_title IS NULL
    OR game_key IS NULL
    OR gpu_part_id IS NULL
    OR resolution IS NULL
    OR graphics_preset IS NULL
    OR avg_fps IS NULL
    OR avg_fps <= 0
    OR coalesce(source_url, '') = ''
  )
ORDER BY game_title, resolution;

\echo '2. Game FPS rows where one_percent_low_fps exceeds avg_fps'
SELECT public_id, game_title, resolution, graphics_preset, avg_fps, one_percent_low_fps
FROM game_fps_benchmarks
WHERE deleted_at IS NULL
  AND one_percent_low_fps IS NOT NULL
  AND one_percent_low_fps > avg_fps
ORDER BY game_title, resolution;

\echo '3. Duplicate active game FPS seed rows'
SELECT game_key, cpu_part_id, gpu_part_id, ram_gb, resolution, graphics_preset, source_name, count(*)
FROM game_fps_benchmarks
WHERE deleted_at IS NULL
GROUP BY game_key, cpu_part_id, gpu_part_id, ram_gb, resolution, graphics_preset, source_name
HAVING count(*) > 1
ORDER BY game_key, resolution;

\echo '4. Seed coverage by game'
SELECT game_title,
       count(*) AS rows,
       min(avg_fps) AS min_avg_fps,
       max(avg_fps) AS max_avg_fps
FROM game_fps_benchmarks
WHERE deleted_at IS NULL
GROUP BY game_title
ORDER BY game_title;

\echo '5. Required game/resolution coverage gaps'
WITH expected_games(game_key) AS (
  VALUES ('pubg'), ('valorant'), ('overwatch-2'), ('lost-ark'), ('cyberpunk-2077')
),
expected_resolutions(resolution) AS (
  VALUES ('FHD'), ('QHD'), ('4K')
),
coverage AS (
  SELECT games.game_key, resolutions.resolution
  FROM expected_games games
  CROSS JOIN expected_resolutions resolutions
)
SELECT coverage.game_key,
       coverage.resolution,
       count(fps.id) AS row_count
FROM coverage
LEFT JOIN game_fps_benchmarks fps
  ON fps.game_key = coverage.game_key
 AND fps.resolution = coverage.resolution
 AND fps.deleted_at IS NULL
GROUP BY coverage.game_key, coverage.resolution
HAVING count(fps.id) = 0
ORDER BY coverage.game_key, coverage.resolution;

\echo '6. Active GPU class coverage gaps'
WITH active_gpu_models AS (
  SELECT coalesce(attributes->>'gpuClass', attributes->>'hardwareClass') AS gpu_class,
         count(*) AS active_parts
  FROM parts
  WHERE category = 'GPU'
    AND status = 'ACTIVE'
    AND deleted_at IS NULL
  GROUP BY coalesce(attributes->>'gpuClass', attributes->>'hardwareClass')
),
fps_gpu_models AS (
  SELECT metadata->>'gpuClass' AS gpu_class,
         count(*) AS fps_rows
  FROM game_fps_benchmarks
  WHERE deleted_at IS NULL
  GROUP BY metadata->>'gpuClass'
)
SELECT active_gpu_models.gpu_class,
       active_gpu_models.active_parts,
       coalesce(fps_gpu_models.fps_rows, 0) AS fps_rows
FROM active_gpu_models
LEFT JOIN fps_gpu_models ON fps_gpu_models.gpu_class = active_gpu_models.gpu_class
WHERE active_gpu_models.gpu_class IS NOT NULL
  AND coalesce(fps_gpu_models.fps_rows, 0) = 0
ORDER BY active_gpu_models.gpu_class;

\echo '7. FPS rows missing audit metadata'
SELECT public_id,
       game_key,
       resolution,
       graphics_preset
FROM game_fps_benchmarks
WHERE deleted_at IS NULL
  AND (
    metadata->>'sourceCapturedText' IS NULL
    OR metadata->>'sourceAccessMethod' IS NULL
    OR metadata->>'evidenceExactness' IS NULL
    OR metadata->>'driverVersion' IS NULL
    OR metadata->>'gameVersion' IS NULL
    OR metadata->>'upscaling' IS NULL
    OR metadata->>'frameGeneration' IS NULL
  )
ORDER BY game_key, resolution;

\echo '8. DB-managed coverage gaps by priority'
SELECT target_type,
       game_key,
       resolution,
       graphics_preset,
       gpu_class,
       cpu_class,
       priority,
       reason
FROM game_fps_coverage_gaps
ORDER BY CASE priority WHEN 'P0' THEN 0 WHEN 'P1' THEN 1 ELSE 2 END,
         target_type,
         game_key,
         resolution NULLS LAST,
         gpu_class NULLS LAST,
         cpu_class NULLS LAST;
