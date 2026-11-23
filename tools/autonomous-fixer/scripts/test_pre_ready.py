import hashlib
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from unittest.mock import patch

import pre_ready


class IntakeTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.issue = {
            "number": 12,
            "state": "open",
            "labels": [],
            "comments": 3,
            "title": "Nested imports",
            "body": "Observed behavior",
        }
        self.write("issue.json", self.issue)
        self.write("current.json", self.issue)
        self.env = {
            "ISSUE_NUMBER": "12",
            "GITHUB_REPOSITORY": "org/repo",
            "GITHUB_EVENT_NAME": "issues",
            "GITHUB_OUTPUT": str(self.root / "output"),
            "GITHUB_STEP_SUMMARY": str(self.root / "summary.md"),
        }

    def write(self, name, data):
        path = self.root / name
        path.parent.mkdir(exist_ok=True)
        path.write_text(json.dumps(data))

    def run_script(self, mode):
        stdout = StringIO()
        with (
            patch.dict(os.environ, self.env, clear=True),
            patch.object(sys, "argv", ["pre_ready.py", mode, str(self.root)]),
            redirect_stdout(stdout),
        ):
            pre_ready.main()
        return stdout.getvalue().strip()

    def capture_snapshot(self):
        state = {
            "issue_at_intake": "Nested imports\n\nObserved behavior",
            "known_access_and_scope": "",
        }
        self.write(
            "request/request.json",
            {"state": state, "questions": {"provider-only": "omitted"}},
        )
        return self.run_script("snapshot")

    def assess_snapshot(self, decision="diagnose"):
        snapshot = self.capture_snapshot()
        self.env.update(
            GITHUB_EVENT_NAME="workflow_dispatch",
            ASSESSMENT_SNAPSHOT=snapshot,
            ENGINEER_DECISION=decision,
        )
        return snapshot

    def write_report(self, choice="ready", **kwargs):
        self.write(
            "jev/report.json",
            dict(
                version="hosted",
                duration_ms=11,
                selection={"pre_ready_action": choice, "missing_information": "access"},
                response={"id": "request-42", "usage": {"cost": 0.00004}},
                **kwargs,
            ),
        )

    def agreement(self):
        return pre_ready.read_json(self.root / "agreement.json")

    def summary(self):
        self.run_script("summary")
        return (self.root / "summary.md").read_text()

    def test_skip_before_call(self):
        for change in [
            {"state": "closed"},
            {"labels": [{"name": "ready-for-agent"}]},
            {"pull_request": {}},
        ]:
            packet, reason = pre_ready.prepare(
                {**self.issue, **change}, "12", "org/repo"
            )
            self.assertIsNone(packet)
            self.assertTrue(reason)

    def test_manual_agreement_archives_only_the_reviewed_snapshot(self):
        assessed = self.assess_snapshot("ready")
        self.write_report()
        result = self.summary()
        self.assertIn(
            "Engineer assessment before this run: **Ready for implementation**.", result
        )
        self.assertIn("Jev recommendation: **Ready for implementation**.", result)
        self.assertIn("Agreement status: `agree`.", result)
        self.assertEqual(
            self.agreement(),
            {
                "repository": "org/repo",
                "issue": 12,
                "status": "agree",
                "engineer_decision": "ready",
                "jev_recommendation": "ready",
                "assessed_snapshot_sha256": assessed,
                "captured_snapshot_sha256": assessed,
            },
        )
        content = (self.root / "reviewed-snapshot.json").read_bytes()
        self.assertEqual(hashlib.sha256(content).hexdigest(), assessed)
        self.assertEqual(
            json.loads(content)["state"],
            {
                "issue_at_intake": "Nested imports\n\nObserved behavior",
                "known_access_and_scope": "",
            },
        )
        self.assertNotIn(b"provider-only", content)

    def test_edit_before_classification_excludes_the_assessment(self):
        assessed = self.assess_snapshot("ready")
        self.write(
            "issue.json",
            {**self.issue, "body": "Changed after the engineer read the snapshot"},
        )
        captured = self.capture_snapshot()
        self.assertNotEqual(assessed, captured)
        result = self.summary()
        self.assertIn("The assessment and the current issue snapshot differ.", result)
        self.assertNotIn("Jev recommendation", result)
        self.assertFalse((self.root / "reviewed-snapshot.json").exists())
        self.assertEqual(self.agreement()["status"], "snapshot_mismatch")
        self.assertEqual(self.agreement()["assessed_snapshot_sha256"], assessed)
        self.assertEqual(self.agreement()["captured_snapshot_sha256"], captured)
        self.assertIsNone(self.agreement()["jev_recommendation"])

    def test_summary_never_recommends_from_a_stale_snapshot(self):
        self.assess_snapshot()
        self.write("current.json", {**self.issue, "body": "Different requirements"})
        self.write_report()
        result = self.summary()
        self.assertIn("The issue changed during triage.", result)
        self.assertNotIn("Jev recommendation", result)
        self.assertIn("request-42", result)
        self.assertIn("Comments excluded from this snapshot: 3", result)
        self.assertIn("Snapshot taken at ", result)
        self.assertEqual(self.agreement()["status"], "issue_changed")
        self.assertIsNone(self.agreement()["jev_recommendation"])

    def test_discussion_change_keeps_a_matching_title_and_description_comparable(self):
        assessed = self.assess_snapshot()
        self.write("issue.json", {**self.issue, "comments": 4})
        self.assertEqual(self.capture_snapshot(), assessed)
        self.write("current.json", {**self.issue, "comments": 5})
        self.write_report("request_info")
        result = self.summary()
        self.assertIn("now has 5 comments", result)
        self.assertIn("request-42", result)
        self.assertIn("Missing information: `access`", result)
        self.assertIn("Jev recommendation: **Ask for missing information**", result)
        self.assertEqual(self.agreement()["status"], "disagree")

    def test_abstentions_and_provider_failures_do_not_count_as_agreement(self):
        for human, jev in [
            ("uncertain", "ready"),
            ("ready", "uncertain"),
            ("uncertain", "uncertain"),
        ]:
            with self.subTest(human=human, jev=jev):
                self.assess_snapshot(human)
                self.write_report(jev)
                self.summary()
                self.assertEqual(self.agreement()["status"], "abstained")
        self.write_report(error="Provider unavailable")
        self.summary()
        self.assertEqual(self.agreement()["status"], "classification_failed")
        self.assertIsNone(self.agreement()["jev_recommendation"])

    def test_automatic_summary_uses_no_engineer_assessment(self):
        self.write(
            "jev/report.json",
            {
                "version": "hosted",
                "duration_ms": 11,
                "selection": {"pre_ready_action": "ready"},
                "response": {
                    "id": "request-42\nUnexpected markup",
                    "usage": {"cost": 0.00004},
                },
            },
        )
        result = self.summary()
        self.assertIn("Automatic triage for a newly opened issue.", result)
        self.assertIn("Jev recommendation: **Ready for implementation**.", result)
        self.assertNotIn("Engineer assessment", result)
        self.assertNotIn(self.issue["body"], result)
        self.assertNotIn("Unexpected markup", result)
        self.assertFalse((self.root / "agreement.json").exists())
        self.assertFalse((self.root / "reviewed-snapshot.json").exists())
        self.assertEqual((self.root / "recommendation.md").read_text(), result)

    def test_ready_issue_emits_ineligible_output_without_classifier_packet(self):
        self.write(
            "issue.json", {**self.issue, "labels": [{"name": "ready-for-agent"}]}
        )
        self.run_script("prepare")
        self.assertEqual((self.root / "output").read_text(), "eligible=false\n")
        self.assertFalse((self.root / "packet.json").exists())
        result = self.summary()
        self.assertIn("The issue already has the ready-for-agent label.", result)
        self.assertNotIn("Jev recommendation", result)

    def test_invalid_snapshot_hash_blocks_a_manual_run(self):
        self.env.update(
            GITHUB_EVENT_NAME="workflow_dispatch",
            ENGINEER_DECISION="ready",
            ASSESSMENT_SNAPSHOT="old",
        )
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            self.run_script("prepare")
        self.assertFalse((self.root / "packet.json").exists())

    def test_id_validation(self):
        with self.assertRaises(ValueError):
            pre_ready.prepare(self.issue, "../12", "org/repo")
