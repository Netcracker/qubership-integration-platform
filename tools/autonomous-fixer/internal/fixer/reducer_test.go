package fixer

import (
	"strings"
	"testing"
)

func testContext() TransitionContext {
	return TransitionContext{Task: Seed{TaskID: "task", Repository: "owner/repo", IssueNumber: 1, BaseSHA: strings.Repeat("a", 40), TrustedSHA: strings.Repeat("b", 40), Requirements: "Fix the reported behavior.", ScopePaths: []string{"engine/"}}, AllowedActors: "alice,bob", WorkflowSHA: strings.Repeat("c", 40), TrustedWorkflow: true}
}
func apply(t *testing.T, s *State, e Event, c TransitionContext) *State {
	t.Helper()
	r, err := Transition(s, e, c)
	if err != nil || r.Disposition != "accepted" {
		t.Fatalf("transition %s: %+v, %v", e.Type, r, err)
	}
	return r.State
}
func humanEvent(kind string, id int64) Event {
	return Event{Type: kind, Actor: "alice", Source: Source{Kind: "comment", ID: id, Action: "created"}}
}
func initial(t *testing.T) (*State, TransitionContext) {
	t.Helper()
	c := testContext()
	return apply(t, nil, humanEvent("fix", 1), c), c
}
func running(t *testing.T) (*State, TransitionContext) {
	t.Helper()
	s, c := initial(t)
	internal := c
	internal.Internal = true
	s = apply(t, s, Event{Type: "effect-confirmed", EffectID: s.Effects[0].ID, EffectResult: &EffectResult{RunID: 99, RunAttempt: 1}}, internal)
	return apply(t, s, callback(s, "run-started", 1), c), c
}
func callback(s *State, kind string, id int64) Event {
	e := s.Executions[len(s.Executions)-1]
	return Event{Type: kind, ExecutionID: e.ID, Generation: e.Generation, ContractVersion: e.ContractVersion, Source: Source{Kind: "workflow", RunID: 99, RunAttempt: 1, CallbackID: id}}
}
func TestProvenanceAndEditedCommands(t *testing.T) {
	s, c := initial(t)
	for _, event := range []Event{{Type: "stop", Actor: "alice", Source: Source{Kind: "comment", ID: 2, Action: "edited"}}, {Type: "stop", Actor: "alice", Source: Source{Kind: "workflow", RunID: 99, RunAttempt: 1, CallbackID: 2}}} {
		r, err := Transition(s, event, c)
		if err != nil || r.Disposition != "ignored" || r.State != s {
			t.Fatalf("source accepted: %+v %v", r, err)
		}
	}
	c.TrustedWorkflow = false
	r, err := Transition(s, callback(s, "run-started", 3), c)
	if err != nil || r.Disposition != "ignored" {
		t.Fatal(r, err)
	}
}
func TestStopWaitsForCompletionAndRejectsStaleEffects(t *testing.T) {
	s, c := initial(t)
	old := s
	s = apply(t, s, humanEvent("stop", 2), c)
	if s.Phase != "stopping" || old.Desired != "running" || old.Effects[0].Status != "pending" {
		t.Fatal("stop mutated previous state or completed early")
	}
	internal := c
	internal.Internal = true
	r, err := Transition(s, Event{Type: "effect-confirmed", EffectID: s.Effects[0].ID, EffectResult: &EffectResult{RunID: 99, RunAttempt: 1}}, internal)
	if err != nil || r.Disposition != "ignored" {
		t.Fatal(r, err)
	}
	s, c = running(t)
	s = apply(t, s, humanEvent("stop", 2), c)
	done := callback(s, "run-finished", 3)
	done.Result = "cancelled"
	s = apply(t, s, done, c)
	if s.Phase != "stopped" {
		t.Fatal(s.Phase)
	}
}
func TestRevokedActorBeforeContinuation(t *testing.T) {
	s, c := running(t)
	c.AllowedActors = "bob"
	s = apply(t, s, callback(s, "verifying", 2), c)
	if s.Phase != "waiting-human" || s.Decisions[0].Kind != "authorization" || s.Generation != 2 {
		t.Fatal("missing authorization decision")
	}
	done := callback(s, "run-finished", 3)
	done.Result = "cancelled"
	s = apply(t, s, done, c)
	resume := humanEvent("resume", 4)
	resume.Actor = "bob"
	s = apply(t, s, resume, c)
	if s.Phase != "queued" || s.Initiator != "bob" || s.Executions[1].TrustedSHA != c.WorkflowSHA {
		t.Fatal("continuation lost current authority or source pin")
	}
}
func TestMultipleQuestionsAndNoChangeConfirmation(t *testing.T) {
	s, c := running(t)
	for i, id := range []string{"one", "two"} {
		e := callback(s, "decision-request", int64(i+2))
		e.QuestionID = id
		e.Question = "Which behavior is required?"
		e.ResumeCondition = "An allowed user answers."
		e.Reason = "decision-required"
		s = apply(t, s, e, c)
	}
	answer := humanEvent("answer", 4)
	answer.QuestionID = "one"
	answer.QuestionVersion = 1
	answer.ContractVersion = 1
	answer.Answer = "Proceed."
	s = apply(t, s, answer, c)
	if len(openQuestions(s)) != 1 {
		t.Fatal("other question was closed")
	}
	answer.Source.ID = 5
	answer.QuestionID = "two"
	s = apply(t, s, answer, c)
	done := callback(s, "run-finished", 6)
	done.Result = "completed"
	s = apply(t, s, done, c)
	if s.Phase != "queued" || len(s.Executions) != 2 {
		t.Fatal("did not continue after all answers and completion")
	}
	s, c = running(t)
	proposal := callback(s, "no-change", 2)
	proposal.QuestionID = "no-change"
	proposal.Question = "Confirm the existing behavior?"
	proposal.ResumeCondition = "An allowed user confirms."
	proposal.Basis = "expected-behavior"
	proposal.EvidenceRef = "artifact:1"
	s = apply(t, s, proposal, c)
	done = callback(s, "run-finished", 3)
	done.Result = "completed"
	s = apply(t, s, done, c)
	if s.Phase != "waiting-human" {
		t.Fatal("completed without confirmation")
	}
	answer = humanEvent("answer", 4)
	answer.QuestionID = "no-change"
	answer.QuestionVersion = 1
	answer.ContractVersion = 1
	answer.Answer = "confirm"
	s = apply(t, s, answer, c)
	if s.Phase != "completed" || s.Outcome != "confirmed-no-change" {
		t.Fatal("confirmation did not complete")
	}
}

func candidateState(t *testing.T) (*State, TransitionContext, *Manifest) {
	t.Helper()
	s, c := running(t)
	s = apply(t, s, callback(s, "verifying", 2), c)
	m, expected := manifestFixture()
	e := s.Executions[0]
	m.TaskID = s.TaskID
	m.AttemptID = s.Attempt.ID
	m.ExecutionID = e.ID
	m.Generation = s.Generation
	m.ContractVersion = s.Contract.Version
	m.Repository = s.Repository
	m.BaseSHA = s.Contract.BaseSHA
	m.ParentSHA = s.Contract.BaseSHA
	m.Run = ManifestRun{RunID: 99, RunAttempt: 1, Workflow: "agent-work", TrustedSHA: e.TrustedSHA}
	m.Files[0].Path = "engine/src/save.go"
	m.Verification.ContractVersion = s.Contract.Version
	expected.CandidateSHA = m.CandidateSHA
	expected.ParentSHA = m.ParentSHA
	c.ExpectedCandidate = &expected
	c.EvidenceVerified = true
	return s, c, m
}
func TestCandidatePublicationRequiresVerifiedCurrentEvidence(t *testing.T) {
	cases := map[string]func(*State, *TransitionContext, *Event){
		"unverified artifact":       func(s *State, c *TransitionContext, e *Event) { c.EvidenceVerified = false },
		"missing expected evidence": func(s *State, c *TransitionContext, e *Event) { c.ExpectedCandidate = nil },
		"wrong candidate": func(s *State, c *TransitionContext, e *Event) {
			c.ExpectedCandidate.CandidateSHA = strings.Repeat("f", 40)
		},
		"wrong parent": func(s *State, c *TransitionContext, e *Event) {
			c.ExpectedCandidate.ParentSHA = strings.Repeat("f", 40)
		},
		"foreign run":    func(s *State, c *TransitionContext, e *Event) { e.Source.RunID++ },
		"missing review": func(s *State, c *TransitionContext, e *Event) { e.Manifest.Reviews = nil },
		"stale review": func(s *State, c *TransitionContext, e *Event) {
			e.Manifest.Reviews[0].CandidateSHA = s.Contract.BaseSHA
		},
		"stale manifest contract": func(s *State, c *TransitionContext, e *Event) { e.Manifest.ContractVersion++ },
		"reused data": func(s *State, c *TransitionContext, e *Event) {
			e.Manifest.Verification.DataBID = e.Manifest.Verification.DataAID
		},
		"different environment": func(s *State, c *TransitionContext, e *Event) { e.Manifest.Verification.EnvironmentID = "other" },
		"stopped":               func(s *State, c *TransitionContext, e *Event) { *s = *apply(t, s, humanEvent("stop", 20), *c) },
		"superseded contract": func(s *State, c *TransitionContext, e *Event) {
			update := Event{Type: "requirements", Actor: "alice", Requirements: "Revised criteria.", Source: Source{Kind: "issue", ID: 1, Revision: 2}}
			*s = *apply(t, s, update, *c)
		},
	}
	for name, change := range cases {
		t.Run(name, func(t *testing.T) {
			s, c, m := candidateState(t)
			e := callback(s, "candidate", 3)
			e.Manifest = m
			change(s, &c, &e)
			r, err := Transition(s, e, c)
			if err != nil || r.Disposition != "ignored" {
				t.Fatal("invalid candidate accepted", r, err)
			}
			for _, effect := range r.State.Effects {
				if effect.Kind == "publish" {
					t.Fatal("invalid evidence created publication")
				}
			}
		})
	}
	s, c, m := candidateState(t)
	event := callback(s, "candidate", 3)
	event.Manifest = m
	published := apply(t, s, event, c)
	if published.Phase != "publishing" || published.Effects[len(published.Effects)-1].Kind != "publish" || published.Candidate == nil {
		t.Fatal("valid evidence did not create publication")
	}
	m.Files[0].Path = "changed"
	if published.Candidate.Files[0].Path == "changed" || s.Candidate != nil {
		t.Fatal("candidate was not cloned")
	}
}
func TestRequirementsSupersedeQuestionsAndOldAnswers(t *testing.T) {
	s, c := running(t)
	q := callback(s, "decision-request", 2)
	q.QuestionID = "q"
	q.Question = "Choose behavior."
	q.ResumeCondition = "An allowed user answers."
	q.Reason = "decision-required"
	s = apply(t, s, q, c)
	update := Event{Type: "requirements", Actor: "alice", Requirements: "Revised criteria.", Source: Source{Kind: "issue", ID: 1, Revision: 2}}
	s = apply(t, s, update, c)
	answer := humanEvent("answer", 4)
	answer.QuestionID = "q"
	answer.QuestionVersion = 1
	answer.ContractVersion = 1
	answer.Answer = "Continue."
	r, err := Transition(s, answer, c)
	if err != nil || r.Disposition != "ignored" || s.Decisions[0].Status != "superseded" {
		t.Fatal(r, err)
	}
}
func TestRetryRunAttemptAndLabelContinuation(t *testing.T) {
	s, c := running(t)
	s = apply(t, s, humanEvent("stop", 10), c)
	done := callback(s, "run-finished", 11)
	done.Result = "completed"
	s = apply(t, s, done, c)
	label := Event{Type: "label-added", Actor: "alice", Source: Source{Kind: "label", ID: 2, Action: "added"}}
	r, err := Transition(s, label, c)
	if err != nil || r.Disposition != "ignored" {
		t.Fatal("label resumed task", err)
	}
	s = apply(t, s, humanEvent("retry", 12), c)
	if s.Attempt.Number != 2 {
		t.Fatal("retry reused attempt")
	}
	internal := c
	internal.Internal = true
	effect := s.Effects[len(s.Effects)-1]
	s = apply(t, s, Event{Type: "effect-confirmed", EffectID: effect.ID, EffectResult: &EffectResult{RunID: 99, RunAttempt: 2}}, internal)
	start := callback(s, "run-started", 13)
	r, err = Transition(s, start, c)
	if err != nil || r.Disposition != "ignored" {
		t.Fatal("old run attempt admitted", err)
	}
	start.Source.RunAttempt = 2
	start.Source.CallbackID = 14
	s = apply(t, s, start, c)
	if s.Executions[1].Run.RunAttempt != 2 {
		t.Fatal("current run attempt was not admitted")
	}
}

func TestUnadmittedCompletionPreservesControlDecisions(t *testing.T) {
	for _, mode := range []string{"stopped", "replan", "revoked", "open-question"} {
		t.Run(mode, func(t *testing.T) {
			s, c := initial(t)
			internal := c
			internal.Internal = true
			s = apply(t, s, Event{Type: "effect-confirmed", EffectID: s.Effects[0].ID, EffectResult: &EffectResult{RunID: 99, RunAttempt: 1}}, internal)
			done := callback(s, "run-finished", 3)
			done.Result = "completed"
			switch mode {
			case "stopped":
				s = apply(t, s, humanEvent("stop", 2), c)
			case "replan":
				s = apply(t, s, Event{Type: "requirements", Actor: "alice", Requirements: "Revised criteria.", Source: Source{Kind: "issue", ID: 1, Revision: 2}}, c)
			case "revoked":
				c.AllowedActors = "bob"
			case "open-question":
				question(s, "existing", "decision-required", "Which behavior is required?", "An allowed user answers.", "decision")
			}
			s = apply(t, s, done, c)
			switch mode {
			case "stopped":
				if s.Phase != "stopped" || len(s.Decisions) != 0 {
					t.Fatal("completion changed the user stop")
				}
			case "replan":
				if s.Phase != "queued" || len(s.Executions) != 2 || len(s.Decisions) != 0 {
					t.Fatal("completion blocked the current requirements")
				}
			case "revoked":
				if len(s.Decisions) != 1 || s.Decisions[0].Kind != "authorization" {
					t.Fatal("completion lost the authorization gate")
				}
			case "open-question":
				if s.Phase != "waiting-human" || len(s.Decisions) != 1 || s.Decisions[0].ID != "existing" {
					t.Fatal("completion replaced an open question")
				}
			}
		})
	}
}
