-- Run against the BuildGraph PostgreSQL database after Flyway migration.
-- Example:
-- docker exec -i buildgraph-postgres psql -U buildgraph -d buildgraph < tools/check_benchmark_seed_quality.sql

\echo '1. ACTIVE parts without any benchmark row'
SELECT p.category, p.public_id, p.name
FROM parts p
LEFT JOIN benchmark_summaries b
  ON b.part_id = p.id
 AND b.deleted_at IS NULL
WHERE p.status = 'ACTIVE'
  AND p.deleted_at IS NULL
  AND b.id IS NULL
ORDER BY p.category, p.name;

\echo '2. Latest normalized-fit-v1 rows with missing metadata.sourceUrl'
WITH latest AS (
  SELECT DISTINCT ON (b.part_id)
         b.part_id,
         b.benchmark_key,
         b.score,
         b.metadata
  FROM benchmark_summaries b
  WHERE b.deleted_at IS NULL
  ORDER BY b.part_id, b.created_at DESC, b.id DESC
)
SELECT p.category, p.public_id, p.name, latest.benchmark_key
FROM parts p
JOIN latest ON latest.part_id = p.id
WHERE p.status = 'ACTIVE'
  AND p.deleted_at IS NULL
  AND latest.benchmark_key LIKE 'normalized-fit-v1:%'
  AND coalesce(latest.metadata->>'sourceUrl', '') = ''
ORDER BY p.category, p.name;

\echo '3. Latest normalized-fit-v1 rows with invalid score'
WITH latest AS (
  SELECT DISTINCT ON (b.part_id)
         b.part_id,
         b.benchmark_key,
         b.score
  FROM benchmark_summaries b
  WHERE b.deleted_at IS NULL
  ORDER BY b.part_id, b.created_at DESC, b.id DESC
)
SELECT p.category, p.public_id, p.name, latest.benchmark_key, latest.score
FROM parts p
JOIN latest ON latest.part_id = p.id
WHERE p.status = 'ACTIVE'
  AND p.deleted_at IS NULL
  AND latest.benchmark_key LIKE 'normalized-fit-v1:%'
  AND (latest.score IS NULL OR latest.score < 0 OR latest.score > 100)
ORDER BY p.category, p.name;

\echo '4. Coverage summary by category'
SELECT p.category,
       count(*) AS active_parts,
       count(b.id) FILTER (WHERE b.benchmark_key LIKE 'normalized-fit-v1:%') AS normalized_fit_rows
FROM parts p
LEFT JOIN benchmark_summaries b
  ON b.part_id = p.id
 AND b.deleted_at IS NULL
 AND b.benchmark_key LIKE 'normalized-fit-v1:%'
WHERE p.status = 'ACTIVE'
  AND p.deleted_at IS NULL
GROUP BY p.category
ORDER BY p.category;

\echo '5. ACTIVE CPU/GPU normalized rows without public raw benchmark metadata'
WITH latest AS (
  SELECT DISTINCT ON (b.part_id)
         b.part_id,
         b.benchmark_key,
         b.metadata
  FROM benchmark_summaries b
  WHERE b.deleted_at IS NULL
  ORDER BY b.part_id, b.created_at DESC, b.id DESC
)
SELECT p.category, p.public_id, p.name, latest.benchmark_key
FROM parts p
JOIN latest ON latest.part_id = p.id
WHERE p.status = 'ACTIVE'
  AND p.deleted_at IS NULL
  AND p.category IN ('CPU', 'GPU')
  AND latest.benchmark_key LIKE 'normalized-fit-v1:%'
  AND (
    coalesce(latest.metadata->>'rawBenchmarkCoverage', '') <> 'PUBLIC_RAW_BENCHMARK_SEEDED'
    OR coalesce(jsonb_typeof(latest.metadata->'rawBenchmarks'), '') <> 'array'
    OR CASE
         WHEN jsonb_typeof(latest.metadata->'rawBenchmarks') = 'array'
           THEN jsonb_array_length(latest.metadata->'rawBenchmarks') = 0
         ELSE true
       END
  )
ORDER BY p.category, p.name;

\echo '6. Public raw benchmark entries with invalid numeric score'
WITH latest AS (
  SELECT DISTINCT ON (b.part_id)
         b.part_id,
         b.benchmark_key,
         b.metadata
  FROM benchmark_summaries b
  WHERE b.deleted_at IS NULL
  ORDER BY b.part_id, b.created_at DESC, b.id DESC
), expanded AS (
  SELECT p.category,
         p.public_id,
         p.name,
         latest.benchmark_key,
         raw_entry
  FROM parts p
  JOIN latest ON latest.part_id = p.id
  CROSS JOIN LATERAL jsonb_array_elements(coalesce(latest.metadata->'rawBenchmarks', '[]'::jsonb)) AS raw_entry
  WHERE p.status = 'ACTIVE'
    AND p.deleted_at IS NULL
    AND p.category IN ('CPU', 'GPU')
    AND latest.benchmark_key LIKE 'normalized-fit-v1:%'
)
SELECT category, public_id, name, benchmark_key, raw_entry
FROM expanded
WHERE coalesce(raw_entry->>'score', '') !~ '^[0-9]+([.][0-9]+)?$'
   OR (raw_entry->>'score')::numeric <= 0
ORDER BY category, name;
