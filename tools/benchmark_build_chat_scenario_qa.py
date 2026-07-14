#!/usr/bin/env python3
"""Run the fixed gpt-5.4-mini Build Chat scenario QA suite."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import os
import re
import statistics
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from itertools import count
from pathlib import Path
import threading
from typing import Any
from zoneinfo import ZoneInfo


PROFILE = "BUILD_CHAT_54_MINI_FAST"
REPORT_TIMEZONE = ZoneInfo("Asia/Seoul")
DEFAULT_CASES = "tools/build_chat_scenario_qa_cases.json"
ANSWER_TYPES = {"BUDGET", "PART", "GENERAL"}
CATEGORIES = ["CPU", "MOTHERBOARD", "RAM", "GPU", "STORAGE", "PSU", "CASE", "COOLER"]
CATEGORY_ALIASES = {
    "CPU": ["cpu", "프로세서", "라이젠", "ryzen", "인텔", "intel", "9700", "9950"],
    "GPU": ["gpu", "그래픽", "글카", "rtx", "지포스", "geforce", "vram", "5090", "5080", "5070", "5060"],
    "RAM": ["ram", "램", "메모리", "ddr"],
    "STORAGE": ["ssd", "nvme", "저장", "pcie"],
    "PSU": ["psu", "파워", "전원", "w", "와트", "atx 3"],
    "MOTHERBOARD": ["메인보드", "마더보드", "보드", "b850", "x870"],
    "CASE": ["케이스", "case", "미들타워", "메쉬"],
    "COOLER": ["쿨러", "공랭", "수랭", "aio", "라디에이터"],
}
VISIBLE_TECH_TERMS = [
    "socket mismatch", "hard constraint", "sideeffectrisk", "semantic cache",
    "cache hit", "cache miss", "normalized score", "internal db", "tool fail",
    "currentquotedraft", "public_id", "schema violation",
]
BUDGET_COUNTER_WARNINGS = {
    "BUDGET_BELOW_MINIMUM", "BUDGET_BELOW_USAGE_MINIMUM", "PART_BUDGET_SHORTFALL",
}
CRITICAL_FAILURES = {
    "SCHEMA_INVALID", "DRAFT_MUTATED", "UNKNOWN_PART", "PART_NAME_MISMATCH",
    "PART_PRICE_MISMATCH", "BUILD_TOTAL_MISMATCH", "TOOL_FAIL_RECOMMENDED",
    "GRAPH_FAIL_RECOMMENDED", "HARD_CONSTRAINT_LOST", "BUDGET_GUARD_VIOLATION",
    "SIMULATION_MUTATION", "CACHE_INTENT_COLLISION", "PUBLIC_ACTIONS_EXPOSED",
    "DEPRECATED_WARNING_EXPOSED", "BUILD_COUNT_EXCEEDED", "REQUIRED_WARNING_MISSING",
    "HARD_CONSTRAINT_WARNING_MISSING", "CLARIFICATION_ECHO_MISSING",
    "NEGATED_CONSTRAINT_INCLUDED",
    "BOARD_FOCUS_MISSING", "BOARD_FOCUS_CATEGORY_MISMATCH",
    "BOARD_FOCUS_FALSE_POSITIVE", "BOARD_FOCUS_MUTATION", "BOARD_FOCUS_SCHEMA_INVALID",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build Chat gpt-5.4-mini scenario QA")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--cases", default=DEFAULT_CASES)
    parser.add_argument("--stage", choices=["gate", "full", "stability", "cache"], default="gate")
    parser.add_argument("--output-dir", default="docs/reports")
    parser.add_argument("--results-dir", default=".qa-results")
    parser.add_argument("--report-suffix", default=None)
    parser.add_argument("--case-id", nargs="+", default=None)
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--repeat", type=int, default=None)
    parser.add_argument("--workers", type=int, default=1, help="Parallel case workers for gate/full functional QA")
    parser.add_argument("--verify-graph", action="store_true")
    parser.add_argument("--cache-state", choices=["off", "on", "unknown"], default="unknown")
    parser.add_argument("--validate-only", action="store_true", help="Validate the fixed corpus without calling the API")
    parser.add_argument("--strict", action="store_true")
    parser.add_argument("--user-email", default=os.environ.get("BUILD_CHAT_QA_USER_EMAIL", "build-chat-qa@example.com"))
    parser.add_argument("--user-password", default=os.environ.get("BUILD_CHAT_QA_USER_PASSWORD", "passw0rd!"))
    parser.add_argument("--user-name", default=os.environ.get("BUILD_CHAT_QA_USER_NAME", "Build Chat QA"))
    parser.add_argument("--no-provision-user", action="store_true")
    return parser.parse_args()


class ApiClient:
    def __init__(self, base_url: str, email: str, password: str, *, name: str = "Build Chat QA", provision: bool = False):
        self.base_url = base_url.rstrip("/")
        self.email = email
        self.password = password
        self.token = self._authenticate(email, password, name, provision)
        self.part_cache: dict[str, dict[str, Any]] = {}
        self.graph_cache: dict[str, dict[str, Any]] = {}

    def _authenticate(self, email: str, password: str, name: str, provision: bool) -> str:
        try:
            return self._login(email, password)
        except RuntimeError:
            if not provision:
                raise
        status, payload, _, _ = self.request("POST", "/api/users", {
            "email": email,
            "password": password,
            "name": name,
            "phoneNumber": "010-7000-0700",
            "postalCode": "06236",
            "addressLine1": "서울특별시 강남구 테헤란로 1",
            "addressLine2": "Build Chat QA",
            "termsAccepted": True,
            "marketingAccepted": False,
        }, auth=False)
        if status >= 400 and status != 409:
            raise RuntimeError(f"QA user provisioning failed: status={status} payload={payload}")
        return self._login(email, password)

    def _login(self, email: str, password: str) -> str:
        status, payload, _, _ = self.request("POST", "/api/auth/login", {"email": email, "password": password}, auth=False)
        token = payload.get("accessToken") if isinstance(payload, dict) else None
        if status >= 400 or not token:
            raise RuntimeError(f"login failed: status={status} payload={payload}")
        return str(token)

    def request(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        *,
        headers: dict[str, str] | None = None,
        auth: bool = True,
        timeout: int = 180,
        retry: bool = True,
        reauth: bool = True,
    ) -> tuple[int, dict[str, Any], int, dict[str, Any]]:
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        request_headers = {"Accept": "application/json"}
        if data is not None:
            request_headers["Content-Type"] = "application/json"
        if auth and getattr(self, "token", None):
            request_headers["Authorization"] = f"Bearer {self.token}"
        if headers:
            request_headers.update(headers)
        req = urllib.request.Request(self.base_url + path, data=data, headers=request_headers, method=method)
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(req, timeout=timeout) as response:
                raw = response.read().decode("utf-8", "replace")
                payload = json.loads(raw) if raw else {}
                return response.status, payload, round((time.perf_counter() - started) * 1000), {"retried": False}
        except urllib.error.HTTPError as error:
            raw = error.read().decode("utf-8", "replace")
            try:
                payload = json.loads(raw) if raw else {}
            except json.JSONDecodeError:
                payload = {"message": raw}
            elapsed = round((time.perf_counter() - started) * 1000)
            if auth and error.code == 401 and reauth:
                self.token = self._login(self.email, self.password)
                status, retry_payload, retry_ms, meta = self.request(
                    method,
                    path,
                    body,
                    headers=headers,
                    auth=auth,
                    timeout=timeout,
                    retry=retry,
                    reauth=False,
                )
                meta.update({"reauthenticated": True, "firstStatus": 401, "firstLatencyMs": elapsed})
                return status, retry_payload, retry_ms, meta
            if retry and (error.code == 429 or error.code >= 500):
                time.sleep(30)
                status, retry_payload, retry_ms, meta = self.request(
                    method, path, body, headers=headers, auth=auth, timeout=timeout, retry=False, reauth=reauth
                )
                meta.update({"retried": True, "firstStatus": error.code, "firstLatencyMs": elapsed})
                return status, retry_payload, retry_ms, meta
            return error.code, payload, elapsed, {"retried": False}
        except (urllib.error.URLError, TimeoutError) as error:
            elapsed = round((time.perf_counter() - started) * 1000)
            if retry:
                time.sleep(30)
                status, retry_payload, retry_ms, meta = self.request(
                    method, path, body, headers=headers, auth=auth, timeout=timeout, retry=False, reauth=reauth
                )
                meta.update({"retried": True, "firstStatus": "TRANSPORT", "firstLatencyMs": elapsed, "firstError": str(error)})
                return status, retry_payload, retry_ms, meta
            return 0, {"message": str(error)}, elapsed, {"retried": False, "transportError": str(error)}

    def current_draft(self) -> dict[str, Any]:
        status, payload, _, _ = self.request("GET", "/api/quote-drafts/current")
        if status >= 400:
            raise RuntimeError(f"current draft failed: {status} {payload}")
        return payload

    def active_parts(self) -> list[dict[str, Any]]:
        items: list[dict[str, Any]] = []
        page = 0
        while True:
            query = urllib.parse.urlencode({"status": "ACTIVE", "page": page, "size": 100})
            status, payload, _, _ = self.request("GET", f"/api/parts?{query}")
            if status >= 400:
                raise RuntimeError(f"parts query failed: {status} {payload}")
            page_items = payload.get("items") or []
            items.extend(page_items)
            if len(items) >= int(payload.get("total") or len(items)) or not page_items:
                break
            page += 1
        for item in items:
            if item.get("id"):
                self.part_cache[str(item["id"])] = item
        return items

    def part(self, part_id: str) -> dict[str, Any] | None:
        cached = self.part_cache.get(part_id)
        if cached and cached.get("attributes") is not None:
            return cached
        status, payload, _, _ = self.request("GET", f"/api/parts/{urllib.parse.quote(part_id)}")
        if status >= 400:
            return None
        self.part_cache[part_id] = payload
        return payload

    def resolve_graph(self, build: dict[str, Any]) -> dict[str, Any] | None:
        items = [
            {"partId": item.get("partId"), "category": item.get("category"), "quantity": item.get("quantity", 1)}
            for item in build.get("items") or [] if item.get("partId")
        ]
        fingerprint = json.dumps({
            "budgetWon": build.get("budgetWon"),
            "items": sorted(items, key=lambda row: (str(row["category"]), str(row["partId"]))),
        }, sort_keys=True)
        key = hashlib.sha256(fingerprint.encode()).hexdigest()
        if key in self.graph_cache:
            return self.graph_cache[key]
        status, payload, _, _ = self.request("POST", "/api/build-graphs/resolve", {
            "source": "AI_BUILD", "view": "FOCUSED", "budgetWon": build.get("budgetWon"), "items": items,
        })
        if status >= 400:
            return None
        self.graph_cache[key] = payload
        return payload


def draft_fingerprint(draft: dict[str, Any]) -> dict[str, Any]:
    items = [
        {"partId": item.get("partId"), "category": item.get("category"), "quantity": item.get("quantity")}
        for item in draft.get("items") or []
    ]
    items.sort(key=lambda row: (str(row["category"]), str(row["partId"])))
    return {"items": items, "totalPrice": draft.get("totalPrice")}


def complete_draft(draft: dict[str, Any]) -> bool:
    categories = {item.get("category") for item in draft.get("items") or []}
    return all(category in categories for category in CATEGORIES)


def virtual_draft(parts: list[dict[str, Any]]) -> dict[str, Any]:
    by_category: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for part in parts:
        if part.get("category") in CATEGORIES and int(part.get("price") or 0) > 0:
            by_category[str(part["category"])].append(part)
    selected = []
    for category in CATEGORIES:
        candidates = sorted(by_category[category], key=lambda row: (int(row.get("price") or 0), str(row.get("id"))))
        if not candidates:
            raise RuntimeError(f"cannot create virtual draft: no ACTIVE {category} part")
        part = candidates[len(candidates) // 2]
        quantity = 2 if category == "RAM" else 1
        price = int(part.get("price") or 0)
        selected.append({
            "partId": part.get("id"), "category": category, "name": part.get("name"),
            "manufacturer": part.get("manufacturer"), "quantity": quantity,
            "currentPrice": price, "price": price, "lineTotal": price * quantity,
            "attributes": part.get("attributes") or {},
        })
    return {
        "id": None, "status": "VIRTUAL_QA", "name": "Build Chat QA virtual draft",
        "items": selected, "itemCount": sum(item["quantity"] for item in selected),
        "totalPrice": sum(item["lineTotal"] for item in selected),
    }


def draft_from_verified_build(client: ApiClient, build: dict[str, Any]) -> dict[str, Any] | None:
    selected = []
    for item in build.get("items") or []:
        part_id = str(item.get("partId") or "")
        detail = client.part(part_id) if part_id else None
        if not detail:
            return None
        attributes = detail.get("attributes") or {}
        quantity = int(item.get("quantity") or 1)
        if (str(detail.get("category") or item.get("category")) == "RAM" and int(attributes.get("moduleCount") or 1) > 1):
            # Build 카드의 RAM quantity는 모듈 수 의미로도 사용된다. currentQuoteDraft에서는
            # kit 상품 자체 수량으로 해석되므로, 2-DIMM kit를 2개 kit(4 DIMM)로 만들지 않는다.
            quantity = 1
        price = int(detail.get("price") or 0)
        selected.append({
            "partId": part_id,
            "category": detail.get("category") or item.get("category"),
            "name": detail.get("name"),
            "manufacturer": detail.get("manufacturer"),
            "quantity": quantity,
            "currentPrice": price,
            "price": price,
            "lineTotal": price * quantity,
            "attributes": attributes,
        })
    categories = {item.get("category") for item in selected}
    if not all(category in categories for category in CATEGORIES):
        return None
    return {
        "id": None,
        "status": "VIRTUAL_QA_VERIFIED",
        "name": "Build Chat QA verified virtual draft",
        "items": selected,
        "itemCount": sum(item["quantity"] for item in selected),
        "totalPrice": sum(item["lineTotal"] for item in selected),
    }


def verified_virtual_draft(client: ApiClient, active_parts: list[dict[str, Any]]) -> dict[str, Any]:
    status, response, _, _ = client.request(
        "POST",
        "/api/ai/build-chat",
        {"message": "300만원으로 QHD 게임용 PC 추천해줘"},
        headers={"X-BuildGraph-AI-Profile": PROFILE},
    )
    if status == 200 and response_schema_valid(response):
        for build in response.get("builds") or []:
            draft = draft_from_verified_build(client, build)
            if not draft:
                continue
            graph = client.resolve_graph({"items": draft["items"], "budgetWon": draft["totalPrice"]})
            blocking = any(
                str(result.get("status") or "").upper() == "FAIL"
                and str(result.get("tool") or "").lower() in {"compatibility", "power", "size"}
                for result in (graph or {}).get("toolResults") or []
            )
            if graph and not blocking and int((graph.get("compositeScore") or {}).get("score") or 0) > 0:
                return draft
    return virtual_draft(active_parts)


def response_schema_valid(response: Any) -> bool:
    return bool(
        isinstance(response, dict)
        and response.get("answerType") in ANSWER_TYPES
        and isinstance(response.get("message"), str)
        and bool(response.get("message", "").strip())
        and isinstance(response.get("builds"), list)
        and isinstance(response.get("warnings"), list)
    )


def visible_text(response: dict[str, Any]) -> str:
    pieces: list[str] = [str(response.get("message") or "")]
    pieces.extend(str(value) for value in response.get("quickReplies") or [])
    clarification = response.get("clarification") or {}
    if isinstance(clarification, dict):
        pieces.extend(str(value) for value in clarification.get("missingSlots") or [])
    simulation = response.get("simulation") or {}
    if isinstance(simulation, dict):
        pieces.extend(str(simulation.get(key) or "") for key in ("summary", "disclaimer"))
        for part_key in ("currentPart", "targetPart"):
            part = simulation.get(part_key) or {}
            if isinstance(part, dict):
                pieces.append(str(part.get("name") or ""))
        for row in simulation.get("specComparisons") or []:
            if isinstance(row, dict):
                pieces.extend(str(row.get(key) or "") for key in ("label", "currentValue", "targetValue", "deltaText"))
    board_focus = response.get("boardFocus") or {}
    if isinstance(board_focus, dict):
        pieces.append(str(board_focus.get("label") or ""))
        pieces.extend(str(value) for value in board_focus.get("categories") or [])
    for build in response.get("builds") or []:
        if isinstance(build, dict):
            pieces.extend(str(build.get(key) or "") for key in ("title", "summary", "tierLabel", "budgetLabel"))
            pieces.extend(str(value) for value in build.get("recommendedFor") or [])
            for item in build.get("items") or []:
                if isinstance(item, dict):
                    pieces.extend(str(item.get(key) or "") for key in ("name", "manufacturer", "note", "category"))
    return " ".join(pieces)


def has_next_action(response: dict[str, Any]) -> bool:
    return bool(
        response.get("builds") or response.get("simulation") or response.get("boardFocus") or response.get("clarification")
        or response.get("quickReplies")
    )


def preview_builds(response: dict[str, Any]) -> list[dict[str, Any]]:
    result = []
    for build in response.get("builds") or []:
        if build.get("tier") == "draft-edit" or "DRAFT_EDIT_PREVIEW" in (build.get("badges") or []):
            result.append(build)
    return result


def category_present(response: dict[str, Any], category: str | None) -> bool:
    if not category:
        return True
    simulation = response.get("simulation") or {}
    if isinstance(simulation, dict) and simulation.get("category") == category:
        return True
    board_focus = response.get("boardFocus") or {}
    if isinstance(board_focus, dict) and category in (board_focus.get("categories") or []):
        return True
    clarification = response.get("clarification") or {}
    if isinstance(clarification, dict):
        original = str(clarification.get("originalMessage") or "").lower().replace(" ", "")
        if any(alias.lower().replace(" ", "") in original for alias in CATEGORY_ALIASES[category]):
            return True
    for build in preview_builds(response):
        if category in (build.get("appliedPartCategories") or []):
            return True
    normalized = visible_text(response).lower().replace(" ", "")
    return any(alias.lower().replace(" ", "") in normalized for alias in CATEGORY_ALIASES[category])


def build_fact_failures(
    client: ApiClient,
    response: dict[str, Any],
    expected: dict[str, Any],
    verify_graph: bool,
) -> list[str]:
    failures: list[str] = []
    builds = response.get("builds") or []
    required_terms = [str(term).lower().replace(" ", "") for term in expected.get("requiredTerms") or []]
    forbidden_build_terms = [
        str(term).lower().replace(" ", "") for term in expected.get("forbiddenBuildTerms") or []
    ]
    required_build_categories = {str(value) for value in expected.get("requiredBuildCategories") or []}
    warnings = {str(value) for value in response.get("warnings") or []}
    if len(builds) > int(expected.get("maxBuilds", 3)):
        failures.append("BUILD_COUNT_EXCEEDED")
    for build in builds:
        computed_total = 0
        build_text = json.dumps(build.get("items") or [], ensure_ascii=False).lower().replace(" ", "")
        build_categories = {str(item.get("category") or "") for item in build.get("items") or []}
        if required_build_categories and not required_build_categories.issubset(build_categories):
            failures.append("BUILD_CATEGORIES_MISSING")
        for item in build.get("items") or []:
            part_id = str(item.get("partId") or "")
            detail = client.part(part_id) if part_id else None
            if not detail:
                failures.append("UNKNOWN_PART")
                continue
            if str(item.get("name") or "").strip() != str(detail.get("name") or "").strip():
                failures.append("PART_NAME_MISMATCH")
            item_price = int(item.get("price") or 0)
            current_price = int(detail.get("price") or 0)
            if item_price != current_price:
                failures.append("PART_PRICE_MISMATCH")
            quantity = int(item.get("quantity") or 1)
            computed_total += item_price * quantity
        if int(build.get("totalPrice") or 0) != computed_total:
            failures.append("BUILD_TOTAL_MISMATCH")
        if any(str(result.get("status") or "").upper() == "FAIL" for result in build.get("toolResults") or []):
            failures.append("TOOL_FAIL_RECOMMENDED")
        if required_terms and not all(term in build_text for term in required_terms):
            failures.append("HARD_CONSTRAINT_LOST")
        if forbidden_build_terms and any(term in build_text for term in forbidden_build_terms):
            failures.append("NEGATED_CONSTRAINT_INCLUDED")
        below_minimum = bool(warnings & BUDGET_COUNTER_WARNINGS) or "가능한 최소" in str(build.get("title") or "")
        graph_price_fail_allowed = below_minimum or str(expected.get("budgetMode") or "").upper() == "MIN"
        if verify_graph and build.get("items"):
            graph = client.resolve_graph(build)
            if graph is None:
                failures.append("GRAPH_VERIFICATION_FAILED")
            elif any(
                str(result.get("status") or "").upper() == "FAIL"
                and not (graph_price_fail_allowed and str(result.get("tool") or "").lower() == "price")
                for result in graph.get("toolResults") or []
            ):
                failures.append("GRAPH_FAIL_RECOMMENDED")
            elif int((graph.get("compositeScore") or {}).get("score") or 0) <= 0:
                failures.append("GRAPH_FAIL_RECOMMENDED")

        total = int(build.get("totalPrice") or 0)
        min_total = expected.get("minTotal")
        max_total = expected.get("maxTotal")
        if min_total is not None and total < int(min_total) and not below_minimum:
            failures.append("BUDGET_GUARD_VIOLATION")
        if max_total is not None and total > int(max_total):
            hard_allowed = bool(expected.get("allowHardConstraintOverBudget")) and bool(required_terms)
            hard_warning = any("HARD_CONSTRAINT_OVER_BUDGET" in warning for warning in warnings)
            if not below_minimum and not (hard_allowed and hard_warning):
                failures.append("BUDGET_GUARD_VIOLATION")
    if expected.get("allowHardConstraintOverBudget") and required_terms:
        budget = expected.get("budgetWon")
        if budget is not None and any(int(build.get("totalPrice") or 0) > int(budget) for build in builds):
            if not any("HARD_CONSTRAINT_OVER_BUDGET" in warning for warning in warnings):
                failures.append("HARD_CONSTRAINT_WARNING_MISSING")
    return failures


def outcome_failures(response: dict[str, Any], expected: dict[str, Any]) -> tuple[list[str], dict[str, bool]]:
    failures: list[str] = []
    outcome = str(expected.get("outcome") or "NEXT_ACTION")
    builds = response.get("builds") or []
    simulation = isinstance(response.get("simulation"), dict)
    board_focus_payload = response.get("boardFocus")
    board_focus = isinstance(board_focus_payload, dict)
    clarification = isinstance(response.get("clarification"), dict)
    next_action = has_next_action(response)
    preview = bool(preview_builds(response))
    metrics = {
        "nextAction": next_action, "preview": preview, "simulation": simulation,
        "boardFocus": board_focus, "clarification": clarification,
    }
    warnings = {str(value) for value in response.get("warnings") or []}
    if "actions" in response:
        failures.append("PUBLIC_ACTIONS_EXPOSED")
    if "ASSUMED_DEFAULT_BUDGET" in warnings:
        failures.append("DEPRECATED_WARNING_EXPOSED")
    required_warnings = {str(value) for value in expected.get("requiredWarnings") or []}
    if not required_warnings.issubset(warnings):
        failures.append("REQUIRED_WARNING_MISSING")
    if len(response.get("quickReplies") or []) < int(expected.get("minQuickReplies", 0)):
        failures.append("QUICK_REPLIES_MISSING")
    clarification_payload = response.get("clarification") or {}
    if expected.get("clarificationEcho"):
        original = str(clarification_payload.get("originalMessage") or "") if isinstance(clarification_payload, dict) else ""
        expected_original = str(expected.get("originalMessage") or "")
        if not original or (expected_original and expected_original not in original):
            failures.append("CLARIFICATION_ECHO_MISSING")
    required_slots = {str(value) for value in expected.get("requiredMissingSlots") or []}
    if required_slots:
        actual_slots = set(clarification_payload.get("missingSlots") or []) if isinstance(clarification_payload, dict) else set()
        if not required_slots.issubset(actual_slots):
            failures.append("CLARIFICATION_SLOTS_MISMATCH")
    forbidden_slots = {str(value) for value in expected.get("forbiddenMissingSlots") or []}
    if forbidden_slots and isinstance(clarification_payload, dict):
        actual_slots = set(clarification_payload.get("missingSlots") or [])
        if forbidden_slots & actual_slots:
            failures.append("FORBIDDEN_CLARIFICATION_SLOT")
    if expected.get("forbidClarification") and clarification:
        failures.append("UNEXPECTED_CLARIFICATION")
    normalized_message = str(response.get("message") or "").lower().replace(" ", "")
    required_message_terms = [str(value).lower().replace(" ", "") for value in expected.get("messageContainsAll") or []]
    if required_message_terms and not all(term in normalized_message for term in required_message_terms):
        failures.append("REQUIRED_MESSAGE_TERM_MISSING")
    alternative_message_terms = [str(value).lower().replace(" ", "") for value in expected.get("messageContainsAny") or []]
    if alternative_message_terms and not any(term in normalized_message for term in alternative_message_terms):
        failures.append("REQUIRED_MESSAGE_ALTERNATIVE_MISSING")
    required_quick_replies = {str(value) for value in expected.get("requiredQuickReplies") or []}
    if required_quick_replies and not required_quick_replies.issubset({str(value) for value in response.get("quickReplies") or []}):
        failures.append("REQUIRED_QUICK_REPLY_MISSING")
    if outcome == "BUILDS" and not builds:
        failures.append("EXPECTED_BUILDS_MISSING")
    elif outcome == "BUILDS_OR_NEXT_ACTION" and not (builds or next_action):
        failures.append("DEAD_END")
    elif outcome == "PREVIEW" and not preview:
        failures.append("EXPECTED_PREVIEW_MISSING")
    elif outcome == "PREVIEW_OR_NEXT_ACTION" and not (preview or next_action):
        failures.append("DEAD_END")
    elif outcome == "SIMULATION" and not simulation:
        failures.append("EXPECTED_SIMULATION_MISSING")
    elif outcome == "SIMULATION_OR_NEXT_ACTION" and not (simulation or next_action):
        failures.append("DEAD_END")
    elif outcome == "SIMULATION_OR_CLARIFICATION" and not (simulation or clarification or response.get("quickReplies")):
        failures.append("EXPECTED_SIMULATION_OR_CLARIFICATION_MISSING")
    elif outcome == "BOARD_FOCUS" and not board_focus:
        failures.append("BOARD_FOCUS_MISSING")
    elif outcome == "CLARIFICATION" and not clarification:
        failures.append("EXPECTED_CLARIFICATION_MISSING")
    elif outcome == "CLARIFICATION_OR_NEXT_ACTION" and not (clarification or next_action):
        failures.append("DEAD_END")
    elif outcome == "NEXT_ACTION" and not next_action:
        failures.append("DEAD_END")
    if simulation and builds:
        failures.append("SIMULATION_MUTATION")
    if board_focus:
        categories = board_focus_payload.get("categories") or []
        if (board_focus_payload.get("type") != "PART_LOCATION"
                or not isinstance(categories, list)
                or not categories
                or any(category not in CATEGORIES for category in categories)
                or not str(board_focus_payload.get("label") or "").strip()):
            failures.append("BOARD_FOCUS_SCHEMA_INVALID")
        expected_categories = expected.get("expectedBoardFocusCategories") or []
        if expected_categories and categories != expected_categories:
            failures.append("BOARD_FOCUS_CATEGORY_MISMATCH")
        if builds or simulation:
            failures.append("BOARD_FOCUS_MUTATION")
    if expected.get("forbidBoardFocus") and board_focus:
        failures.append("BOARD_FOCUS_FALSE_POSITIVE")
    answer_types = set(expected.get("answerTypes") or [])
    if answer_types and response.get("answerType") not in answer_types:
        failures.append("ANSWER_TYPE_MISMATCH")
    if not category_present(response, expected.get("expectedCategory")):
        failures.append("CATEGORY_MISMATCH")
    required_terms = [str(term).lower().replace(" ", "") for term in expected.get("requiredTerms") or []]
    if required_terms:
        haystack = visible_text(response).lower().replace(" ", "")
        if not all(term in haystack for term in required_terms):
            failures.append("HARD_CONSTRAINT_LOST")
    required_candidate_terms = [
        str(term).lower().replace(" ", "") for term in expected.get("requiredCandidateTerms") or []
    ]
    if required_candidate_terms and "PART_CONSTRAINT_NOT_FOUND" not in warnings:
        candidate_texts = [
            str(reply).lower().replace(" ", "")
            for reply in response.get("quickReplies") or []
            if "담아" in str(reply)
        ]
        candidate_texts.extend(
            str(item.get("name") or "").lower().replace(" ", "")
            for build in builds
            for item in build.get("items") or []
        )
        if not candidate_texts or any(
                not all(term in candidate for term in required_candidate_terms)
                for candidate in candidate_texts):
            failures.append("CANDIDATE_CONSTRAINT_LOST")
    return failures, metrics


def visible_language_failures(response: dict[str, Any]) -> list[str]:
    normalized = visible_text(response).lower().replace("_", " ")
    return ["INTERNAL_TERM_EXPOSED"] if any(term in normalized for term in VISIBLE_TECH_TERMS) else []


def response_fingerprint(response: dict[str, Any]) -> str:
    compact = {
        "answerType": response.get("answerType"),
        "message": response.get("message"),
        "builds": [
            {
                "tier": build.get("tier"), "totalPrice": build.get("totalPrice"),
                "items": sorted((item.get("category"), item.get("partId"), item.get("quantity")) for item in build.get("items") or []),
            }
            for build in response.get("builds") or []
        ],
        "simulation": response.get("simulation"),
        "boardFocus": response.get("boardFocus"),
        "quickReplies": response.get("quickReplies"),
        "clarification": response.get("clarification"),
    }
    return hashlib.sha256(json.dumps(compact, ensure_ascii=False, sort_keys=True).encode()).hexdigest()


def select_cases(cases: list[dict[str, Any]], args: argparse.Namespace) -> list[dict[str, Any]]:
    if args.stage == "gate":
        selected = [case for case in cases if case.get("stage") == "gate"]
    elif args.stage == "cache":
        selected = [case for case in cases if case.get("group") == "CACHE_MINIMAL_PAIR"]
    elif args.stage == "stability":
        selected = [case for case in cases if case.get("risk") == "HIGH"][:80]
    else:
        selected = list(cases)
    if args.case_id:
        ids = set(args.case_id)
        selected = [case for case in selected if case.get("id") in ids]
    if args.limit is not None:
        selected = selected[: max(0, args.limit)]
    return selected


def validate_cases(cases: list[dict[str, Any]]) -> None:
    if len(cases) != 700:
        raise RuntimeError(f"expected exactly 700 scenarios, found {len(cases)}")
    ids = [str(case.get("id") or "") for case in cases]
    if any(not case_id for case_id in ids) or len(set(ids)) != len(ids):
        raise RuntimeError("scenario ids must be non-empty and unique")
    expected_groups = {
        "BUDGET_BUILD": 100, "PART_RECOMMEND": 100, "DRAFT_PREVIEW": 100,
        "SIMULATION": 90, "CLARIFICATION": 80, "ROBUSTNESS": 70,
        "CACHE_MINIMAL_PAIR": 60, "BOARD_FOCUS": 100,
    }
    actual_groups = Counter(str(case.get("group")) for case in cases)
    if dict(actual_groups) != expected_groups:
        raise RuntimeError(f"unexpected group distribution: {dict(actual_groups)}")
    if sum(case.get("stage") == "gate" for case in cases) != 200:
        raise RuntimeError("gate stage must contain exactly 200 scenarios")
    pair_groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for case in cases:
        if case.get("contextMode") not in {"NONE", "CURRENT_DRAFT"}:
            raise RuntimeError(f"{case['id']}: invalid contextMode")
        ui_context = case.get("uiContext")
        if ui_context is not None and (
                not isinstance(ui_context, dict)
                or ui_context.get("surface") != "SELF_QUOTE"
                or "BOARD_PART_FOCUS" not in (ui_context.get("capabilities") or [])):
            raise RuntimeError(f"{case['id']}: invalid uiContext")
        turns = case.get("turns")
        if not isinstance(turns, list) or not turns:
            raise RuntimeError(f"{case['id']}: turns must be a non-empty list")
        for index, item in enumerate(turns, 1):
            if not isinstance(item, dict) or not isinstance(item.get("expect"), dict):
                raise RuntimeError(f"{case['id']} turn {index}: expect is required")
            if not item.get("message") and "quickReplyIndex" not in item:
                raise RuntimeError(f"{case['id']} turn {index}: message or quickReplyIndex is required")
            answer_types = item["expect"].get("answerTypes") or []
            if any(answer_type not in ANSWER_TYPES for answer_type in answer_types):
                raise RuntimeError(f"{case['id']} turn {index}: invalid answerTypes")
        if case.get("minimalPairGroup"):
            pair_groups[str(case["minimalPairGroup"])].append(case)
    if len(pair_groups) != 30 or any(len(rows) != 2 for rows in pair_groups.values()):
        raise RuntimeError("cache corpus must contain 30 complete minimal-pair groups")


def run_turn(
    client: ApiClient,
    case: dict[str, Any],
    turn_index: int,
    turn_spec: dict[str, Any],
    draft: dict[str, Any],
    previous_response: dict[str, Any] | None,
    recent_builds: list[dict[str, Any]],
    verify_graph: bool,
) -> dict[str, Any]:
    message = turn_spec.get("message")
    if "quickReplyIndex" in turn_spec:
        quick_replies = (previous_response or {}).get("quickReplies") or []
        reply_index = int(turn_spec.get("quickReplyIndex") or 0)
        if reply_index >= len(quick_replies):
            message = None
        else:
            message = quick_replies[reply_index]
    request_body: dict[str, Any] = {"message": message or ""}
    ui_context = turn_spec.get("uiContext") or case.get("uiContext")
    if ui_context:
        request_body["uiContext"] = ui_context
    if recent_builds:
        request_body["currentBuilds"] = recent_builds[:3]
    if case.get("contextMode") == "CURRENT_DRAFT":
        request_body["currentQuoteDraft"] = draft
    clarification = (previous_response or {}).get("clarification") or {}
    if turn_spec.get("useClarification") or (turn_spec.get("useClarificationIfPresent") and clarification):
        original = clarification.get("originalMessage") if isinstance(clarification, dict) else None
        if original:
            request_body["clarificationContext"] = {"originalMessage": original}

    status, response, latency_ms, transport = client.request(
        "POST", "/api/ai/build-chat", request_body,
        headers={"X-BuildGraph-AI-Profile": PROFILE},
    )
    expected = turn_spec.get("expect") or {}
    failures: list[str] = []
    metrics = {"nextAction": False, "preview": False, "simulation": False, "clarification": False}
    if status != 200:
        failures.append("HTTP_ERROR")
    if not response_schema_valid(response):
        failures.append("SCHEMA_INVALID")
    else:
        outcome_errors, metrics = outcome_failures(response, expected)
        failures.extend(outcome_errors)
        failures.extend(build_fact_failures(client, response, expected, verify_graph))
        failures.extend(visible_language_failures(response))
    return {
        "caseId": case["id"], "group": case["group"], "stage": case["stage"],
        "risk": case["risk"], "turn": turn_index + 1, "profile": PROFILE,
        "message": message, "requestBody": request_body, "status": status,
        "response": response, "latencyMs": latency_ms, "transport": transport,
        "expected": expected,
        "failures": sorted(set(failures)), "metrics": metrics,
        "success": not failures, "responseFingerprint": response_fingerprint(response) if response_schema_valid(response) else None,
        "minimalPairGroup": case.get("minimalPairGroup"),
        "cacheExpectation": case.get("cacheExpectation", "NONE"),
    }


def apply_pair_oracles(results: list[dict[str, Any]]) -> None:
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for result in results:
        if result.get("minimalPairGroup"):
            grouped[str(result["minimalPairGroup"])].append(result)
    for rows in grouped.values():
        if len(rows) != 2:
            continue
        expectation = rows[0].get("cacheExpectation")
        same = rows[0].get("responseFingerprint") == rows[1].get("responseFingerprint")
        if expectation == "DIFFERENT_REQUIRED" and same:
            for row in rows:
                row["failures"] = sorted(set(row["failures"] + ["CACHE_INTENT_COLLISION"]))
                row["success"] = False
        if expectation == "SAME_ALLOWED" and not same:
            for row in rows:
                row.setdefault("warningsEval", []).append("SEMANTIC_PAIR_DIFFERED")


def percentile(values: list[int], pct: int) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    position = (len(ordered) - 1) * (pct / 100)
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return float(ordered[lower])
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def source_commit() -> str | None:
    try:
        result = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
            timeout=5,
        )
        return result.stdout.strip() or None
    except (OSError, subprocess.SubprocessError):
        return None


def summarize(
    results: list[dict[str, Any]],
    draft_before: dict,
    draft_after: dict,
    args: argparse.Namespace,
    wall_duration_ms: int,
) -> dict[str, Any]:
    turn_counts = Counter("PASS" if row["success"] else "FAIL" for row in results)
    rows_by_case: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in results:
        rows_by_case[str(row["caseId"])].append(row)
    case_counts = Counter(
        "PASS" if all(row["success"] for row in case_rows) else "FAIL"
        for case_rows in rows_by_case.values()
    )
    by_group: dict[str, dict[str, int]] = {}
    for group in sorted({row["group"] for row in results}):
        group_cases = {
            case_id: case_rows for case_id, case_rows in rows_by_case.items()
            if case_rows[0]["group"] == group
        }
        by_group[group] = dict(Counter(
            "PASS" if all(row["success"] for row in case_rows) else "FAIL"
            for case_rows in group_cases.values()
        ))
    latencies = [int(row["latencyMs"]) for row in results if row["status"] == 200]
    failure_counts = Counter(failure for row in results for failure in row["failures"])
    critical = sum(count for failure, count in failure_counts.items() if failure in CRITICAL_FAILURES)
    draft_changed = draft_before != draft_after
    acceptance_passed = case_counts.get("FAIL", 0) == 0 and critical == 0 and not draft_changed
    duration_seconds = max(wall_duration_ms / 1000, 0.001)
    status_counts = Counter(str(row.get("status")) for row in results)
    return {
        "generatedAt": dt.datetime.now(REPORT_TIMEZONE).isoformat(),
        "sourceCommit": source_commit(),
        "baseUrl": args.base_url, "profile": PROFILE, "stage": args.stage,
        "cacheState": args.cache_state, "workers": max(1, int(args.workers)),
        "caseCount": len({row["caseId"] for row in results}),
        "turnCount": len(results), "overall": dict(case_counts), "turnOverall": dict(turn_counts), "byGroup": by_group,
        "failureCounts": dict(failure_counts), "criticalFailureCount": critical,
        "acceptancePassed": acceptance_passed,
        "latency": {
            "avgMs": round(statistics.mean(latencies), 1) if latencies else 0,
            "p95Ms": round(percentile(latencies, 95), 1),
            "maxMs": max(latencies) if latencies else 0,
            "over5Seconds": sum(value > 5_000 for value in latencies),
            "over5SecondsRate": round(sum(value > 5_000 for value in latencies) / len(latencies), 4) if latencies else 0,
        },
        "load": {
            "wallDurationMs": wall_duration_ms,
            "throughputTurnsPerSecond": round(len(results) / duration_seconds, 3),
            "httpStatusCounts": dict(status_counts),
            "retryCount": sum(bool((row.get("transport") or {}).get("retried")) for row in results),
            "reauthenticationCount": sum(bool((row.get("transport") or {}).get("reauthenticated")) for row in results),
        },
        "metrics": {
            "nextActionRate": rate(row["metrics"]["nextAction"] for row in results),
            "previewRate": rate(row["metrics"]["preview"] for row in results if row["group"] == "DRAFT_PREVIEW"),
            "simulationRate": rate(row["metrics"]["simulation"] for row in results if row["group"] == "SIMULATION"),
            "boardFocusRate": rate(
                row["metrics"]["boardFocus"] for row in results
                if (row.get("expected") or {}).get("expectedBoardFocusCategories")
            ),
            "boardFocusVetoPassRate": rate(
                not row["metrics"]["boardFocus"] for row in results
                if (row.get("expected") or {}).get("forbidBoardFocus")
            ),
            "clarificationRate": rate(row["metrics"]["clarification"] for row in results if row["group"] == "CLARIFICATION" and row["turn"] == 1),
        },
        "draftChanged": draft_changed,
        "startDraftFingerprint": draft_before, "endDraftFingerprint": draft_after,
    }


def rate(values: Any) -> float:
    rows = list(values)
    return round(sum(bool(value) for value in rows) / len(rows), 4) if rows else 1.0


def write_outputs(
    results: list[dict[str, Any]], summary: dict[str, Any], args: argparse.Namespace,
) -> tuple[Path, Path, Path]:
    date = dt.datetime.now(REPORT_TIMEZONE).strftime("%Y%m%d")
    suffix = f"-{args.report_suffix}" if args.report_suffix else ""
    output_dir = Path(args.output_dir)
    results_dir = Path(args.results_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    results_dir.mkdir(parents=True, exist_ok=True)
    md_path = output_dir / f"build-chat-scenario-qa-{date}-{args.stage}{suffix}.md"
    json_path = output_dir / f"build-chat-scenario-qa-{date}-{args.stage}{suffix}.json"
    raw_path = results_dir / f"build-chat-scenario-qa-{date}-{args.stage}{suffix}.jsonl"
    json_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    with raw_path.open("w", encoding="utf-8") as handle:
        for row in results:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")

    latency = summary["latency"]
    load = summary["load"]
    lines = [
        "# Build Chat 전체 시나리오 QA 보고서", "",
        f"- 생성 시각: {summary['generatedAt']}", f"- 대상: `{summary['baseUrl']}`",
        f"- 소스 커밋: `{summary.get('sourceCommit') or '확인 불가'}`",
        f"- 모델 profile: `{PROFILE}` (`gpt-5.4-mini`)", f"- 실행 단계: `{args.stage}` / cache 상태: `{args.cache_state}`",
        f"- 동시 실행 workers: {summary['workers']}" + (" (기능 검증용, latency 기준선으로 사용하지 않음)" if summary['workers'] > 1 else ""),
        f"- 시나리오 {summary['caseCount']}건 / 실제 대화 턴 {summary['turnCount']}건",
        f"- 시나리오 판정: PASS {summary['overall'].get('PASS', 0)} / FAIL {summary['overall'].get('FAIL', 0)}",
        f"- 대화 턴 판정: PASS {summary['turnOverall'].get('PASS', 0)} / FAIL {summary['turnOverall'].get('FAIL', 0)}",
        f"- 수용 기준 판정: {'PASS' if summary['acceptancePassed'] else 'FAIL'}",
        f"- 지연: 평균 {latency['avgMs']/1000:.3f}초 / p95 {latency['p95Ms']/1000:.3f}초 / 최대 {latency['maxMs']/1000:.3f}초 / 5초 초과 {latency['over5Seconds']}건 ({latency['over5SecondsRate']:.1%})",
        f"- 실행 시간: {load['wallDurationMs']/1000:.3f}초 / 처리량: {load['throughputTurnsPerSecond']:.3f} turn/s / HTTP 상태: {load['httpStatusCounts']} / 재시도: {load['retryCount']}건",
        f"- 견적초안 변경: {'발생' if summary['draftChanged'] else '없음'}", "",
        "## 그룹별 결과", "", "| 그룹 | PASS | FAIL |", "|---|---:|---:|",
    ]
    for group, counts in summary["byGroup"].items():
        lines.append(f"| {group} | {counts.get('PASS', 0)} | {counts.get('FAIL', 0)} |")
    lines.extend(["", "## 핵심 지표", ""])
    for key, value in summary["metrics"].items():
        lines.append(f"- {key}: {value:.1%}")
    lines.extend(["", "## 실패 유형", ""])
    if summary["failureCounts"]:
        for failure, count in sorted(summary["failureCounts"].items(), key=lambda item: (-item[1], item[0])):
            lines.append(f"- `{failure}`: {count}건")
    else:
        lines.append("- 없음")
    if args.stage == "gate":
        lines.extend(["", "## 게이트 결론", ""])
        if summary["acceptancePassed"]:
            lines.append("- 안전 게이트를 통과했습니다. full/stability/cache/live web 후속 검증을 진행할 수 있습니다.")
        else:
            lines.append("- 안전 게이트를 통과하지 못했습니다. 비용이 큰 full/stability/cache/live web 후속 검증은 중단합니다.")
            lines.append("- 실패 원인을 수정한 뒤 동일한 200개 게이트 시나리오를 먼저 재실행해야 합니다.")
    failed = [row for row in results if not row["success"]]
    lines.extend(["", "## 실패 상세", ""])
    if not failed:
        lines.append("- 없음")
    for row in failed[:100]:
        response = row.get("response") or {}
        expected = row.get("expected") or {}
        totals = [int(build.get("totalPrice") or 0) for build in response.get("builds") or []]
        budget_detail = ""
        if "BUDGET_GUARD_VIOLATION" in row["failures"]:
            budget_detail = (
                f"- 기대 총액 범위: {expected.get('minTotal', '-')}~{expected.get('maxTotal', '-')}원"
                f" / 실제 추천 총액: {', '.join(f'{total:,}원' for total in totals) or '-'}"
            )
        lines.extend([
            f"### {row['caseId']} / turn {row['turn']}", "",
            f"- 그룹: `{row['group']}` / 지연: {row['latencyMs']/1000:.3f}초 / HTTP {row['status']}",
            f"- 입력: {row.get('message')}", f"- 실패: {', '.join(row['failures'])}",
            *([budget_detail] if budget_detail else []),
            f"- 응답: {str(response.get('message') or '')[:500]}", "",
        ])
    lines.extend([
        "## 판정 메모", "",
        "- 사용자 표시 언어 판정은 message, quickReplies, build/simulation 표시 필드만 본다. 내부 warning code는 제외한다.",
        "- 변경 요청은 최신 계약의 draft-edit 미리보기 build를 우선 기대하며, 챗 API가 실제 quote draft를 수정하면 치명 실패다.",
        "- raw 응답은 `.qa-results/`에만 저장되며 Git 커밋 대상이 아니다.",
    ])
    md_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return md_path, json_path, raw_path


def manual_review_sample(results: list[dict[str, Any]], results_dir: Path, stage: str) -> Path | None:
    if stage != "full":
        return None
    selected = sorted(results, key=lambda row: hashlib.sha256(f"{row['caseId']}:{row['turn']}".encode()).hexdigest())[:120]
    output = []
    for row in selected:
        output.append({
            "caseId": row["caseId"], "group": row["group"], "message": row["message"],
            "response": row["response"],
            "review": {"intentUnderstanding": None, "factualHonesty": None, "naturalness": None,
                       "clarificationRestraint": None, "nextActionUsefulness": None, "note": ""},
        })
    path = results_dir / f"build-chat-manual-review-{dt.datetime.now(REPORT_TIMEZONE).strftime('%Y%m%d')}.json"
    path.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path


def execute_case(
    client: ApiClient,
    case: dict[str, Any],
    repetition: int,
    qa_draft: dict[str, Any],
    verify_graph: bool,
    *,
    worker_index: int = 1,
) -> list[dict[str, Any]]:
    before_case = draft_fingerprint(client.current_draft()) if case.get("contextMode") == "CURRENT_DRAFT" else None
    previous_response: dict[str, Any] | None = None
    recent_builds: list[dict[str, Any]] = []
    rows: list[dict[str, Any]] = []
    for index, turn_spec in enumerate(case["turns"]):
        row = run_turn(client, case, index, turn_spec, qa_draft, previous_response, recent_builds, verify_graph)
        row["repeat"] = repetition
        row["workerIndex"] = worker_index
        rows.append(row)
        previous_response = row["response"] if isinstance(row["response"], dict) else None
        if previous_response and previous_response.get("builds"):
            recent_builds = previous_response["builds"][:3]
    if before_case is not None:
        after_case = draft_fingerprint(client.current_draft())
        if before_case != after_case:
            rows[-1]["failures"] = sorted(set(rows[-1]["failures"] + ["DRAFT_MUTATED"]))
            rows[-1]["success"] = False
    return rows


def worker_email(base_email: str, worker_index: int) -> str:
    local, separator, domain = base_email.partition("@")
    if not separator:
        raise ValueError("parallel QA requires a valid email address")
    return f"{local}-worker-{worker_index}@{domain}"


def main() -> int:
    args = parse_args()
    cases = json.loads(Path(args.cases).read_text(encoding="utf-8"))
    validate_cases(cases)
    if args.validate_only:
        print(f"Validated {len(cases)} Build Chat QA scenarios for {PROFILE}")
        return 0
    selected = select_cases(cases, args)
    if not selected:
        raise RuntimeError("no scenarios selected")
    repeat = args.repeat if args.repeat is not None else (3 if args.stage == "stability" else 1)
    workers = max(1, int(args.workers))
    if workers > 1 and args.stage in {"cache", "stability"}:
        raise RuntimeError("cache/stability stages must run with --workers 1")
    client = ApiClient(
        args.base_url,
        args.user_email,
        args.user_password,
        name=args.user_name,
        provision=not args.no_provision_user,
    )
    active_parts = client.active_parts()
    persisted_draft = client.current_draft()
    qa_draft = verified_virtual_draft(client, active_parts)
    start_draft = draft_fingerprint(persisted_draft)
    results: list[dict[str, Any]] = []
    run_started = time.perf_counter()
    total_runs = len(selected) * repeat
    progress_path = Path(args.results_dir) / "build-chat-scenario-qa-progress.json"
    progress_path.parent.mkdir(parents=True, exist_ok=True)

    def update_progress(completed: int) -> None:
        progress_path.write_text(json.dumps({
            "completed": completed,
            "total": total_runs,
            "workers": workers,
            "stage": args.stage,
            "updatedAt": dt.datetime.now(REPORT_TIMEZONE).isoformat(),
        }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    update_progress(0)
    if workers == 1:
        run_number = 0
        for repetition in range(1, repeat + 1):
            for case in selected:
                run_number += 1
                print(f"[{run_number}/{total_runs}] {case['id']} repeat={repetition}", flush=True)
                results.extend(execute_case(client, case, repetition, qa_draft, args.verify_graph))
                update_progress(run_number)
    else:
        worker_local = threading.local()
        worker_counter = count(1)
        worker_lock = threading.Lock()
        worker_records: list[tuple[int, ApiClient, dict[str, Any]]] = []

        def get_worker_client() -> tuple[ApiClient, int]:
            if not hasattr(worker_local, "client"):
                with worker_lock:
                    worker_index = next(worker_counter)
                worker_client = ApiClient(
                    args.base_url,
                    worker_email(args.user_email, worker_index),
                    args.user_password,
                    name=f"{args.user_name} Worker {worker_index}",
                    provision=not args.no_provision_user,
                )
                worker_client.part_cache.update({str(part["id"]): part for part in active_parts if part.get("id")})
                worker_start = draft_fingerprint(worker_client.current_draft())
                worker_local.client = worker_client
                worker_local.worker_index = worker_index
                with worker_lock:
                    worker_records.append((worker_index, worker_client, worker_start))
            return worker_local.client, worker_local.worker_index

        def run_parallel_case(ordinal: int, repetition: int, case: dict[str, Any]) -> tuple[int, list[dict[str, Any]]]:
            worker_client, worker_index = get_worker_client()
            return ordinal, execute_case(
                worker_client, case, repetition, qa_draft, args.verify_graph, worker_index=worker_index
            )

        jobs = [
            (ordinal, repetition, case)
            for ordinal, (repetition, case) in enumerate(
                ((rep, case) for rep in range(1, repeat + 1) for case in selected), start=1
            )
        ]
        completed = 0
        ordered_rows: dict[int, list[dict[str, Any]]] = {}
        with ThreadPoolExecutor(max_workers=workers, thread_name_prefix="build-chat-qa") as executor:
            future_map = {
                executor.submit(run_parallel_case, ordinal, repetition, case): (ordinal, case["id"], repetition)
                for ordinal, repetition, case in jobs
            }
            for future in as_completed(future_map):
                ordinal, case_id, repetition = future_map[future]
                result_ordinal, case_rows = future.result()
                ordered_rows[result_ordinal] = case_rows
                completed += 1
                print(f"[{completed}/{total_runs}] {case_id} repeat={repetition}", flush=True)
                update_progress(completed)
        for ordinal in sorted(ordered_rows):
            results.extend(ordered_rows[ordinal])
        parallel_mutation = False
        for worker_index, worker_client, worker_start in worker_records:
            if draft_fingerprint(worker_client.current_draft()) != worker_start:
                parallel_mutation = True
                worker_rows = [row for row in results if row.get("workerIndex") == worker_index]
                if worker_rows:
                    worker_rows[-1]["failures"] = sorted(set(worker_rows[-1]["failures"] + ["DRAFT_MUTATED"]))
                    worker_rows[-1]["success"] = False
        if parallel_mutation:
            persisted_draft = dict(persisted_draft)
            persisted_draft["parallelWorkerDraftChanged"] = True
    apply_pair_oracles(results)
    wall_duration_ms = round((time.perf_counter() - run_started) * 1000)
    end_draft = draft_fingerprint(client.current_draft())
    if workers > 1 and persisted_draft.get("parallelWorkerDraftChanged"):
        end_draft = dict(end_draft)
        end_draft["parallelWorkerDraftChanged"] = True
    summary = summarize(results, start_draft, end_draft, args, wall_duration_ms)
    md_path, json_path, raw_path = write_outputs(results, summary, args)
    review_path = manual_review_sample(results, Path(args.results_dir), args.stage)
    print(md_path)
    print(json_path)
    print(raw_path)
    if review_path:
        print(review_path)
    if args.strict and not summary["acceptancePassed"]:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
