import copy
import io
import json
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError

from tools import benchmark_build_chat_scenario_qa as qa


class FakeClient:
    def __init__(self, parts=None, graph=None):
        self.parts = parts or {}
        self.graph = graph

    def part(self, part_id):
        return self.parts.get(part_id)

    def resolve_graph(self, build):
        return self.graph


def build_response(total=300_000, price=300_000, tool_status="PASS"):
    return {
        "answerType": "BUDGET",
        "message": "현재 판매 중인 부품으로 구성했습니다.",
        "builds": [{
            "id": "qa-build", "tier": "balanced", "title": "추천 조합",
            "totalPrice": total, "items": [{
                "partId": "part-1", "category": "GPU", "name": "RTX 5090 테스트",
                "price": price, "quantity": 1,
            }],
            "toolResults": [{"tool": "compatibility", "status": tool_status}],
        }],
        "warnings": [],
    }


class BuildChatScenarioQaTest(unittest.TestCase):
    def test_parallel_worker_email_is_isolated_and_stable(self):
        self.assertEqual(
            "build-chat-qa-worker-3@example.com",
            qa.worker_email("build-chat-qa@example.com", 3),
        )

    def test_api_client_reauthenticates_once_after_access_token_expiry(self):
        class FakeResponse:
            status = 200

            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc, traceback):
                return False

            @staticmethod
            def read():
                return b'{"status":"UP"}'

        client = qa.ApiClient.__new__(qa.ApiClient)
        client.base_url = "http://localhost:8082"
        client.email = "build-chat-qa@example.com"
        client.password = "passw0rd!"
        client.token = "expired-token"
        expired = HTTPError(
            "http://localhost:8082/api/health",
            401,
            "Unauthorized",
            {},
            io.BytesIO(b'{"code":"UNAUTHORIZED"}'),
        )

        with patch.object(client, "_login", return_value="fresh-token") as login, patch(
                "urllib.request.urlopen", side_effect=[expired, FakeResponse()]):
            status, payload, _, meta = client.request("GET", "/api/health")

        self.assertEqual(200, status)
        self.assertEqual({"status": "UP"}, payload)
        self.assertEqual("fresh-token", client.token)
        self.assertTrue(meta["reauthenticated"])
        login.assert_called_once_with(client.email, client.password)

    def test_fixed_corpus_contract(self):
        cases = json.loads(Path("tools/build_chat_scenario_qa_cases.json").read_text(encoding="utf-8"))
        qa.validate_cases(cases)
        self.assertEqual(700, len(cases))
        self.assertEqual(200, sum(case["stage"] == "gate" for case in cases))
        self.assertEqual(100, sum(case["group"] == "BOARD_FOCUS" for case in cases))

    def test_latest_response_schema(self):
        self.assertTrue(qa.response_schema_valid(build_response()))
        invalid = build_response()
        invalid.pop("warnings")
        self.assertFalse(qa.response_schema_valid(invalid))

    def test_verified_draft_rejects_incomplete_build(self):
        client = FakeClient({"part-1": {
            "id": "part-1", "category": "GPU", "name": "RTX 5090 테스트",
            "manufacturer": "QA", "price": 300_000, "attributes": {},
        }})
        draft = qa.draft_from_verified_build(client, build_response()["builds"][0])
        self.assertIsNone(draft)

    def test_verified_draft_normalizes_multi_module_ram_kit_quantity(self):
        parts = {}
        items = []
        for category in qa.CATEGORIES:
            part_id = f"part-{category.lower()}"
            attributes = {"moduleCount": 2} if category == "RAM" else {}
            parts[part_id] = {
                "id": part_id, "category": category, "name": f"QA {category}",
                "manufacturer": "QA", "price": 100_000, "attributes": attributes,
            }
            items.append({
                "partId": part_id, "category": category,
                "quantity": 2 if category == "RAM" else 1,
            })
        draft = qa.draft_from_verified_build(FakeClient(parts), {"items": items})
        self.assertIsNotNone(draft)
        ram = next(item for item in draft["items"] if item["category"] == "RAM")
        self.assertEqual(1, ram["quantity"])

    def test_build_price_and_total_are_checked_against_parts(self):
        client = FakeClient({"part-1": {"id": "part-1", "name": "RTX 5090 테스트", "price": 300_000}})
        self.assertEqual([], qa.build_fact_failures(client, build_response(), {}, False))

        price_mismatch = build_response(price=299_000, total=299_000)
        self.assertIn("PART_PRICE_MISMATCH", qa.build_fact_failures(client, price_mismatch, {}, False))

        total_mismatch = build_response(total=299_000)
        self.assertIn("BUILD_TOTAL_MISMATCH", qa.build_fact_failures(client, total_mismatch, {}, False))

    def test_tool_and_graph_failures_are_blocking(self):
        part = {"id": "part-1", "name": "RTX 5090 테스트", "price": 300_000}
        client = FakeClient({"part-1": part}, {"toolResults": [{"status": "FAIL"}], "compositeScore": {"score": 0}})
        failures = qa.build_fact_failures(client, build_response(tool_status="FAIL"), {}, True)
        self.assertIn("TOOL_FAIL_RECOMMENDED", failures)
        self.assertIn("GRAPH_FAIL_RECOMMENDED", failures)

    def test_minimum_counterproposal_allows_graph_price_fail_only(self):
        part = {"id": "part-1", "name": "RTX 5090 테스트", "price": 300_000}
        graph = {
            "toolResults": [{"tool": "price", "status": "FAIL"}],
            "compositeScore": {"score": 755},
        }
        response = build_response()
        response["warnings"] = ["BUDGET_BELOW_MINIMUM"]
        failures = qa.build_fact_failures(FakeClient({"part-1": part}, graph), response, {}, True)
        self.assertNotIn("GRAPH_FAIL_RECOMMENDED", failures)

    def test_min_budget_mode_does_not_treat_graph_price_overrun_as_compatibility_failure(self):
        part = {"id": "part-1", "name": "RTX 5090 테스트", "price": 300_000}
        graph = {
            "toolResults": [{"tool": "price", "status": "FAIL"}],
            "compositeScore": {"score": 800},
        }
        failures = qa.build_fact_failures(
            FakeClient({"part-1": part}, graph),
            build_response(),
            {"budgetMode": "MIN", "minTotal": 300_000},
            True,
        )
        self.assertNotIn("GRAPH_FAIL_RECOMMENDED", failures)

    def test_budget_guard_allows_explicit_minimum_counterproposal(self):
        client = FakeClient({"part-1": {"id": "part-1", "name": "RTX 5090 테스트", "price": 300_000}})
        response = build_response()
        response["warnings"] = ["BUDGET_BELOW_MINIMUM"]
        failures = qa.build_fact_failures(client, response, {"maxTotal": 100_000}, False)
        self.assertNotIn("BUDGET_GUARD_VIOLATION", failures)

    def test_budget_guard_rejects_unexplained_overrun(self):
        client = FakeClient({"part-1": {"id": "part-1", "name": "RTX 5090 테스트", "price": 300_000}})
        failures = qa.build_fact_failures(client, build_response(), {"maxTotal": 100_000}, False)
        self.assertIn("BUDGET_GUARD_VIOLATION", failures)

    def test_hard_constraint_over_budget_requires_warning(self):
        client = FakeClient({"part-1": {"id": "part-1", "name": "RTX 5090 테스트", "price": 300_000}})
        expected = {
            "budgetWon": 100_000,
            "requiredTerms": ["5090"],
            "allowHardConstraintOverBudget": True,
        }
        failures = qa.build_fact_failures(client, build_response(), expected, False)
        self.assertIn("HARD_CONSTRAINT_WARNING_MISSING", failures)
        warned = build_response()
        warned["warnings"] = ["HARD_CONSTRAINT_OVER_BUDGET"]
        self.assertNotIn(
            "HARD_CONSTRAINT_WARNING_MISSING",
            qa.build_fact_failures(client, warned, expected, False),
        )

    def test_negated_constraint_cannot_appear_in_build_items(self):
        client = FakeClient({"part-1": {"id": "part-1", "name": "RTX 5090 테스트", "price": 300_000}})
        failures = qa.build_fact_failures(
            client,
            build_response(),
            {"forbiddenBuildTerms": ["5090"]},
            False,
        )
        self.assertIn("NEGATED_CONSTRAINT_INCLUDED", failures)

    def test_public_actions_and_removed_default_budget_warning_are_rejected(self):
        response = build_response()
        response["actions"] = []
        response["warnings"] = ["ASSUMED_DEFAULT_BUDGET"]
        failures, _ = qa.outcome_failures(response, {"outcome": "NEXT_ACTION"})
        self.assertIn("PUBLIC_ACTIONS_EXPOSED", failures)
        self.assertIn("DEPRECATED_WARNING_EXPOSED", failures)

    def test_clarification_contract_checks_echo_slots_and_chips(self):
        response = {
            "answerType": "GENERAL",
            "message": "용도와 예산을 알려주세요.",
            "builds": [],
            "warnings": ["LOW_INFORMATION"],
            "quickReplies": ["사무용 100만원", "게이밍 200만원", "게이밍 300만원"],
            "clarification": {
                "originalMessage": "컴퓨터 하나 맞춰줘",
                "missingSlots": ["budget", "useCase"],
            },
        }
        expected = {
            "outcome": "CLARIFICATION",
            "requiredWarnings": ["LOW_INFORMATION"],
            "minQuickReplies": 3,
            "clarificationEcho": True,
            "originalMessage": "컴퓨터 하나 맞춰줘",
            "requiredMissingSlots": ["budget", "useCase"],
        }
        failures, _ = qa.outcome_failures(response, expected)
        self.assertEqual([], failures)

    def test_simulation_cannot_include_mutation_build(self):
        response = build_response()
        response["answerType"] = "GENERAL"
        response["simulation"] = {"category": "GPU", "summary": "비교"}
        failures, _ = qa.outcome_failures(response, {"outcome": "SIMULATION"})
        self.assertIn("SIMULATION_MUTATION", failures)

    def test_board_focus_contract_checks_categories_and_read_only_shape(self):
        response = {
            "answerType": "GENERAL",
            "message": "CPU · RAM 위치를 현재 구성도에서 강조했습니다.",
            "builds": [],
            "warnings": [],
            "boardFocus": {
                "type": "PART_LOCATION",
                "categories": ["CPU", "RAM"],
                "label": "CPU · RAM 위치",
            },
        }
        failures, metrics = qa.outcome_failures(
            response,
            {"outcome": "BOARD_FOCUS", "expectedBoardFocusCategories": ["CPU", "RAM"]},
        )
        self.assertEqual([], failures)
        self.assertTrue(metrics["boardFocus"])

        mismatched = copy.deepcopy(response)
        mismatched["boardFocus"]["categories"] = ["RAM"]
        failures, _ = qa.outcome_failures(
            mismatched,
            {"outcome": "BOARD_FOCUS", "expectedBoardFocusCategories": ["CPU", "RAM"]},
        )
        self.assertIn("BOARD_FOCUS_CATEGORY_MISMATCH", failures)

    def test_board_focus_veto_and_mutation_are_blocking(self):
        response = {
            "answerType": "GENERAL",
            "message": "RAM 위치를 강조했습니다.",
            "builds": [],
            "warnings": [],
            "boardFocus": {
                "type": "PART_LOCATION",
                "categories": ["RAM"],
                "label": "RAM 위치",
            },
        }
        failures, _ = qa.outcome_failures(response, {"outcome": "NEXT_ACTION", "forbidBoardFocus": True})
        self.assertIn("BOARD_FOCUS_FALSE_POSITIVE", failures)

        response["builds"] = build_response()["builds"]
        failures, _ = qa.outcome_failures(response, {"outcome": "BOARD_FOCUS"})
        self.assertIn("BOARD_FOCUS_MUTATION", failures)

    def test_clarification_category_can_be_verified_from_echoed_original_message(self):
        response = {
            "answerType": "GENERAL",
            "message": "리안리 216과 현재 4000D의 쿨링 차이를 더 확인해볼게요.",
            "builds": [],
            "warnings": [],
            "clarification": {
                "missingSlots": [],
                "originalMessage": "현재 견적의 케이스를 리안리 216 케이스로 바꾸면?",
            },
        }

        failures, _ = qa.outcome_failures(
            response,
            {"outcome": "SIMULATION_OR_CLARIFICATION", "expectedCategory": "CASE"},
        )

        self.assertEqual([], failures)

    def test_explicit_part_constraint_must_hold_for_every_candidate_chip(self):
        response = {
            "answerType": "PART",
            "message": "RTX 5080 후보입니다.",
            "builds": [],
            "warnings": [],
            "quickReplies": ["RTX 5080 A 견적에 담아줘", "RTX 5090 B 견적에 담아줘"],
        }
        failures, _ = qa.outcome_failures(
            response,
            {"outcome": "NEXT_ACTION", "requiredCandidateTerms": ["5080"]},
        )
        self.assertIn("CANDIDATE_CONSTRAINT_LOST", failures)

    def test_preview_shape_is_recognized(self):
        response = build_response()
        response["builds"][0]["tier"] = "draft-edit"
        failures, metrics = qa.outcome_failures(response, {"outcome": "PREVIEW"})
        self.assertEqual([], failures)
        self.assertTrue(metrics["preview"])

    def test_different_cache_pair_cannot_return_identical_payload(self):
        response = build_response()
        rows = [
            {"minimalPairGroup": "pair", "cacheExpectation": "DIFFERENT_REQUIRED", "responseFingerprint": qa.response_fingerprint(response), "failures": [], "success": True},
            {"minimalPairGroup": "pair", "cacheExpectation": "DIFFERENT_REQUIRED", "responseFingerprint": qa.response_fingerprint(copy.deepcopy(response)), "failures": [], "success": True},
        ]
        qa.apply_pair_oracles(rows)
        self.assertFalse(rows[0]["success"])
        self.assertIn("CACHE_INTENT_COLLISION", rows[0]["failures"])


if __name__ == "__main__":
    unittest.main()
