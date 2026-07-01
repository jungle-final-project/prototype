-- RAG seed for explicit high-end part constraints.
-- Embeddings are intentionally not generated in Flyway; use the admin backfill endpoint after migration.

WITH seed_rag(public_id, source_id, chunk_text, summary, score, metadata) AS (
  VALUES
    (
      '00000000-0000-4000-8000-000000015201',
      'requirement-rule-explicit-gpu-class-hard-constraint',
      'If a user explicitly asks for a GPU class such as RTX 5090, 5090 글카, RTX 5080, RTX 5070, or similar named graphics-card class, extract requiredGpuClasses with the normalized GPU class and set hardConstraintPolicy to MUST_INCLUDE. Do not silently downgrade the GPU because of the default budget. If the user did not provide a budget, keep budget null and use OPEN_BUDGET for the recommendation stage.',
      'Requirement parse rule: explicit GPU class wording is a hard include constraint.',
      0.99000,
      '{"sourceType":"INTERNAL_RULE","purpose":"REQUIREMENT_PARSE","title":"Explicit GPU class hard constraint parse rule","relatedFields":["requiredGpuClasses","requiredPartKeywords","hardConstraintPolicy","budgetPolicy"],"relatedCategories":["GPU"],"metadataVersion":3}'
    ),
    (
      '00000000-0000-4000-8000-000000015202',
      'requirement-counterexample-explicit-gpu-with-user-budget',
      'If a user says RTX 5090 넣고 300만원 이하, 300만원으로 5090 PC, or a similar concrete budget plus explicit GPU class, preserve USER_BUDGET and requiredGpuClasses. The recommendation should include the requested GPU class and attach HARD_CONSTRAINT_OVER_BUDGET when the total cannot fit the budget, instead of replacing the requested GPU with a lower class.',
      'Requirement parse counterexample: explicit GPU plus budget keeps the hard GPU constraint and surfaces over-budget warning.',
      0.98500,
      '{"sourceType":"INTERNAL_RULE","purpose":"REQUIREMENT_PARSE","title":"Explicit GPU class with concrete budget counterexample","relatedFields":["budget","budgetPolicy","requiredGpuClasses","hardConstraintPolicy"],"relatedCategories":["GPU"],"metadataVersion":3}'
    ),
    (
      '00000000-0000-4000-8000-000000015203',
      'build-rule-hard-gpu-class-selection',
      'Build recommendation must apply requiredGpuClasses before price target allocation. If requiredGpuClasses contains RTX_5090 and ACTIVE toolReady RTX_5090 parts exist, every recommended build for that requirement should contain an RTX_5090 GPU. Budget overflow is reported as HARD_CONSTRAINT_OVER_BUDGET, not solved by downgrading the requested GPU.',
      'Build recommendation rule: required GPU class overrides price target selection.',
      0.99000,
      '{"sourceType":"INTERNAL_RULE","purpose":"BUILD_RECOMMEND","title":"Hard GPU class selection rule","relatedFields":["requiredGpuClasses","hardConstraintPolicy"],"relatedCategories":["GPU"],"metadataVersion":3}'
    ),
    (
      '00000000-0000-4000-8000-000000015204',
      'part-spec-rtx-5090-class',
      'RTX_5090 is the normalized GPU class for GeForce RTX 5090 / 5090 글카 / RTX5090 wording. It is a flagship Blackwell GPU class, typically with 32GB GDDR7 VRAM and high power requirements. Use stored parts.attributes and Tool checks for exact product dimensions, price, PSU, and case fit.',
      'Part spec guide: RTX 5090 wording maps to normalized gpuClass RTX_5090.',
      0.98000,
      '{"sourceType":"PART_SPEC","purpose":"BUILD_RECOMMEND","title":"RTX 5090 class guide","relatedFields":["gpuClass","hardwareClass","requiredGpuClasses"],"relatedCategories":["GPU","PSU","CASE"],"metadataVersion":3}'
    )
)
INSERT INTO rag_evidence (
  public_id,
  agent_session_id,
  source_id,
  chunk_text,
  summary,
  score,
  metadata,
  created_at
)
SELECT
  public_id::uuid,
  NULL,
  source_id,
  chunk_text,
  summary,
  score,
  metadata::jsonb,
  now()
FROM seed_rag
ON CONFLICT (public_id) DO UPDATE SET
  agent_session_id = NULL,
  source_id = EXCLUDED.source_id,
  chunk_text = EXCLUDED.chunk_text,
  summary = EXCLUDED.summary,
  score = EXCLUDED.score,
  metadata = EXCLUDED.metadata,
  created_at = rag_evidence.created_at;
