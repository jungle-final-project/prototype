import argparse
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_FIXTURE_ROOT = ROOT / "docs" / "agent-as" / "fixtures" / "scenarios"
REQUIRED_FILES = {
    "raw.jsonl",
    "expected-log-summary.json",
    "expected-routing.json",
    "expected-ai-result.json",
    "admin-label.json",
}
RAW_REQUIRED_FIELDS = {
    "schemaVersion",
    "collectedAt",
    "agentId",
    "sequence",
    "kind",
    "payload",
    "privacyFlags",
}
ALLOWED_DECISIONS = {"SELF_SOLVABLE", "REMOTE_POSSIBLE", "VISIT_REQUIRED", "NEEDS_MORE_INFO"}
ALLOWED_RISK_LEVELS = {"LOW", "MEDIUM", "HIGH"}
ALLOWED_CONFIDENCE = {"LOW", "MEDIUM", "HIGH"}


class FixtureError(Exception):
    pass


def load_json(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise FixtureError(f"{path}: invalid JSON: {exc}") from exc


def validate_raw_jsonl(path):
    rows = []
    lines = path.read_text(encoding="utf-8").splitlines()
    if not lines:
        raise FixtureError(f"{path}: raw.jsonl must contain at least one line")
    for index, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as exc:
            raise FixtureError(f"{path}:{index}: invalid JSONL row: {exc}") from exc
        missing = sorted(RAW_REQUIRED_FIELDS - row.keys())
        if missing:
            raise FixtureError(f"{path}:{index}: missing raw envelope fields: {', '.join(missing)}")
        if not isinstance(row.get("payload"), dict):
            raise FixtureError(f"{path}:{index}: payload must be an object")
        privacy_flags = row.get("privacyFlags")
        if not isinstance(privacy_flags, dict):
            raise FixtureError(f"{path}:{index}: privacyFlags must be an object")
        if privacy_flags.get("containsRawPath") is True and privacy_flags.get("masked") is not True:
            raise FixtureError(f"{path}:{index}: containsRawPath=true requires masked=true")
        if isinstance(row.get("sequence"), bool) or not isinstance(row.get("sequence"), int):
            raise FixtureError(f"{path}:{index}: sequence must be an integer")
        rows.append(row)
    if not rows:
        raise FixtureError(f"{path}: raw.jsonl must contain at least one non-empty line")
    return rows


def require_fields(path, payload, fields):
    missing = [field for field in fields if field not in payload]
    if missing:
        raise FixtureError(f"{path}: missing fields: {', '.join(missing)}")


def validate_log_summary(path, payload, raw_rows):
    require_fields(
        path,
        payload,
        [
            "summaryVersion",
            "scenarioId",
            "symptomType",
            "summaryText",
            "incidentWindow",
            "signalKinds",
            "reasonCodes",
            "dataQuality",
            "rawSamplePolicy",
        ],
    )
    if not isinstance(payload["signalKinds"], list) or not payload["signalKinds"]:
        raise FixtureError(f"{path}: signalKinds must be a non-empty array")
    raw_kinds = {row["kind"] for row in raw_rows}
    missing_kinds = set(payload["signalKinds"]) - raw_kinds
    if missing_kinds:
        raise FixtureError(f"{path}: signalKinds not present in raw rows: {', '.join(sorted(missing_kinds))}")
    raw_sample_policy = payload["rawSamplePolicy"]
    if raw_sample_policy.get("maxSamples") != 20:
        raise FixtureError(f"{path}: rawSamplePolicy.maxSamples must be 20")
    if raw_sample_policy.get("rawFullLogIncluded") is not False:
        raise FixtureError(f"{path}: raw full log must not be included")


def validate_routing(path, payload):
    require_fields(
        path,
        payload,
        [
            "recommendedDecision",
            "supportDecision",
            "riskLevel",
            "confidence",
            "reasonCodes",
            "remoteActions",
            "visitReasons",
            "blockingFactors",
            "safetyAdviceLevel",
            "safetyNotices",
            "requiresAdminApproval",
            "allowAutoResponse",
        ],
    )
    if payload["recommendedDecision"] not in ALLOWED_DECISIONS:
        raise FixtureError(f"{path}: unsupported recommendedDecision {payload['recommendedDecision']}")
    if payload["supportDecision"] != payload["recommendedDecision"]:
        raise FixtureError(f"{path}: supportDecision must match recommendedDecision in fixtures")
    if payload["riskLevel"] not in ALLOWED_RISK_LEVELS:
        raise FixtureError(f"{path}: unsupported riskLevel {payload['riskLevel']}")
    if payload["confidence"] not in ALLOWED_CONFIDENCE:
        raise FixtureError(f"{path}: unsupported confidence {payload['confidence']}")
    if payload["requiresAdminApproval"] is not True:
        raise FixtureError(f"{path}: AS AI fixtures must require admin approval")
    if payload["allowAutoResponse"] is not False:
        raise FixtureError(f"{path}: AS AI fixtures must not allow auto response")


def validate_ai_result(path, payload, routing):
    require_fields(
        path,
        payload,
        [
            "contractVersion",
            "supportDecision",
            "riskLevel",
            "confidence",
            "reasonCodes",
            "causeCandidates",
            "nextActions",
            "remoteActions",
            "visitReasons",
            "blockingFactors",
            "requiredAdditionalLogs",
            "evidenceRefs",
            "unsafeActionsExcluded",
            "adminReviewRequired",
            "userFirstNotice",
            "proposedTicketPatch",
        ],
    )
    if payload["supportDecision"] != routing["supportDecision"]:
        raise FixtureError(f"{path}: supportDecision must match expected-routing.json")
    if payload["riskLevel"] != routing["riskLevel"]:
        raise FixtureError(f"{path}: riskLevel must match expected-routing.json")
    if payload["adminReviewRequired"] is not True:
        raise FixtureError(f"{path}: adminReviewRequired must be true")
    if not isinstance(payload["userFirstNotice"], dict):
        raise FixtureError(f"{path}: userFirstNotice must be an object")
    require_fields(path, payload["userFirstNotice"], ["title", "summary", "safeActions", "additionalQuestions"])
    if payload.get("proposedTicketPatch") != {}:
        raise FixtureError(f"{path}: fixtures must not auto-patch tickets")


def validate_admin_label(path, payload, routing):
    require_fields(
        path,
        payload,
        [
            "reviewStatus",
            "finalSupportDecision",
            "diagnosticAccuracy",
            "actualResolution",
            "eligibleForTraining",
            "rawFullLogIncluded",
            "labelSource",
        ],
    )
    if payload["finalSupportDecision"] != routing["supportDecision"]:
        raise FixtureError(f"{path}: finalSupportDecision must match expected-routing.json")
    if payload["eligibleForTraining"] is not True:
        raise FixtureError(f"{path}: fixtures should be eligible for training")
    if payload["rawFullLogIncluded"] is not False:
        raise FixtureError(f"{path}: raw full log must not be used as training input")


def validate_scenario(scenario_dir):
    names = {path.name for path in scenario_dir.iterdir() if path.is_file()}
    missing = sorted(REQUIRED_FILES - names)
    if missing:
        raise FixtureError(f"{scenario_dir}: missing files: {', '.join(missing)}")
    raw_rows = validate_raw_jsonl(scenario_dir / "raw.jsonl")
    log_summary = load_json(scenario_dir / "expected-log-summary.json")
    routing = load_json(scenario_dir / "expected-routing.json")
    ai_result = load_json(scenario_dir / "expected-ai-result.json")
    admin_label = load_json(scenario_dir / "admin-label.json")
    validate_log_summary(scenario_dir / "expected-log-summary.json", log_summary, raw_rows)
    validate_routing(scenario_dir / "expected-routing.json", routing)
    validate_ai_result(scenario_dir / "expected-ai-result.json", ai_result, routing)
    validate_admin_label(scenario_dir / "admin-label.json", admin_label, routing)


def main():
    parser = argparse.ArgumentParser(description="Validate PC Agent AS AI fixture scenarios.")
    parser.add_argument("--root", type=Path, default=DEFAULT_FIXTURE_ROOT)
    parser.add_argument("--min-scenarios", type=int, default=50)
    args = parser.parse_args()
    if not args.root.exists():
        raise SystemExit(f"fixture root does not exist: {args.root}")
    scenario_dirs = sorted(path for path in args.root.iterdir() if path.is_dir())
    if len(scenario_dirs) < args.min_scenarios:
        raise SystemExit(f"expected at least {args.min_scenarios} scenarios, found {len(scenario_dirs)}")
    errors = []
    for scenario_dir in scenario_dirs:
        try:
            validate_scenario(scenario_dir)
        except FixtureError as exc:
            errors.append(str(exc))
    if errors:
        raise SystemExit("\n".join(errors))
    print(f"Validated {len(scenario_dirs)} PC Agent AS AI fixture scenarios")


if __name__ == "__main__":
    main()
