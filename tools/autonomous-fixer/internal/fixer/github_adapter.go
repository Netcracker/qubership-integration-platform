package fixer

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"time"
)

type GitHubAdapter struct {
	Client        *GitHubClient
	Store         *CommentStore
	Task          Seed
	DefaultBranch string
	WorkflowID    int64
	AllowedActors func(context.Context) (string, error)
	Mode          string
	state         *State
	dispatch      *DispatchDiagnostic
	cancellations map[string]bool
}

type DispatchDiagnostic struct {
	executionID        string
	Receipt            string
	DispatchHTTPStatus int
	Readback           string
	ReadbackHTTPStatus int
	RunID              int64
}

func (a *GitHubAdapter) ReadState(ctx context.Context) (StateRead, error) {
	result, err := a.Store.ReadState(ctx)
	if err == nil && result.Status == "ok" {
		a.state = result.State
	}
	return result, err
}
func (a *GitHubAdapter) WriteState(ctx context.Context, state *State) error {
	return a.Store.WriteState(ctx, state)
}
func (a *GitHubAdapter) expected(execution Execution) WorkerRunExpected {
	sha := execution.TrustedSHA
	if sha == "" {
		sha = a.state.Contract.TrustedSHA
	}
	return WorkerRunExpected{Repository: a.Task.Repository, WorkflowID: a.WorkflowID, DefaultBranch: a.DefaultBranch, TrustedSHA: sha, RunName: WorkRunName(a.Task.IssueNumber, execution.ID, execution.Generation, execution.ContractVersion)}
}
func (a *GitHubAdapter) execution(id string) (*Execution, error) {
	if a.state != nil {
		for i := range a.state.Executions {
			if a.state.Executions[i].ID == id {
				return &a.state.Executions[i], nil
			}
		}
	}
	return nil, fmt.Errorf("execution is not in canonical state")
}
func (a *GitHubAdapter) Context(ctx context.Context, event *Event) (TransitionContext, error) {
	actors, err := a.AllowedActors(ctx)
	if err != nil {
		return TransitionContext{}, err
	}
	var issue Issue
	if err := a.Client.Request(ctx, "GET", fmt.Sprintf("/issues/%d", a.Task.IssueNumber), nil, &issue); err != nil {
		return TransitionContext{}, err
	}
	result := TransitionContext{Task: a.Task, WorkflowSHA: a.Task.TrustedSHA, AllowedActors: actors, Mode: a.Mode,
		IssueUnavailable:    issue.Number != a.Task.IssueNumber || issue.State != "open" || !issue.ReadyForAgent(),
		RequirementsCurrent: a.state != nil && a.state.Contract.matchesRequirements(issue.Requirements())}
	if event != nil && event.Source.Kind == "workflow" {
		execution, err := a.execution(event.ExecutionID)
		if err != nil || event.Source.RunID <= 0 || event.Source.RunAttempt <= 0 {
			return result, nil
		}
		var run WorkflowRun
		if err := a.Client.Request(ctx, "GET", fmt.Sprintf("/actions/runs/%d/attempts/%d", event.Source.RunID, event.Source.RunAttempt), nil, &run); err != nil {
			return result, err
		}
		expected := a.expected(*execution)
		expected.RunID = event.Source.RunID
		expected.RunAttempt = event.Source.RunAttempt
		result.TrustedWorkflow = MatchWorkerRun(run, expected)
	}
	return result, nil
}
func (a *GitHubAdapter) runFor(ctx context.Context, execution Execution) (*WorkflowRun, error) {
	var hint int64
	if execution.Run != nil {
		hint = execution.Run.RunID
	}
	if hint == 0 {
		for _, effect := range a.state.Effects {
			if effect.Kind == "dispatch" && effect.ExecutionID == execution.ID && effect.Result != nil {
				hint = effect.Result.RunID
			}
		}
	}
	expected := a.expected(execution)
	if hint != 0 {
		var run WorkflowRun
		if err := a.Client.Request(ctx, "GET", fmt.Sprintf("/actions/runs/%d", hint), nil, &run); err != nil {
			return nil, err
		}
		expected.RunID = hint
		if !MatchWorkerRun(run, expected) {
			return nil, fmt.Errorf("worker run provenance does not match the dispatch")
		}
		return &run, nil
	}
	raw, err := a.Client.List(ctx, "/actions/workflows/agent-work.yaml/runs?event=workflow_dispatch", "workflow_runs")
	if err != nil {
		return nil, err
	}
	var found *WorkflowRun
	for _, data := range raw {
		var run WorkflowRun
		if err := json.Unmarshal(data, &run); err != nil {
			return nil, err
		}
		if MatchWorkerRun(run, expected) {
			if found != nil {
				return nil, fmt.Errorf("multiple runs match the execution")
			}
			found = &run
		}
	}
	return found, nil
}
func (a *GitHubAdapter) Perform(ctx context.Context, effect Effect) error {
	execution, err := a.execution(effect.ExecutionID)
	if err != nil {
		return err
	}
	switch effect.Kind {
	case "report-hosted":
		return a.postHostedReport(ctx, effect)
	case "dispatch":
		var response struct {
			RunID int64 `json:"workflow_run_id"`
		}
		inputs := map[string]string{"issue": strconv.FormatInt(a.Task.IssueNumber, 10), "execution": execution.ID, "generation": strconv.Itoa(execution.Generation), "contract_version": strconv.Itoa(execution.ContractVersion), "trusted_sha": a.expected(*execution).TrustedSHA, "mode": executionMode(execution.Mode)}
		reply, err := a.Client.request(ctx, "POST", "/actions/workflows/agent-work.yaml/dispatches", map[string]any{"ref": a.DefaultBranch, "inputs": inputs}, &response)
		a.dispatch = &DispatchDiagnostic{executionID: execution.ID, Receipt: "unconfirmed", DispatchHTTPStatus: reply.status}
		switch {
		case reply.status >= 200 && reply.status < 300:
			a.dispatch.Receipt = "acknowledged"
			if err == nil && response.RunID > 0 {
				a.dispatch.RunID = response.RunID
			}
		case reply.status >= 400 && reply.status < 500:
			a.dispatch.Receipt = "rejected"
		}
		return err
	case "cancel":
		run, err := a.runFor(ctx, *execution)
		if err != nil {
			return err
		}
		if run == nil {
			return fmt.Errorf("cannot identify run to cancel")
		}
		if run.Status != "completed" {
			if err := a.Client.Request(ctx, "POST", fmt.Sprintf("/actions/runs/%d/cancel", run.ID), nil, nil); err != nil {
				return err
			}
		}
		if a.cancellations == nil {
			a.cancellations = make(map[string]bool)
		}
		a.cancellations[effect.ID] = true
		return nil
	default:
		return fmt.Errorf("publication is disabled in fake mode")
	}
}

func (a *GitHubAdapter) findDispatchedRun(ctx context.Context, execution Execution) (*WorkflowRun, error) {
	readback, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	for _, delay := range []time.Duration{0, 250 * time.Millisecond, 500 * time.Millisecond, time.Second} {
		if delay > 0 {
			select {
			case <-readback.Done():
				return nil, ctx.Err()
			case <-time.After(delay):
			}
		}
		run, err := a.runFor(readback, execution)
		if run != nil {
			return run, nil
		}
		if readback.Err() != nil {
			return nil, ctx.Err()
		}
		var response *HTTPError
		if err != nil && (!errors.As(err, &response) || response.Status != http.StatusNotFound) {
			return nil, err
		}
	}
	return nil, nil
}

func (a *GitHubAdapter) observeDispatch(ctx context.Context, execution Execution) (Observation, error) {
	diagnostic := a.dispatch
	if diagnostic == nil || diagnostic.executionID != execution.ID {
		diagnostic = &DispatchDiagnostic{executionID: execution.ID, Receipt: "unrecorded"}
		a.dispatch = diagnostic
	}
	if diagnostic.Receipt == "rejected" {
		return Observation{Status: "unknown"}, nil
	}
	if diagnostic.Receipt == "acknowledged" && diagnostic.RunID > 0 {
		// Admission verifies the run's provenance after GitHub exposes its attempt.
		return Observation{Status: "found", Result: &EffectResult{RunID: diagnostic.RunID, RunAttempt: 1}}, nil
	}
	run, err := a.findDispatchedRun(ctx, execution)
	if err != nil {
		diagnostic.Readback = "failed"
		var response *HTTPError
		if errors.As(err, &response) {
			diagnostic.ReadbackHTTPStatus = response.Status
		}
		return Observation{Status: "unknown"}, err
	}
	if run == nil {
		diagnostic.Readback = "not-visible"
		if diagnostic.Receipt == "acknowledged" {
			return Observation{Status: "pending"}, nil
		}
		return Observation{Status: "unknown"}, nil
	}
	diagnostic.Readback = "observed"
	diagnostic.RunID = run.ID
	return Observation{Status: "found", Result: &EffectResult{RunID: run.ID, RunAttempt: run.RunAttempt, Conclusion: run.Conclusion}}, nil
}

func (a *GitHubAdapter) Observe(ctx context.Context, effect Effect) (Observation, error) {
	execution, err := a.execution(effect.ExecutionID)
	if err != nil {
		return Observation{Status: "unknown"}, err
	}
	if effect.Kind == "publish" {
		return Observation{Status: "unknown"}, nil
	}
	if effect.Kind == "report-hosted" {
		return a.observeHostedReport(ctx, effect)
	}
	if effect.Kind == "dispatch" {
		return a.observeDispatch(ctx, *execution)
	}
	run, err := a.runFor(ctx, *execution)
	if err != nil {
		return Observation{Status: "unknown"}, err
	}
	if run == nil {
		return Observation{Status: "unknown"}, nil
	}
	if run.Status == "completed" {
		return Observation{Status: "found", Result: &EffectResult{RunID: run.ID, RunAttempt: run.RunAttempt, Conclusion: run.Conclusion}}, nil
	}
	if a.cancellations[effect.ID] {
		return Observation{Status: "pending"}, nil
	}
	return Observation{Status: "unknown"}, nil
}

func SyncStopLabels(ctx context.Context, client *GitHubClient, issue int64, state *State) error {
	desired, owned := make(map[string]bool), make(map[string]bool)
	for _, reason := range StopReasons {
		owned["needs-human:"+reason] = true
	}
	for _, episode := range state.StopEpisodes {
		if episode.Status == "open" || episode.Status == "resolved" {
			desired["needs-human:"+episode.Reason] = true
		}
	}
	raw, err := client.List(ctx, fmt.Sprintf("/issues/%d/labels", issue), "")
	if err != nil {
		return err
	}
	current := make(map[string]bool)
	for _, data := range raw {
		var label struct {
			Name string `json:"name"`
		}
		if err := json.Unmarshal(data, &label); err != nil {
			return err
		}
		current[label.Name] = true
	}
	for label := range desired {
		if !owned[label] {
			return fmt.Errorf("unknown stop label")
		}
		if !current[label] {
			var definition json.RawMessage
			if err := client.Request(ctx, "GET", "/labels/"+url.PathEscape(label), nil, &definition); err != nil {
				if status, ok := err.(*HTTPError); !ok || status.Status != 404 {
					return err
				}
				if err := client.Request(ctx, "POST", "/labels", map[string]string{"name": label, "color": "FBCA04", "description": "The agent needs an action from an allowed user."}, nil); err != nil {
					return err
				}
			}
			if err := client.Request(ctx, "POST", fmt.Sprintf("/issues/%d/labels", issue), map[string]any{"labels": []string{label}}, nil); err != nil {
				return err
			}
		}
	}
	for label := range current {
		if owned[label] && !desired[label] {
			if err := client.Request(ctx, "DELETE", fmt.Sprintf("/issues/%d/labels/%s", issue, url.PathEscape(label)), nil, nil); err != nil {
				return err
			}
		}
	}
	return nil
}
