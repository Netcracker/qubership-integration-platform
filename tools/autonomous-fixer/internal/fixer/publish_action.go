package fixer

import (
	"context"
	"encoding/json"
	"fmt"
	"reflect"
	"strconv"
	"strings"
	"time"
)

func (s *actionSession) publicationRun(ctx context.Context, candidate HostedCandidate) (WorkflowRun, error) {
	var run WorkflowRun
	execution, err := s.adapter.execution(candidate.ExecutionID)
	if err != nil {
		return run, err
	}
	path := fmt.Sprintf("/actions/runs/%d/attempts/%d", candidate.Run.RunID, candidate.Run.RunAttempt)
	if err := s.cfg.Client.Request(ctx, "GET", path, nil, &run); err != nil {
		return run, err
	}
	expected := s.adapter.expected(*execution)
	expected.RunID, expected.RunAttempt = candidate.Run.RunID, candidate.Run.RunAttempt
	if execution.Status != "completed" || !MatchWorkerRun(run, expected) || run.Status != "completed" || run.Conclusion != "success" {
		return run, fmt.Errorf("the selected worker run did not complete successfully")
	}
	return run, nil
}

func (s *actionSession) ensureNoPendingStop(ctx context.Context, run WorkflowRun, actors string) error {
	started, err := time.Parse(time.RFC3339Nano, run.CreatedAt)
	if err != nil {
		return fmt.Errorf("worker creation time is unavailable")
	}
	comments, err := s.cfg.Client.List(ctx, fmt.Sprintf("/issues/%d/comments", s.issue.Number), "")
	if err != nil {
		return err
	}
	for _, raw := range comments {
		var comment Comment
		if err := json.Unmarshal(raw, &comment); err != nil {
			return err
		}
		if comment.Body != "/agent stop" || !human(comment.User) || !Allowed(actors, comment.User.Login) || comment.CreatedAt != comment.UpdatedAt {
			continue
		}
		created, err := time.Parse(time.RFC3339Nano, comment.CreatedAt)
		key := EventKey(Event{Type: "stop", Source: Source{Kind: "comment", ID: comment.ID, Action: "created"}})
		if (err != nil || !created.Before(started)) && !contains(s.state.Receipts, key) {
			return fmt.Errorf("an authorized stop command is pending")
		}
	}
	events, err := s.cfg.Client.List(ctx, fmt.Sprintf("/issues/%d/events", s.issue.Number), "")
	if err != nil {
		return err
	}
	for _, raw := range events {
		var item struct {
			ID        int64      `json:"id"`
			Event     string     `json:"event"`
			Actor     GitHubUser `json:"actor"`
			Label     Label      `json:"label"`
			CreatedAt string     `json:"created_at"`
		}
		if err := json.Unmarshal(raw, &item); err != nil {
			return err
		}
		if item.Event != "unlabeled" || item.Label.Name != "ready-for-agent" || !human(item.Actor) || !Allowed(actors, item.Actor.Login) {
			continue
		}
		created, err := time.Parse(time.RFC3339Nano, item.CreatedAt)
		key := EventKey(Event{Type: "label-removed", Source: Source{Kind: "label", ID: item.ID, Action: "removed"}})
		if (err != nil || !created.Before(started)) && !contains(s.state.Receipts, key) {
			return fmt.Errorf("an authorized removal of ready-for-agent is pending")
		}
	}
	return nil
}

func (s *actionSession) publishAction(ctx context.Context, operation string, output ActionResult) (ActionResult, error) {
	automatic := s.cfg.EventName == "workflow_run" || s.completionNotifier()
	if (!automatic && s.cfg.EventName != "workflow_dispatch") || s.state == nil || s.state.HostedCandidate == nil {
		return output, fmt.Errorf("select an issue with an authorized hosted candidate")
	}
	state := s.state
	candidate := *state.HostedCandidate
	runID := strconv.FormatInt(candidate.Run.RunID, 10)
	attempt := strconv.Itoa(candidate.Run.RunAttempt)
	if s.cfg.Inputs["candidate_run"] != runID || s.cfg.Inputs["candidate_attempt"] != attempt {
		return output, fmt.Errorf("the selected run and attempt differ from the recorded hosted candidate")
	}
	if automatic {
		if err := s.completion(ctx); err != nil {
			return output, err
		}
		if s.run.ID != candidate.Run.RunID || s.run.RunAttempt != candidate.Run.RunAttempt || s.execution.ID != candidate.ExecutionID || s.run.Conclusion != "success" {
			return output, fmt.Errorf("automatic publication must use the completed candidate run")
		}
	}
	if operation == "confirm-publish" {
		if err := s.confirmHostedPublication(ctx, candidate); err != nil {
			return output, err
		}
		output.Result = &Result{State: s.state, Disposition: "published", Reason: "Draft pull request confirmed."}
		return output, nil
	}
	current, err := s.adapter.Context(ctx, nil)
	if err != nil {
		return output, err
	}
	if current.IssueUnavailable || !current.RequirementsCurrent || !Allowed(current.AllowedActors, state.Initiator) {
		return output, fmt.Errorf("the issue or its operators are no longer authorized for publication")
	}
	if automatic {
		enabled, err := s.cfg.Variables.RepositoryVariable(ctx, "AGENT_AUTO_PUBLISH")
		if err != nil {
			return output, err
		}
		if enabled != "true" {
			return output, fmt.Errorf("AGENT_AUTO_PUBLISH must be true for automatic publication")
		}
	} else if !Allowed(current.AllowedActors, s.cfg.Actor, s.cfg.TriggeringActor) {
		return output, fmt.Errorf("the publication operators are outside AGENT_ALLOWED_ACTORS")
	}
	if state.HostedPublication != nil && state.Phase == "waiting-ci" {
		output.Result = &Result{State: state, Disposition: "already-published", Reason: "The candidate already has a draft pull request."}
		return output, nil
	}
	if state.Phase != "awaiting-publication" || state.Desired != "running" || len(openQuestions(state)) != 0 || len(active(state)) != 0 {
		return output, fmt.Errorf("the hosted candidate is no longer awaiting publication")
	}
	if err := validateHostedCandidate(state, candidate); err != nil {
		return output, err
	}
	run, err := s.publicationRun(ctx, candidate)
	if err != nil {
		return output, err
	}
	if err := s.ensureNoPendingStop(ctx, run, current.AllowedActors); err != nil {
		return output, err
	}
	name := hostedArtifactName(candidate.Run)
	id, err := s.artifact(ctx, run, name)
	if err != nil {
		return output, err
	}
	if id != candidate.ArtifactID {
		return output, fmt.Errorf("the worker artifact changed after candidate registration")
	}
	if operation == "publish" {
		s.execution, err = s.adapter.execution(candidate.ExecutionID)
		if err != nil {
			return output, err
		}
		s.run = &run
		result := s.cfg.HostedResult
		if result == nil || s.validateCheckpoint(&result.Checkpoint) != nil || result.CandidateSHA256 != candidate.CandidateSHA256 || result.ReportSHA256 != candidate.ReportSHA256 || !reflect.DeepEqual(result.Signature, candidate.Signature) {
			return output, fmt.Errorf("the downloaded artifact differs from the registered candidate")
		}
		if candidate.Signature != nil {
			data, err := json.Marshal(candidate.Signature)
			if err != nil {
				return output, err
			}
			output.Outputs["result_signature"] = string(data)
		}
	}
	output.Outputs["process"] = "true"
	output.Outputs["download"] = "true"
	output.Outputs["artifact_id"] = strconv.FormatInt(id, 10)
	output.Outputs["artifact_name"] = name
	output.Outputs["base_sha"] = state.Contract.BaseSHA
	output.Outputs["candidate_sha256"] = candidate.CandidateSHA256
	output.Outputs["report_sha256"] = candidate.ReportSHA256
	output.Outputs["default_branch"] = s.repo.DefaultBranch
	output.Outputs["run_id"] = runID
	output.Outputs["run_attempt"] = attempt
	output.Outputs["scope_paths"] = strings.Join(state.Contract.ScopePaths, ",")
	return output, nil
}

func (s *actionSession) confirmHostedPublication(ctx context.Context, candidate HostedCandidate) error {
	publication := s.cfg.HostedPublication
	if publication == nil || s.state.Phase != "awaiting-publication" || publication.Number <= 0 || !commitPattern.MatchString(publication.SHA) || publication.Branch != hostedBranch(s.issue.Number, candidate.Run) {
		return fmt.Errorf("invalid hosted publication confirmation")
	}
	var pr struct {
		Number int64  `json:"number"`
		State  string `json:"state"`
		Draft  bool   `json:"draft"`
		URL    string `json:"html_url"`
		Body   string `json:"body"`
		Head   struct {
			Ref  string     `json:"ref"`
			SHA  string     `json:"sha"`
			Repo Repository `json:"repo"`
		} `json:"head"`
		Base struct {
			Ref  string     `json:"ref"`
			SHA  string     `json:"sha"`
			Repo Repository `json:"repo"`
		} `json:"base"`
	}
	if err := s.cfg.Client.Request(ctx, "GET", fmt.Sprintf("/pulls/%d", publication.Number), nil, &pr); err != nil {
		return err
	}
	if pr.Number != publication.Number || pr.State != "open" || !pr.Draft || pr.Head.Ref != publication.Branch || pr.Head.SHA != publication.SHA || pr.Head.Repo.FullName != s.state.Repository || pr.Base.Ref != s.repo.DefaultBranch || pr.Base.Repo.FullName != s.state.Repository || !commitPattern.MatchString(pr.Base.SHA) {
		return fmt.Errorf("the draft pull request differs from the publication receipt")
	}
	if candidate.Signature != nil && candidate.Signature.Append(pr.Body) != pr.Body {
		return fmt.Errorf("the draft pull request signature differs from the worker result")
	}
	next := cloneState(s.state)
	next.HostedPublication = &HostedPublication{
		Number: pr.Number, Branch: pr.Head.Ref, SHA: pr.Head.SHA, URL: pr.URL,
		BaseRef: pr.Base.Ref, BaseSHA: pr.Base.SHA,
	}
	next.Phase = "waiting-ci"
	next.Revision++
	writeErr := s.adapter.WriteState(ctx, next)
	observed, err := s.adapter.ReadState(ctx)
	if err != nil || observed.Status != "ok" || !reflect.DeepEqual(observed.State, next) {
		return fmt.Errorf("could not confirm publication state for pull request #%d: %v", pr.Number, writeErr)
	}
	s.state = observed.State
	return nil
}
