package fixer

import (
	"fmt"
	"strings"
)

var humanEvents = []string{"fix", "label-added", "label-removed", "stop", "resume", "retry", "answer", "requirements", "status"}
var callbackEvents = []string{"run-started", "run-finished", "verifying", "decision-request", "no-change", "candidate", "hosted-candidate"}

func active(s *State) []int {
	out := []int{}
	for i, e := range s.Executions {
		if e.Status == "planned" || e.Status == "running" {
			out = append(out, i)
		}
	}
	return out
}
func openQuestions(s *State) []int {
	out := []int{}
	for i, d := range s.Decisions {
		if d.Status == "open" {
			out = append(out, i)
		}
	}
	return out
}
func addEffect(s *State, kind string, e Execution, p EffectPayload) {
	id := fmt.Sprintf("%s/%s/%d", e.ID, kind, s.Generation)
	for _, old := range s.Effects {
		if old.ID == id {
			return
		}
	}
	s.Effects = append(s.Effects, Effect{ID: id, Kind: kind, ExecutionID: e.ID, Generation: s.Generation, Status: "pending", Payload: p})
}
func dispatch(s *State) {
	trusted := s.ControllerSHA
	if trusted == "" {
		trusted = s.Contract.TrustedSHA
	}
	e := Execution{ID: fmt.Sprintf("%s/execution/%d", s.TaskID, len(s.Executions)+1), Mode: s.Mode, AttemptID: s.Attempt.ID, Generation: s.Generation, ContractVersion: s.Contract.Version, TrustedSHA: trusted, Stage: "work", Status: "planned"}
	s.Executions = append(s.Executions, e)
	s.Replan = false
	s.Phase = "queued"
	addEffect(s, "dispatch", e, EffectPayload{ContractVersion: s.Contract.Version, TrustedSHA: trusted})
}
func supersede(s *State) {
	for i := range s.Effects {
		if s.Effects[i].Status == "pending" && !continuesAfterGeneration(s.Effects[i].Kind) {
			s.Effects[i].Status = "superseded"
		}
	}
	s.Candidate = nil
	s.HostedCandidate = nil
}

func continuesAfterGeneration(kind string) bool {
	return kind == "cancel" || kind == "report-hosted"
}
func question(s *State, id, reason, text, condition, kind string) *Decision {
	s.Decisions = append(s.Decisions, Decision{ID: id, Version: 1, ContractVersion: s.Contract.Version, Reason: reason, Question: text, ResumeCondition: condition, Kind: kind, Status: "open"})
	s.StopEpisodes = append(s.StopEpisodes, StopEpisode{ID: id + "/stop", DecisionID: id, Reason: reason, Status: "open"})
	s.Phase = "waiting-human"
	return &s.Decisions[len(s.Decisions)-1]
}
func continueWork(s *State) {
	if len(active(s)) > 0 || len(openQuestions(s)) > 0 || s.Desired != "running" {
		return
	}
	if s.NoChangeConfirmed {
		s.Phase = "completed"
		s.Outcome = "confirmed-no-change"
	} else {
		s.Generation++
		dispatch(s)
	}
	for i := range s.StopEpisodes {
		if s.StopEpisodes[i].Status == "resolved" {
			s.StopEpisodes[i].Status = "closed"
		}
	}
}
func authorization(s *State) {
	s.Generation++
	supersede(s)
	question(s, fmt.Sprintf("%s/authorization/%d", s.TaskID, s.Generation), "access-missing", "The initiating user no longer has permission.", "An allowed user resumes the task.", "authorization")
}
func sameRun(e Execution, source Source) bool {
	return e.Run != nil && e.Run.RunID == source.RunID && e.Run.RunAttempt == source.RunAttempt
}
func validSource(e Event) bool {
	switch e.Type {
	case "label-added":
		return e.Source.Kind == "label" && e.Source.Action == "added"
	case "label-removed":
		return e.Source.Kind == "label" && e.Source.Action == "removed"
	case "requirements":
		return e.Source.Kind == "issue"
	}
	if contains(humanEvents, e.Type) {
		return e.Source.Kind == "comment"
	}
	return contains(callbackEvents, e.Type) && e.Source.Kind == "workflow"
}
func Transition(previous *State, event Event, context TransitionContext) (Result, error) {
	reject := func(reason string) (Result, error) { return Result{previous, "ignored", reason}, nil }
	if previous != nil {
		if err := ValidateState(previous); err != nil {
			return Result{}, err
		}
	}
	if event.Type == "effect-confirmed" {
		if !context.Internal || previous == nil {
			return reject("Internal event required.")
		}
		s := cloneState(previous)
		for i := range s.Effects {
			e := &s.Effects[i]
			if e.ID == event.EffectID && e.Status == "pending" {
				if !continuesAfterGeneration(e.Kind) && e.Generation != s.Generation {
					return reject("Effect generation is stale.")
				}
				e.Status = "confirmed"
				e.Result = event.EffectResult
				s = cloneState(s)
				s.Revision++
				if err := ValidateState(s); err != nil {
					return Result{}, err
				}
				return Result{s, "accepted", "Effect observed."}, nil
			}
		}
		return reject("Effect is no longer pending.")
	}
	key := EventKey(event)
	if key == "" || !validSource(event) {
		return reject("Unsupported event source or edited command.")
	}
	if previous != nil && contains(previous.Receipts, key) {
		return reject("Event already handled.")
	}
	if contains(humanEvents, event.Type) && !Allowed(context.AllowedActors, event.Actor) {
		return reject("Actor is not allowed.")
	}
	if contains(callbackEvents, event.Type) && !context.TrustedWorkflow {
		return reject("Workflow provenance is not trusted.")
	}
	if previous == nil && event.Type != "fix" && event.Type != "label-added" {
		return reject("Task has not started.")
	}
	if previous != nil && (previous.Phase == "recovery-required" || previous.Phase == "completed") {
		return reject("Task cannot continue in this phase.")
	}
	if event.Type == "status" {
		return Result{previous, "status", "State read without changes."}, nil
	}
	if previous != nil && previous.HostedPublication != nil && contains([]string{"fix", "label-added", "resume", "retry", "answer", "requirements"}, event.Type) {
		return reject("The hosted draft pull request is published. Continue in the pull request.")
	}
	if context.IssueUnavailable && contains([]string{"fix", "label-added", "resume", "retry", "answer"}, event.Type) {
		return reject("The issue must be open and have the ready-for-agent label.")
	}
	s := cloneState(previous)
	if s == nil {
		var err error
		s, err = NewState(context.Task, event.Actor)
		if err != nil {
			return Result{}, err
		}
	}
	if shaPattern.MatchString(context.WorkflowSHA) {
		s.ControllerSHA = context.WorkflowSHA
	}
	if context.Mode != "" {
		s.Mode = context.Mode
	}
	accept := func(reason string) (Result, error) {
		s.Receipts = append(s.Receipts, key)
		s.Revision++
		if err := ValidateState(s); err != nil {
			return Result{}, err
		}
		return Result{s, "accepted", reason}, nil
	}
	if previous == nil {
		dispatch(s)
		return accept("Task accepted.")
	}
	if event.Type == "fix" || event.Type == "label-added" {
		return reject("Use resume or retry for an existing task.")
	}
	switch event.Type {
	case "stop", "label-removed":
		if s.Desired == "stopped" {
			return reject("Stop already requested.")
		}
		s.Desired = "stopped"
		s.Generation++
		supersede(s)
		for _, i := range active(s) {
			addEffect(s, "cancel", s.Executions[i], EffectPayload{})
		}
		s.Phase = "stopped"
		if len(active(s)) > 0 {
			s.Phase = "stopping"
		}
		return accept("Stop requested; completion must be observed.")
	case "requirements":
		if !nonempty(event.Requirements) {
			return reject("Requirements must not be empty.")
		}
		if s.Contract.matchesRequirements(event.Requirements) {
			return reject("Requirements already match the contract.")
		}
		s.Contract.Version++
		s.Contract.Requirements = ""
		s.Contract.RequirementsSHA256 = requirementsSHA256(event.Requirements)
		s.Contract.Criteria = []string{}
		s.Generation++
		supersede(s)
		for _, i := range openQuestions(s) {
			s.Decisions[i].Status = "superseded"
		}
		for i := range s.StopEpisodes {
			if s.StopEpisodes[i].Status == "open" {
				s.StopEpisodes[i].Status = "superseded"
			}
		}
		s.NoChangeConfirmed = false
		s.Replan = true
		s.Initiator = strings.ToLower(event.Actor)
		if s.Desired == "running" && len(active(s)) == 0 && !context.IssueUnavailable {
			dispatch(s)
		}
		return accept("Requirements changed; earlier evidence cannot authorize publication.")
	case "answer":
		idx := -1
		for _, i := range openQuestions(s) {
			if s.Decisions[i].ID == event.QuestionID {
				idx = i
			}
		}
		if idx < 0 {
			return reject("Answer does not match the current question.")
		}
		d := &s.Decisions[idx]
		if d.Version != event.QuestionVersion || d.ContractVersion != s.Contract.Version || d.ContractVersion != event.ContractVersion || !nonempty(event.Answer) {
			return reject("Answer does not match the current question.")
		}
		if d.Kind == "no-change" && !contains([]string{"confirm", "continue"}, event.Answer) {
			return reject("Answer confirm or continue.")
		}
		d.Status = "answered"
		d.Answer = &Answer{Actor: strings.ToLower(event.Actor), Text: event.Answer, Source: key}
		for i := range s.StopEpisodes {
			if s.StopEpisodes[i].DecisionID == d.ID {
				s.StopEpisodes[i].Status = "resolved"
			}
		}
		s.Initiator = strings.ToLower(event.Actor)
		if d.Kind == "no-change" {
			s.NoChangeConfirmed = event.Answer == "confirm"
		}
		continueWork(s)
		return accept("Answer recorded.")
	case "resume", "retry":
		if len(active(s)) > 0 {
			return reject("Wait for active executions to finish.")
		}
		for _, i := range openQuestions(s) {
			d := &s.Decisions[i]
			if d.Kind != "authorization" {
				return reject("Answer the open questions first.")
			}
			d.Status = "answered"
			d.Answer = &Answer{Actor: strings.ToLower(event.Actor), Text: "resume", Source: key}
			for j := range s.StopEpisodes {
				if s.StopEpisodes[j].DecisionID == d.ID {
					s.StopEpisodes[j].Status = "resolved"
				}
			}
		}
		if !contains([]string{"stopped", "waiting-human"}, s.Phase) {
			return reject("Task is not waiting for continuation.")
		}
		s.Initiator = strings.ToLower(event.Actor)
		s.Desired = "running"
		if event.Type == "retry" {
			s.NoChangeConfirmed = false
			s.Attempt.Number++
			s.Attempt.ID = fmt.Sprintf("%s/attempt/%d", s.TaskID, s.Attempt.Number)
		}
		continueWork(s)
		return accept("Continuation accepted.")
	}
	idx := -1
	for i, e := range s.Executions {
		if e.ID == event.ExecutionID {
			idx = i
		}
	}
	if idx < 0 {
		return reject("Execution is not registered.")
	}
	e := &s.Executions[idx]
	if event.Type == "run-finished" {
		unadmitted := false
		for _, effect := range s.Effects {
			if effect.ExecutionID == e.ID && effect.Kind == "dispatch" && effect.Status == "confirmed" && effect.Result != nil && effect.Result.RunID == event.Source.RunID && effect.Result.RunAttempt == event.Source.RunAttempt {
				unadmitted = e.Run == nil
			}
		}
		if !sameRun(*e, event.Source) && !unadmitted {
			return reject("Run identity does not match.")
		}
		if !contains([]string{"completed", "cancelled"}, event.Result) {
			return reject("Invalid run result.")
		}
		if !contains([]string{"planned", "running"}, e.Status) {
			return reject("Run already finished.")
		}
		e.Status = event.Result
		if s.Desired == "stopped" && len(active(s)) == 0 {
			s.Phase = "stopped"
			return accept("Execution completion observed.")
		}
		if context.IssueUnavailable {
			return accept("Execution completion observed. The issue is unavailable for continuation.")
		}
		if s.Phase == "waiting-human" && len(openQuestions(s)) == 0 {
			if Allowed(context.AllowedActors, s.Initiator) {
				continueWork(s)
			} else {
				authorization(s)
			}
		} else if s.Replan && len(active(s)) == 0 {
			if Allowed(context.AllowedActors, s.Initiator) {
				dispatch(s)
			} else {
				authorization(s)
			}
		} else if unadmitted && e.Generation == s.Generation && e.ContractVersion == s.Contract.Version && len(openQuestions(s)) == 0 {
			if Allowed(context.AllowedActors, s.Initiator) {
				question(s, fmt.Sprintf("%s/admission/%d", e.ID, e.ContractVersion), "environment-blocked",
					"The worker ended before admission. Inspect the run and resolve the environment failure before continuing.",
					"An allowed user answers this version of the question after resolving the environment failure.", "decision")
			} else {
				authorization(s)
			}
		}
		return accept("Execution completion observed.")
	}
	if event.Generation != s.Generation || event.ContractVersion != s.Contract.Version || e.Generation != s.Generation || e.ContractVersion != s.Contract.Version || s.Desired != "running" {
		return reject("Execution result is stale.")
	}
	if context.IssueUnavailable {
		return reject("The issue must be open and have the ready-for-agent label.")
	}
	if !Allowed(context.AllowedActors, s.Initiator) {
		authorization(s)
		for _, i := range active(s) {
			addEffect(s, "cancel", s.Executions[i], EffectPayload{})
		}
		return accept("Continuation requires an allowed user.")
	}
	if event.Type == "run-started" {
		if e.Mode == "hosted" && !context.RequirementsCurrent {
			return reject("The issue changed before the worker was admitted.")
		}
		if e.Status != "planned" || e.Run != nil {
			return reject("Execution was already admitted.")
		}
		matched := false
		for _, effect := range s.Effects {
			if effect.ExecutionID == e.ID && effect.Kind == "dispatch" && effect.Status == "confirmed" && effect.Result != nil && effect.Result.RunID == event.Source.RunID && effect.Result.RunAttempt == event.Source.RunAttempt {
				matched = true
			}
		}
		if !matched {
			return reject("Run does not match the observed dispatch.")
		}
		e.Run = &RunIdentity{RunID: event.Source.RunID, RunAttempt: event.Source.RunAttempt}
		e.Status = "running"
		s.Phase = "working"
		return accept("Execution admitted.")
	}
	if !sameRun(*e, event.Source) || e.Status != "running" {
		return reject("Callback does not match an admitted run.")
	}
	switch event.Type {
	case "verifying":
		if s.Phase != "working" {
			return reject("Work is not ready for verification.")
		}
		s.Phase = "verifying"
		return accept("Verification remains in the same execution.")
	case "decision-request", "no-change":
		if !contains([]string{"working", "verifying", "waiting-human"}, s.Phase) {
			return reject("Execution cannot ask a question in this phase.")
		}
		if !nonempty(event.QuestionID) {
			return reject("Question ID must be new.")
		}
		for _, d := range s.Decisions {
			if d.ID == event.QuestionID {
				return reject("Question ID must be new.")
			}
		}
		if !nonempty(event.Question) || !nonempty(event.ResumeCondition) {
			return reject("Question and resume condition are required.")
		}
		reason := event.Reason
		kind := "decision"
		if event.Type == "no-change" {
			reason = "invalid"
			kind = "no-change"
			if !nonempty(event.EvidenceRef) || !contains([]string{"already-fixed", "expected-behavior", "duplicate", "unreachable"}, event.Basis) {
				return reject("No-change proposal requires a basis and evidence.")
			}
		}
		if !contains(StopReasons, reason) {
			return reject("Unknown stop reason.")
		}
		if event.HostedIssueResult != nil && (!validHostedIssueResult(event.HostedIssueResult) || event.Type != "decision-request" || e.Mode != "hosted" || event.HostedIssueResult.Run != *e.Run || !contains([]string{"decision-required", "verification-blocked"}, reason)) {
			return reject("The signed report does not match this worker decision.")
		}
		d := question(s, event.QuestionID, reason, event.Question, event.ResumeCondition, kind)
		if event.HostedIssueResult != nil {
			d.HostedResult = event.HostedIssueResult
			addEffect(s, "report-hosted", *e, EffectPayload{DecisionID: d.ID})
		}
		if kind == "no-change" {
			d.Basis = event.Basis
			d.EvidenceRef = event.EvidenceRef
		}
		return accept("Human decision requested.")
	case "candidate":
		if s.Phase != "verifying" || len(openQuestions(s)) > 0 {
			return reject("Candidate is not ready for publication.")
		}
		if !context.EvidenceVerified || context.ExpectedCandidate == nil {
			return reject("Artifact evidence has not been verified.")
		}
		expected := *context.ExpectedCandidate
		expected.TaskID = s.TaskID
		expected.Repository = s.Repository
		expected.AttemptID = s.Attempt.ID
		expected.ExecutionID = e.ID
		expected.Generation = s.Generation
		expected.ContractVersion = s.Contract.Version
		expected.BaseSHA = s.Contract.BaseSHA
		expected.ScopePaths = s.Contract.ScopePaths
		expected.AllowCIChanges = s.Contract.AllowCIChanges
		expected.Run = ManifestRun{RunID: e.Run.RunID, RunAttempt: e.Run.RunAttempt, Workflow: "agent-work", TrustedSHA: e.TrustedSHA}
		if err := ValidateManifest(event.Manifest, expected); err != nil {
			return reject(err.Error())
		}
		s.Candidate = event.Manifest
		s = cloneState(s)
		s.Phase = "publishing"
		addEffect(s, "publish", s.Executions[idx], EffectPayload{CandidateSHA: s.Candidate.CandidateSHA})
		return accept("Verified candidate recorded.")
	case "hosted-candidate":
		if s.Phase != "working" || !context.RequirementsCurrent || executionMode(e.Mode) != "hosted" || event.HostedCandidate == nil || len(openQuestions(s)) > 0 {
			return reject("The hosted candidate no longer matches the active work.")
		}
		if err := validateHostedCandidate(s, *event.HostedCandidate); err != nil {
			return reject(err.Error())
		}
		s.HostedCandidate = event.HostedCandidate
		s.Phase = "awaiting-publication"
		return accept("The worker reported a candidate. Authorized publication is pending.")
	}
	return reject("Unsupported transition.")
}
