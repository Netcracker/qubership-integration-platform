"""Scripted clarification regression over the raw SSE and saved run evidence."""

import json
import unittest
from pathlib import Path

EVIDENCE = Path("ai-service/target/serialized-clarifications/evidence.json")
ROLE_QUESTION = "Which roles may call this external HTTP trigger?"
SECOND_QUESTION = "Which description should the entry use?"


class SerializedClarificationRegression(unittest.TestCase):
    def test_focused_repair_is_visible_once_on_the_same_run(self):
        self.assertTrue(
            EVIDENCE.is_file(),
            "the Java flow test must write raw clarification evidence",
        )
        evidence = json.loads(EVIDENCE.read_text(encoding="utf-8"))
        first = evidence["firstDecision"]
        answer_sse = evidence["answerSse"]
        archive = evidence["archive"]
        draft = json.dumps(evidence["draft"])
        run = evidence["run"]

        self.assertEqual(ROLE_QUESTION, first["question"])
        self.assertNotIn(SECOND_QUESTION, first["question"])
        self.assertTrue(first["id"])
        second_offer = _second_offer(answer_sse)
        self.assertTrue(second_offer)
        self.assertNotEqual(first["id"], second_offer)
        cards = _decision_cards(answer_sse)
        self.assertTrue(cards)
        self.assertTrue(
            all(
                question == SECOND_QUESTION and offer_id == second_offer
                for question, offer_id in cards
            )
        )
        self.assertNotIn(ROLE_QUESTION, _decision_questions(answer_sse))
        self.assertNotIn("Which RBAC role should I use", answer_sse)
        self.assertIn(SECOND_QUESTION, answer_sse)
        self.assertEqual(evidence["runId"], _run_id(run))
        self.assertIn("abc", draft)
        self.assertNotIn("CLARIFICATION_ANSWER_NO_PROGRESS", answer_sse)
        offer_turns = [
            turn
            for turn in archive["turns"]
            if _offer(turn) == first["id"] and "abc" in (turn.get("rawText") or "")
        ]
        self.assertEqual(1, len(offer_turns))
        answer = _clarification_answer(run, first["id"])
        self.assertTrue(answer)
        self.assertEqual(first["id"], answer["offerId"])
        self.assertEqual("ANSWER", answer["disposition"])
        self.assertTrue(answer["answerTurnId"])


def _decision_questions(sse):
    return [question for question, _offer_id in _decision_cards(sse)]


def _unwrap_sse(line):
    payload = "" if line is None else line.strip()
    wrapped = False
    while payload.startswith("data:"):
        wrapped = True
        payload = payload[len("data:") :].strip()
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


def _second_offer(sse):
    for question, offer_id in _decision_cards(sse):
        if question == SECOND_QUESTION and offer_id:
            return offer_id
    return ""


def _decision_cards(sse):
    cards = []
    lines = sse.replace("\r\n", "\n").split("\n")
    for index, line in enumerate(lines):
        if _unwrap_sse(line) != "event: decision":
            continue
        for follower in lines[index + 1 :]:
            payload = _unwrap_sse(follower)
            if payload is None or payload == "":
                continue
            if payload.startswith("event:"):
                break
            if payload.startswith("{"):
                card = json.loads(payload)
                cards.append((card.get("question", ""), card.get("id", "")))
            break
    return cards


def _clarification_answer(run, offer_id):
    transitions = run.get("transitions") if isinstance(run, dict) else None
    if not isinstance(transitions, list):
        return {}
    matched = None
    for transition in transitions:
        clarification = (
            transition.get("clarification") if isinstance(transition, dict) else None
        )
        answer = (
            clarification.get("answer") if isinstance(clarification, dict) else None
        )
        if isinstance(answer, dict) and answer.get("offerId") == offer_id:
            matched = answer
    return matched or {}


if __name__ == "__main__":
    unittest.main()
