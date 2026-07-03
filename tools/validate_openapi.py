from pathlib import Path

try:
    import yaml
except ModuleNotFoundError as exc:
    raise SystemExit("PyYAML is required to validate docs/openapi.yaml") from exc


OPENAPI_PATH = Path("docs/openapi.yaml")

TOOL_PATHS = [
    "/api/tools/compatibility/check",
    "/api/tools/power/check",
    "/api/tools/size/check",
    "/api/tools/performance/check",
    "/api/tools/price/check",
]

REQUIRED_PATHS = [
    "/api/health",
    "/api/users",
    "/api/auth/login",
    "/api/auth/refresh",
    "/api/auth/logout",
    "/api/auth/me",
    "/api/auth/google/start",
    "/api/auth/google/callback",
    "/api/auth/exchange",
    "/api/requirements/parse",
    "/api/builds/recommend",
    "/api/builds/{id}",
    "/api/builds/history",
    "/api/builds/{id}/change-part",
    "/api/parts",
    "/api/parts/{id}",
    *TOOL_PATHS,
    "/api/price-alerts",
    "/api/admin/price-jobs",
    "/api/admin/price-jobs/run",
    "/api/agent/devices/register",
    "/api/agent/consents",
    "/api/agent/heartbeat",
    "/api/agent/log-uploads",
    "/api/agent/sessions",
    "/api/agent/sessions/{id}/run",
    "/api/agent/sessions/{id}",
    "/api/rag/search",
    "/api/rag/evidence/{id}",
    "/api/agent-logs/upload",
    "/api/agent-logs/{id}",
    "/api/as-tickets",
    "/api/as-tickets/{id}",
    "/api/as-tickets/{id}/remote-support-requests",
    "/api/as-tickets/{id}/feedback",
    "/api/admin/dashboard",
    "/api/admin/audit-logs/recent",
    "/api/admin/agent-sessions",
    "/api/admin/agent-sessions/{id}",
    "/api/admin/tool-invocations",
    "/api/admin/tool-invocations/{id}",
    "/api/admin/rag-evidence/{id}",
    "/api/admin/as-tickets",
    "/api/admin/as-tickets/{id}",
]

POST_JSON_REQUEST_SCHEMAS = {
    "/api/users": "SignupRequest",
    "/api/auth/login": "LoginRequest",
    "/api/auth/refresh": "RefreshRequest",
    "/api/auth/logout": "RefreshRequest",
    "/api/auth/exchange": "AuthExchangeRequest",
    "/api/requirements/parse": "RequirementParseRequest",
    "/api/builds/recommend": "BuildRecommendRequest",
    "/api/builds/{id}/change-part": "ChangePartRequest",
    "/api/price-alerts": "PriceAlertCreateRequest",
    "/api/admin/price-jobs/run": "PriceJobRunRequest",
    "/api/agent/devices/register": "PcAgentRegisterRequest",
    "/api/agent/consents": "PcAgentConsentRequest",
    "/api/agent/heartbeat": "PcAgentHeartbeatRequest",
    "/api/agent/sessions": "AgentSessionCreateRequest",
    "/api/as-tickets": "AsTicketCreateRequest",
    "/api/as-tickets/{id}/remote-support-requests": "RemoteSupportRequestCreateRequest",
    "/api/as-tickets/{id}/feedback": "SupportFeedbackRequest",
}

REQUIRED_SCHEMAS = [
    "ErrorResponse",
    "AuthResponse",
    "ChangePartRequest",
    "ToolCheckRequest",
    "ToolCheckResponse",
    "AgentLogUploadRequest",
    "PcAgentRegisterRequest",
    "PcAgentRegisterResponse",
    "PcAgentConsentRequest",
    "PcAgentConsentResponse",
    "PcAgentHeartbeatRequest",
    "PcAgentHeartbeatResponse",
    "PcAgentLogUploadRequest",
    "PcAgentLogUploadResponse",
    "SupportDecision",
    "SafetyAdviceLevel",
    "DiagnosticAccuracy",
    "SafetyNoticeDto",
    "IncidentWindowDto",
    "LogSummaryDto",
    "SupportRoutingDto",
    "AiDiagnosisRequestDto",
    "AsTicketDto",
    "RemoteSupportRequestCreateRequest",
    "SupportFeedbackRequest",
    "AdminAsTicketUpdateRequest",
    "AgentSessionDto",
    "ToolInvocationDto",
    "RagEvidenceDto",
]

REQUIRED_ERROR_CODES = {
    "VALIDATION_ERROR",
    "UNAUTHORIZED",
    "FORBIDDEN",
    "NOT_FOUND",
    "CONFLICT_STATE",
    "DUPLICATE_RESOURCE",
    "FILE_VALIDATION_ERROR",
    "INTERNAL_ERROR",
}


def ref_name(schema: dict) -> str | None:
    ref = schema.get("$ref")
    if not ref:
        return None
    return ref.removeprefix("#/components/schemas/")


def request_schema_ref(operation: dict, content_type: str = "application/json") -> str | None:
    schema = (
        operation.get("requestBody", {})
        .get("content", {})
        .get(content_type, {})
        .get("schema", {})
    )
    return ref_name(schema)


def main() -> None:
    with OPENAPI_PATH.open(encoding="utf-8") as file:
        spec = yaml.safe_load(file)

    if spec.get("openapi") != "3.0.3":
        raise SystemExit("docs/openapi.yaml must declare openapi: 3.0.3")

    paths = spec.get("paths", {})
    missing_paths = [path for path in REQUIRED_PATHS if path not in paths]
    if missing_paths:
        raise SystemExit(f"Missing OpenAPI paths: {', '.join(missing_paths)}")

    if "/api/tools/{tool}/check" in paths:
        raise SystemExit("Use five concrete Tool endpoints, not /api/tools/{tool}/check")

    if "/api/price-snapshots/collect" in paths:
        raise SystemExit("Public /api/price-snapshots/collect is excluded from V1")

    schemas = spec.get("components", {}).get("schemas", {})
    missing_schemas = [schema for schema in REQUIRED_SCHEMAS if schema not in schemas]
    if missing_schemas:
        raise SystemExit(f"Missing OpenAPI schemas: {', '.join(missing_schemas)}")

    for path, schema_name in POST_JSON_REQUEST_SCHEMAS.items():
        post = paths.get(path, {}).get("post")
        if not post:
            raise SystemExit(f"Missing POST operation for {path}")

        if request_schema_ref(post) != schema_name:
            raise SystemExit(f"{path} must reference {schema_name}")

    for path in TOOL_PATHS:
        post = paths.get(path, {}).get("post")
        if not post:
            raise SystemExit(f"Missing POST operation for {path}")
        request_body = post.get("requestBody", {})
        if request_body.get("$ref") != "#/components/requestBodies/ToolCheckRequestBody":
            raise SystemExit(f"{path} must use ToolCheckRequestBody")

    upload_post = paths["/api/agent-logs/upload"].get("post", {})
    upload_schema = request_schema_ref(upload_post, "multipart/form-data")
    if upload_schema != "AgentLogUploadRequest":
        raise SystemExit("/api/agent-logs/upload must use multipart/form-data AgentLogUploadRequest")

    agent_security = spec.get("components", {}).get("securitySchemes", {})
    if "agentBearerAuth" not in agent_security:
        raise SystemExit("OpenAPI must define agentBearerAuth for PC Agent token endpoints")
    for path in ["/api/agent/consents", "/api/agent/heartbeat", "/api/agent/log-uploads"]:
        post = paths[path].get("post", {})
        if {"agentBearerAuth": []} not in post.get("security", []):
            raise SystemExit(f"{path} must use agentBearerAuth")
        parameters = post.get("parameters", [])
        if not any(parameter.get("$ref") == "#/components/parameters/IdempotencyKey" for parameter in parameters):
            raise SystemExit(f"{path} must declare Idempotency-Key header")
    register_security = paths["/api/agent/devices/register"].get("post", {}).get("security")
    if register_security != []:
        raise SystemExit("/api/agent/devices/register must not require bearer auth")
    agent_upload_post = paths["/api/agent/log-uploads"].get("post", {})
    agent_upload_schema = request_schema_ref(agent_upload_post, "multipart/form-data")
    if agent_upload_schema != "PcAgentLogUploadRequest":
        raise SystemExit("/api/agent/log-uploads must use multipart/form-data PcAgentLogUploadRequest")

    consent_types = set(
        schemas["PcAgentConsentRequest"]
        .get("properties", {})
        .get("consentType", {})
        .get("enum", [])
    )
    required_consent_types = {
        "SERVER_UPLOAD",
        "REMOTE_CONNECTION",
        "REMOTE_FULL_CONTROL",
        "HIGH_RISK_REMOTE_ACTION",
    }
    missing_consent_types = required_consent_types - consent_types
    if missing_consent_types:
        raise SystemExit(f"PcAgentConsentRequest missing consent types: {', '.join(sorted(missing_consent_types))}")

    admin_ticket_patch = paths["/api/admin/as-tickets/{id}"].get("patch", {})
    if request_schema_ref(admin_ticket_patch) != "AdminAsTicketUpdateRequest":
        raise SystemExit("/api/admin/as-tickets/{id} PATCH must reference AdminAsTicketUpdateRequest")

    as_ticket_properties = schemas["AsTicketDto"].get("properties", {})
    for field in [
        "analysisStatus",
        "reviewStatus",
        "supportDecision",
        "riskLevel",
        "autoResponseAllowed",
        "adminNote",
        "remoteSupportLink",
        "remoteSupportStatus",
        "visitSupportRequired",
        "visitSupportStatus",
        "visitPreferredDate",
        "visitTimeSlot",
        "safetyAdviceLevel",
        "safetyNotices",
        "feedbackRating",
        "feedbackComment",
        "feedbackCreatedAt",
        "diagnosticAccuracy",
    ]:
        if field not in as_ticket_properties:
            raise SystemExit(f"AsTicketDto missing {field}")

    admin_ticket_update_properties = schemas["AdminAsTicketUpdateRequest"].get("properties", {})
    for field in [
        "status",
        "assignedAdminId",
        "adminNote",
        "supportDecision",
        "reviewStatus",
        "riskLevel",
        "diagnosticAccuracy",
        "autoResponseAllowed",
        "remoteSupportLink",
        "visitSupportRequired",
        "visitPreferredDate",
        "visitTimeSlot",
    ]:
        if field not in admin_ticket_update_properties:
            raise SystemExit(f"AdminAsTicketUpdateRequest missing {field}")

    final_support_decisions = {
        "SELF_SOLVABLE",
        "REMOTE_POSSIBLE",
        "VISIT_REQUIRED",
        "REPAIR_OR_REPLACE",
        "NEEDS_MORE_INFO",
        "MONITOR_ONLY",
        "UNSUPPORTED",
    }
    support_decision_enum = set(schemas["SupportDecision"].get("enum", []))
    if support_decision_enum != final_support_decisions:
        raise SystemExit("SupportDecision enum must match FINAL_SUPPORT_SCENARIOS")
    ui_labels = schemas["SupportDecision"].get("x-ui-labels", {})
    missing_ui_labels = final_support_decisions - set(ui_labels)
    if missing_ui_labels:
        raise SystemExit(f"SupportDecision missing UI labels: {', '.join(sorted(missing_ui_labels))}")
    for schema_name in ["AsTicketDto", "AdminAsTicketUpdateRequest", "PcAgentLogUploadResponse"]:
        support_decision_schema = schemas[schema_name].get("properties", {}).get("supportDecision", {})
        if support_decision_schema.get("$ref") != "#/components/schemas/SupportDecision":
            raise SystemExit(f"{schema_name}.supportDecision must reference SupportDecision")
    for field, schema_name in [
        ("incidentWindow", "IncidentWindowDto"),
        ("logSummary", "LogSummaryDto"),
        ("supportRouting", "SupportRoutingDto"),
    ]:
        as_ticket_field = as_ticket_properties.get(field, {})
        refs = [item.get("$ref") for item in as_ticket_field.get("allOf", [])]
        if f"#/components/schemas/{schema_name}" not in refs:
            raise SystemExit(f"AsTicketDto.{field} must reference {schema_name}")
    if schemas["LogSummaryDto"]["properties"]["rawSamples"].get("maxItems") != 20:
        raise SystemExit("LogSummaryDto.rawSamples must be limited to 20")
    if schemas["AiDiagnosisRequestDto"]["properties"]["rawSamples"].get("maxItems") != 20:
        raise SystemExit("AiDiagnosisRequestDto.rawSamples must be limited to 20")
    ai_required = set(schemas["AiDiagnosisRequestDto"].get("required", []))
    if "supportRouting" not in ai_required:
        raise SystemExit("AiDiagnosisRequestDto must require supportRouting")

    support_routing_properties = schemas["SupportRoutingDto"].get("properties", {})
    for field in ["safetyAdviceLevel", "safetyNotices", "allowAutoResponse", "adminApprovalRequired"]:
        if field not in support_routing_properties:
            raise SystemExit(f"SupportRoutingDto missing {field}")

    auth_properties = schemas["AuthResponse"].get("properties", {})
    for field in ["accessToken", "refreshToken", "user"]:
        if field not in auth_properties:
            raise SystemExit(f"AuthResponse missing {field}")
    if "token" in auth_properties:
        raise SystemExit("AuthResponse must not use legacy token field")

    change_part_properties = schemas["ChangePartRequest"].get("properties", {})
    if {"category", "partId"} - set(change_part_properties):
        raise SystemExit("ChangePartRequest must use category and partId")
    if {"itemId", "replacementPartId"} & set(change_part_properties):
        raise SystemExit("ChangePartRequest must not use legacy itemId/replacementPartId")

    error_code_enum = set(
        schemas["ErrorResponse"]
        .get("properties", {})
        .get("code", {})
        .get("enum", [])
    )
    missing_error_codes = REQUIRED_ERROR_CODES - error_code_enum
    if missing_error_codes:
        raise SystemExit(f"ErrorResponse missing codes: {', '.join(sorted(missing_error_codes))}")

    print(f"OpenAPI validation passed: {len(paths)} paths")


if __name__ == "__main__":
    main()
