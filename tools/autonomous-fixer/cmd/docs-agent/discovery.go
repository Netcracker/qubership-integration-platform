package main

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strconv"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docgithub"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

func scheduleActions(repository string) (*fixer.GitHubClient, error) {
	token := os.Getenv("ACTIONS_READ_TOKEN")
	if token == "" {
		return nil, fmt.Errorf("ACTIONS_READ_TOKEN is required for documentation discovery")
	}
	return fixer.NewGitHubClient(repository, token, nil, "")
}

func discoverSchedule(ctx context.Context, repository, actor string, project, issue int) error {
	if repository == "" || project <= 0 || issue < 0 {
		return fmt.Errorf("discover requires a repository and a positive project number")
	}
	client, err := github(repository)
	if err != nil {
		return err
	}
	if err = validateActor(ctx, client, actor); err != nil {
		return err
	}
	if os.Getenv("GITHUB_ACTIONS") != "true" {
		if err = docgithub.AuthorizeActor(ctx, client, actor); err != nil {
			return err
		}
	}
	actions, err := scheduleActions(repository)
	if err != nil {
		return err
	}
	report, err := docgithub.Discover(ctx, client, actions, project, issue)
	if err != nil {
		return err
	}
	return json.NewEncoder(os.Stdout).Encode(report)
}

func claimSchedule(ctx context.Context, prepared, basis, actor string) error {
	if !filepath.IsAbs(prepared) || basis == "" {
		return fmt.Errorf("claim requires an absolute prepared directory and a discovery basis")
	}
	var plan docgithub.Plan
	if err := read(filepath.Join(prepared, "plan.json"), &plan); err != nil {
		return err
	}
	attempt, err := schedulerAttempt(plan)
	if err != nil {
		return err
	}
	client, err := github(plan.Repository)
	if err != nil {
		return err
	}
	if err = validateActor(ctx, client, actor); err != nil {
		return err
	}
	actions, err := scheduleActions(plan.Repository)
	if err != nil {
		return err
	}
	background := os.Getenv("GITHUB_EVENT_NAME") != "issue_comment"
	if !background {
		allowed, err := client.API.RepositoryVariable(ctx, "AGENT_ALLOWED_ACTORS")
		if err != nil {
			return err
		}
		command, err := documentationCommand(ctx, client, actor, allowed)
		if err != nil {
			return err
		}
		if command.Issue != plan.IssueNumber {
			return fmt.Errorf("documentation command changed before claim")
		}
	}
	claimed, reason, err := docgithub.ClaimSchedule(ctx, client, actions, plan, basis, actor, attempt, background)
	if err != nil {
		return err
	}
	return json.NewEncoder(os.Stdout).Encode(map[string]any{"claimed": claimed, "reason": reason})
}

func schedulerAttempt(plan docgithub.Plan) (docgithub.ScheduledAttempt, error) {
	workflow := fmt.Sprintf("%s/.github/workflows/agent-docs-schedule.yaml@refs/heads/%s", plan.Repository, plan.DefaultBranch)
	if os.Getenv("GITHUB_ACTIONS") != "true" || os.Getenv("GITHUB_REPOSITORY") != plan.Repository ||
		os.Getenv("GITHUB_WORKFLOW_REF") != workflow {
		return docgithub.ScheduledAttempt{}, fmt.Errorf("the default-branch documentation scheduler is required")
	}
	attempt := workflowAttempt(plan.Repository)
	if attempt.RunID == 0 {
		return attempt, fmt.Errorf("scheduled documentation run identity is invalid")
	}
	return attempt, nil
}

func workflowAttempt(repository string) docgithub.ScheduledAttempt {
	if os.Getenv("GITHUB_ACTIONS") != "true" || os.Getenv("GITHUB_REPOSITORY") != repository {
		return docgithub.ScheduledAttempt{}
	}
	run, runErr := strconv.ParseInt(os.Getenv("GITHUB_RUN_ID"), 10, 64)
	attempt, attemptErr := strconv.Atoi(os.Getenv("GITHUB_RUN_ATTEMPT"))
	if runErr != nil || attemptErr != nil || run <= 0 || attempt <= 0 {
		return docgithub.ScheduledAttempt{}
	}
	return docgithub.ScheduledAttempt{RunID: run, Attempt: attempt}
}
