DROP INDEX IF EXISTS ux_quote_draft_items_active_category;

CREATE UNIQUE INDEX IF NOT EXISTS ux_quote_draft_items_active_single_category
  ON quote_draft_items(quote_draft_id, category)
  WHERE deleted_at IS NULL
    AND category IN ('CPU', 'GPU', 'MOTHERBOARD', 'PSU', 'CASE', 'COOLER');

CREATE UNIQUE INDEX IF NOT EXISTS ux_quote_draft_items_active_part
  ON quote_draft_items(quote_draft_id, part_id)
  WHERE deleted_at IS NULL;
