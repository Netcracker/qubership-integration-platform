package fixer

import (
	"context"
	"fmt"
	"time"
)

func (s *actionSession) completionNotifier() bool {
	const login = "github-actions[bot]"
	sender := s.cfg.Payload.Sender
	return s.cfg.EventName == "workflow_dispatch" && s.cfg.Inputs["operation"] == "reconcile-run" && s.bot.ID > 0 && sender.ID == s.bot.ID && sender.Type == "Bot" && sender.Login == login && s.cfg.Actor == login && s.cfg.TriggeringActor == login
}

func (s *actionSession) completion(ctx context.Context) error {
	if s.cfg.EventName == "workflow_run" {
		return s.worker(ctx, true)
	}
	actors, err := s.cfg.allowedActors(ctx)
	if err != nil {
		return err
	}
	notification := s.completionNotifier()
	if !notification && !Allowed(actors, s.cfg.Actor, s.cfg.TriggeringActor) {
		return fmt.Errorf("the reconciliation operators are outside AGENT_ALLOWED_ACTORS")
	}
	runID, err := PositiveID(s.cfg.Inputs["worker_run"])
	if err != nil {
		return fmt.Errorf("select a worker run ID")
	}
	attempt, err := PositiveID(s.cfg.Inputs["worker_attempt"])
	if err != nil || attempt > 9007199254740991 {
		return fmt.Errorf("select a worker run attempt")
	}
	path := fmt.Sprintf("/actions/runs/%d/attempts/%d", runID, attempt)
	wait, cancel := context.WithTimeout(ctx, 45*time.Second)
	defer cancel()
	for {
		var run WorkflowRun
		if err := s.cfg.Client.Request(wait, "GET", path, nil, &run); err != nil {
			return err
		}
		identity, err := ParseWorkRunName(run.DisplayTitle)
		if err != nil || identity.IssueNumber != s.issue.Number {
			return fmt.Errorf("the selected run does not belong to this issue")
		}
		execution, err := s.adapter.execution(identity.ExecutionID)
		if err != nil {
			return err
		}
		expected := s.adapter.expected(*execution)
		expected.RunID, expected.RunAttempt = runID, int(attempt)
		if !MatchWorkerRun(run, expected) || (execution.Run != nil && *execution.Run != (RunIdentity{RunID: runID, RunAttempt: int(attempt)})) {
			return fmt.Errorf("the selected run does not match the recorded execution")
		}
		if run.Status == "completed" {
			s.cfg.Payload.WorkflowRun = run
			return s.worker(wait, true)
		}
		if !notification {
			return fmt.Errorf("the selected worker run has not completed")
		}
		select {
		case <-wait.Done():
			return fmt.Errorf("wait for the selected worker to complete: %w", wait.Err())
		case <-time.After(time.Second):
		}
	}
}
