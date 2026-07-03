#!/usr/bin/env python3
"""Export recommendation events into a compact CSV training set.

Usage:
  python tools/export_recommendation_training_data.py --output artifacts/recommendation/training.csv

Requires either psycopg (v3) or psycopg2. The script writes headers even when
there are no rows, so downstream batch jobs can run safely on an empty dataset.
"""

from __future__ import annotations

import argparse
import csv
import os
from pathlib import Path


FIELDS = [
    "event_id",
    "event_type",
    "label_score",
    "source_surface",
    "category",
    "rank_position",
    "part_price",
    "part_status",
    "part_benchmark_score",
    "part_tool_ready",
    "part_has_image",
    "part_has_offer",
    "part_price_age_days",
    "part_has_fps_coverage",
    "category_CPU",
    "category_GPU",
    "category_RAM",
    "category_MOTHERBOARD",
    "category_STORAGE",
    "category_PSU",
    "category_CASE",
    "category_COOLER",
    "build_total_price",
    "created_at",
]


def connect(database_url: str):
    try:
        import psycopg

        return psycopg.connect(database_url)
    except ImportError:
        try:
            import psycopg2

            return psycopg2.connect(database_url)
        except ImportError as exc:
            raise SystemExit("Install psycopg or psycopg2 to export training data.") from exc


def rows(conn, source_surface: str | None):
    sql = """
        SELECT e.public_id::text AS event_id,
               e.event_type,
               e.label_score,
               e.source_surface,
               e.category,
               e.rank_position,
               p.price AS part_price,
               p.status AS part_status,
               bs.score AS part_benchmark_score,
               coalesce((p.attributes->>'toolReady')::boolean, false) AS part_tool_ready,
               (peo.image_url IS NOT NULL) AS part_has_image,
               (peo.offer_url IS NOT NULL) AS part_has_offer,
               CASE
                 WHEN ps.collected_at IS NULL THEN NULL
                 ELSE extract(epoch FROM (now() - ps.collected_at)) / 86400.0
               END AS part_price_age_days,
               EXISTS (
                 SELECT 1
                 FROM game_fps_benchmarks fps
                 WHERE fps.cpu_part_id = p.id OR fps.gpu_part_id = p.id
               ) AS part_has_fps_coverage,
               CASE WHEN e.category = 'CPU' THEN 1 ELSE 0 END AS category_cpu,
               CASE WHEN e.category = 'GPU' THEN 1 ELSE 0 END AS category_gpu,
               CASE WHEN e.category = 'RAM' THEN 1 ELSE 0 END AS category_ram,
               CASE WHEN e.category = 'MOTHERBOARD' THEN 1 ELSE 0 END AS category_motherboard,
               CASE WHEN e.category = 'STORAGE' THEN 1 ELSE 0 END AS category_storage,
               CASE WHEN e.category = 'PSU' THEN 1 ELSE 0 END AS category_psu,
               CASE WHEN e.category = 'CASE' THEN 1 ELSE 0 END AS category_case,
               CASE WHEN e.category = 'COOLER' THEN 1 ELSE 0 END AS category_cooler,
               b.total_price AS build_total_price,
               e.created_at
        FROM recommendation_events e
        LEFT JOIN parts p ON p.id = e.part_id
        LEFT JOIN LATERAL (
          SELECT score
          FROM benchmark_summaries b
          WHERE b.part_id = p.id
            AND b.deleted_at IS NULL
          ORDER BY b.created_at DESC, b.id DESC
          LIMIT 1
        ) bs ON true
        LEFT JOIN LATERAL (
          SELECT collected_at
          FROM price_snapshots snapshot
          WHERE snapshot.part_id = p.id
            AND snapshot.collected_at <= now()
          ORDER BY snapshot.collected_at DESC, snapshot.id DESC
          LIMIT 1
        ) ps ON true
        LEFT JOIN LATERAL (
          SELECT image_url, offer_url
          FROM part_external_offers offer
          WHERE offer.part_id = p.id
            AND offer.deleted_at IS NULL
          ORDER BY
            CASE offer.source
              WHEN 'NAVER_SHOPPING_SEARCH' THEN 1
              WHEN 'ADMIN_MANUAL' THEN 2
              ELSE 9
            END,
            offer.refreshed_at DESC,
            offer.id DESC
          LIMIT 1
        ) peo ON true
        LEFT JOIN builds b ON b.id = e.build_id
        WHERE (
          %s::text IS NULL
          OR (%s::text = '__HOME_PARTS__' AND e.source_surface IN ('HOME_RECOMMENDED_PARTS', 'ADMIN_HOME_PART_FEEDBACK'))
          OR e.source_surface = %s::text
        )
        ORDER BY e.created_at, e.id
    """
    with conn.cursor() as cur:
        cur.execute(sql, (source_surface, source_surface, source_surface))
        for row in cur.fetchall():
            yield dict(zip(FIELDS, row))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--database-url", default=os.getenv("DATABASE_URL", "postgresql://buildgraph:buildgraph@localhost:5432/buildgraph"))
    parser.add_argument("--output", default="artifacts/recommendation/training.csv")
    parser.add_argument("--source-surface", default=None, help="Optional source_surface filter, for example HOME_RECOMMENDED_PARTS")
    parser.add_argument("--home-parts", action="store_true", help="Export HOME_RECOMMENDED_PARTS and ADMIN_HOME_PART_FEEDBACK events together.")
    args = parser.parse_args()

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with connect(args.database_url) as conn, output.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=FIELDS)
        writer.writeheader()
        count = 0
        source_surface = None if not args.source_surface or args.source_surface.upper() == "ALL" else args.source_surface
        if args.home_parts:
            source_surface = "__HOME_PARTS__"
        for row in rows(conn, source_surface):
            writer.writerow(row)
            count += 1
    print(f"exported_rows={count} output={output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
