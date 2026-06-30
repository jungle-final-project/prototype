-- Reusable RAG chunks use pgvector cosine search after embeddings are backfilled.
CREATE INDEX IF NOT EXISTS idx_rag_evidence_reusable_embedding_hnsw
ON rag_evidence
USING hnsw (embedding vector_cosine_ops)
WHERE agent_session_id IS NULL
  AND embedding IS NOT NULL;
