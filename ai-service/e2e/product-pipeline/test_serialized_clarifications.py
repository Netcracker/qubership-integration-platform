"""Scripted clarification regression over the raw SSE and saved run evidence."""

import json
import unittest
from pathlib import Path


EVIDENCE = Path("ai-service/target/serialized-clarifications/evidence.json")
ROLE_QUESTION = "Which roles may call this external HTTP trigger?"
SECOND_QUESTION = "Which description should the entry use?"


class SerializedClarificationRegression(unittest.TestCase):
    def test_focused_repair_is_visible_once_on_the_same_run(self):
        self.assertTrue(EVIDENCE.is_file(), "the Java flow test must write raw clarification evidence")
        evidence = json.loads(EVIDENCE.read_text(encoding="utf-8"))
        first = evidence["firstDecision"]
        answer_sse = evidence["answerSse"]
        archive = evidence["archive"]
        draft = json.dumps(evidence["draft"])
        run = evidence["run"]

        self.assertEqual(ROLE_QUESTION, first["question"])
        self.assertNotIn(SECOND_QUESTION, first["question"])
        self.assertTrue(first["id"])
        self.assertNotIn("Which RBAC role should I use", answer_sse)
        self.assertIn(SECOND_QUESTION, answer_sse)
        self.assertNotIn(ROLE_QUESTION, _decision_questions(answer_sse))
        self.assertEqual(evidence["runId"], _run_id(run))
        self.assertIn("abc", draft)
        self.assertNotIn("CLARIFICATION_ANSWER_NO_PROGRESS", answer_sse)
        offer_turns = [
            turn for turn in archive["turns"]
            if _offer(turn) == first["id"] and "abc" in (turn.get("rawText") or "")
        ]
        self.assertEqual(1, len(offer_turns))
        self.assertTrue(_has_answer_transition(run, first["id"]))


def _decision_questions(sse):
    questions = []
    lines = sse.replace("\r\n", "\n").split("\n")
    for index, line in enumerate(lines):
        if _unwrap_sse(line) != "event: decision":
            continue
        for follower in lines[index + 1:]:
            payload = _unwrap_sse(follower)
            if payload is None or payload == "":
                continue
            if payload.startswith("event:"):
                break
            if payload.startswith("{"):
                questions.append(json.loads(payload).get("question", ""))
            break
    return questions


def _unwrap_sse(line):
    payload = "" if line is None else line.strip()
    wrapped = False
    while payload.startswith("data:"):
        wrapped = True
        payload = payload[len("data:"):].strip()
    if wrapped:
        return payload
    return None if payload == "" else payload


def _offer(turn):
    metadata = turn.get("metadata") or {}
    if isinstance(metadata, str):
        metadata = json.loads(metadata)
    submitted = metadata.get("submitted") if isinstance(metadata, dict) else None
    if isinstance(submitted, dict) and submitted.get("clarificationOfferId"):
        return submitted["clarificationOfferId"]
    if isinstance(metadata, dict):
        return metadata.get("clarificationOfferId")
    return None


def _run_id(run):
    if isinstance(run, dict) and "run" in run and isinstance(run["run"], dict):
        return run["run"].get("runId")
    return run.get("runId") if isinstance(run, dict) else None


def _has_answer_transition(run, offer_id):
    document = json.dumps(run)
    return offer_id in document and "accepted clarification" in document


if __name__ == "__main__":
    unittest.main()
