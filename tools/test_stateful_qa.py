import importlib.util
import json
import sys
import unittest
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / "tools"
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


class StatefulCorpusTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.generator = load_module("stateful_generator", ROOT / "tools" / "generate_stateful_qa_corpora.py")
        cls.audit = load_module("stateful_audit", ROOT / "tools" / "audit_build_chat_stateful.py")
        cls.demo_audit = load_module("demo_stateful_audit", ROOT / "tools" / "audit_demo_journey_stateful.py")

    def test_phase_one_has_fixed_distribution_and_twenty_web_replays(self):
        rows = self.generator.phase1_cases()
        self.audit.validate_cases(rows)
        self.assertEqual(100, len(rows))
        self.assertEqual(20, sum(bool(row["webReplay"]) for row in rows))
        self.assertTrue(all(3 <= len(row["turns"]) <= 5 for row in rows))

    def test_recipient_usecase_budget_chain_is_a_core_guarantee(self):
        rows = self.generator.phase1_cases()
        case = next(row for row in rows if row["id"] == "state-context-01-recipient-usecase-budget")
        self.assertEqual("EMPTY", case["setupDraft"]["template"])
        self.assertEqual("HIGH", case["risk"])
        self.assertTrue(case["webReplay"])
        self.assertIn("CORE_GUARANTEE", case["tags"])
        self.assertEqual([
            "중3 아들 피시 맞춰줄건데 피시 추천해줘",
            "게임용으로 쓸듯",
            "400만원 게이밍 PC 추천해줘",
        ], [turn["message"] for turn in case["turns"]])
        self.assertEqual(["budget", "useCase"], case["turns"][0]["expect"]["requiredMissingSlots"])
        self.assertEqual(["budget"], case["turns"][1]["expect"]["requiredMissingSlots"])
        self.assertEqual(3_500_000, case["turns"][2]["expect"]["minTotal"])
        self.assertEqual(4_500_000, case["turns"][2]["expect"]["maxTotal"])

    def test_core_conversation_expectations_detect_lost_recipient_context(self):
        expected = {
            "outcome": "CLARIFICATION",
            "messageContainsAll": ["게이밍", "예산"],
            "messageContainsAny": ["아들", "아드님"],
            "requiredMissingSlots": ["budget"],
            "forbiddenMissingSlots": ["useCase"],
            "requiredQuickReplies": ["200만원 게이밍 PC 추천해줘"],
        }
        valid = {
            "answerType": "GENERAL",
            "message": "좋아요. 아드님이 사용하실 게이밍 PC로 맞출게요. 이제 예산만 알려주세요.",
            "builds": [],
            "warnings": ["LOW_INFORMATION"],
            "quickReplies": ["200만원 게이밍 PC 추천해줘"],
            "clarification": {"missingSlots": ["budget"], "originalMessage": "중3 아들 PC 게임용"},
        }
        failures, _ = self.audit.outcome_failures(valid, expected)
        self.assertEqual([], failures)
        broken = dict(valid)
        broken["message"] = "용도와 예산을 다시 알려주세요."
        broken["clarification"] = {"missingSlots": ["budget", "useCase"], "originalMessage": "게임용"}
        failures, _ = self.audit.outcome_failures(broken, expected)
        self.assertIn("REQUIRED_MESSAGE_ALTERNATIVE_MISSING", failures)
        self.assertIn("FORBIDDEN_CLARIFICATION_SLOT", failures)

    def test_all_corpora_are_independent_hundred_case_sets(self):
        phase1 = self.generator.phase1_cases()
        phase2 = self.generator.phase2_cases()
        phase3 = self.generator.phase3_cases()
        self.assertEqual([100, 100, 100], [len(phase1), len(phase2), len(phase3)])
        all_ids = [row["id"] for rows in [phase1, phase2, phase3] for row in rows]
        self.assertEqual(len(all_ids), len(set(all_ids)))
        self.assertTrue(all(row["profile"] == "BUILD_CHAT_54_MINI_FAST" for rows in [phase1, phase2, phase3] for row in rows))

    def test_generated_json_matches_generator(self):
        expected = {
            "build_chat_stateful_audit_cases.json": self.generator.phase1_cases(),
            "demo_journey_stateful_audit_cases.json": self.generator.phase2_cases(),
            "user_surface_stateful_audit_cases.json": self.generator.phase3_cases(),
        }
        for filename, rows in expected.items():
            actual = json.loads((ROOT / "tools" / filename).read_text(encoding="utf-8"))
            self.assertEqual(rows, actual, filename)

    def test_failure_classification_requires_repeat_unless_hard_invariant(self):
        first = {"failures": ["TOP3_NOT_BACKFILLED"], "harnessErrors": [], "infraErrors": [], "p0": False}
        verdict, reasons = self.audit.classify(first, None)
        self.assertEqual("SUSPECTED", verdict)
        verdict, reasons = self.audit.classify(first, {"failures": ["TOP3_NOT_BACKFILLED"]})
        self.assertEqual("CONFIRMED_BUG", verdict)
        hard = {"failures": ["TOOL_FAIL_RECOMMENDED"], "harnessErrors": [], "infraErrors": [], "p0": False}
        verdict, reasons = self.audit.classify(hard, None)
        self.assertEqual("CONFIRMED_BUG", verdict)

    def test_phase_two_and_three_group_counts(self):
        phase2 = Counter(row["group"] for row in self.generator.phase2_cases())
        phase3 = Counter(row["group"] for row in self.generator.phase3_cases())
        self.assertEqual({20}, set(phase2.values()))
        self.assertEqual(100, sum(phase3.values()))
        self.assertEqual(100, sum(bool(row["webReplay"]) for row in self.generator.phase3_cases()))
        self.demo_audit.load_cases(ROOT / "tools" / "demo_journey_stateful_audit_cases.json")

    def test_confirmed_variants_collapse_to_independent_root_causes(self):
        relation = {
            "caseId": "state-compat-01-b860-cpu-top3",
            "verdict": "CONFIRMED_BUG",
            "attempts": [{"turns": [{"turn": 1, "failures": ["CATEGORY_MISMATCH"]}]}],
        }
        self.assertEqual(["BG-STATE-01"], self.audit.root_cause_ids(relation))
        follow_up = {
            "caseId": "state-direction-03-cpu-up",
            "verdict": "CONFIRMED_BUG",
            "attempts": [{"turns": [{"turn": 3, "failures": ["CATEGORY_MISMATCH"]}]}],
        }
        self.assertEqual(["BG-STATE-02"], self.audit.root_cause_ids(follow_up))

    def test_report_path_accepts_relative_results_directories(self):
        relative = ROOT / ".qa-results" / "stateful-core-recipient" / "replay.json"
        self.assertEqual(
            ".qa-results/stateful-core-recipient/replay.json",
            self.audit.report_path(relative),
        )


if __name__ == "__main__":
    unittest.main()
