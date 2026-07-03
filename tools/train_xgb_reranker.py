#!/usr/bin/env python3
"""Train an XGBoost recommendation reranker from exported events."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path


FEATURES = [
    "rank_position",
    "part_price",
    "build_total_price",
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
]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", default="artifacts/recommendation/training.csv")
    parser.add_argument("--output-dir", default="artifacts/recommendation/model")
    parser.add_argument("--model-version", default=None)
    parser.add_argument("--min-rows", type=int, default=50)
    parser.add_argument("--allow-small-dataset", action="store_true")
    args = parser.parse_args()

    try:
        import pandas as pd
        from xgboost import XGBRegressor
        from sklearn.metrics import mean_absolute_error
        from sklearn.model_selection import train_test_split
    except ImportError as exc:
        raise SystemExit("Install pandas, scikit-learn, and xgboost before training the reranker.") from exc

    input_path = Path(args.input)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    model_version = args.model_version or "xgb-" + datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")

    data = pd.read_csv(input_path)
    if data.empty:
        report = {
            "modelVersion": model_version,
            "status": "SKIPPED_EMPTY_DATASET",
            "rowCount": 0,
            "createdAt": datetime.now(timezone.utc).isoformat(),
        }
        (output_dir / "metrics.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False))
        return 0

    if len(data) < args.min_rows and not args.allow_small_dataset:
        report = {
            "modelVersion": model_version,
            "status": "SKIPPED_LOW_DATASET",
            "rowCount": int(len(data)),
            "minRows": args.min_rows,
            "createdAt": datetime.now(timezone.utc).isoformat(),
        }
        (output_dir / "metrics.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False))
        return 0

    for feature in FEATURES:
        if feature not in data:
            data[feature] = 0
        data[feature] = pd.to_numeric(data.get(feature), errors="coerce").fillna(0)
    data["label_score"] = pd.to_numeric(data["label_score"], errors="coerce").fillna(0)

    x = data[FEATURES]
    y = data["label_score"]
    if len(data) >= 10:
        x_train, x_test, y_train, y_test = train_test_split(x, y, test_size=0.25, random_state=42)
    else:
        x_train, x_test, y_train, y_test = x, x, y, y

    model = XGBRegressor(
        n_estimators=80,
        max_depth=3,
        learning_rate=0.08,
        subsample=0.9,
        colsample_bytree=0.9,
        random_state=42,
        objective="reg:squarederror",
    )
    model.fit(x_train, y_train)
    predictions = model.predict(x_test)
    mae = float(mean_absolute_error(y_test, predictions))

    model_path = output_dir / f"{model_version}.json"
    model.save_model(model_path)
    latest_path = output_dir / "home-parts-latest.json"
    model.save_model(latest_path)
    report = {
        "modelName": "xgboost-reranker",
        "modelVersion": model_version,
        "algorithm": "XGBOOST",
        "artifactPath": str(model_path),
        "latestArtifactPath": str(latest_path),
        "status": "TRAINED",
        "rowCount": int(len(data)),
        "features": FEATURES,
        "metrics": {"mae": mae},
        "createdAt": datetime.now(timezone.utc).isoformat(),
    }
    (output_dir / "metrics.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
