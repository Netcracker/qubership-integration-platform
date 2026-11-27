package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentoutcome"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

func run(ctx context.Context) error {
	if os.Getenv("GITHUB_EVENT_NAME") != "pull_request_target" {
		return fmt.Errorf("the outcome recorder requires a pull_request_target event")
	}
	config := agentoutcome.Config{
		Repository: os.Getenv("GITHUB_REPOSITORY"), Workspace: os.Getenv("GITHUB_WORKSPACE"),
		Token: os.Getenv("GITHUB_TOKEN"), RequiredChecks: os.Getenv("AGENT_CI_REQUIRED_CHECKS"),
	}
	directory := os.Getenv("AGENT_OUTCOME_DIR")
	if !filepath.IsAbs(directory) || !filepath.IsAbs(config.Workspace) || config.Token == "" {
		return fmt.Errorf("the outcome recorder requires absolute workspace and output paths and a GitHub token")
	}
	runID, idErr := fixer.PositiveID(os.Getenv("GITHUB_RUN_ID"))
	runAttempt, attemptErr := fixer.PositiveID(os.Getenv("GITHUB_RUN_ATTEMPT"))
	if idErr != nil || attemptErr != nil {
		return fmt.Errorf("invalid outcome workflow identity")
	}
	config.Workflow = agentoutcome.Workflow{RunID: runID, RunAttempt: runAttempt}
	eventData, err := os.ReadFile(os.Getenv("GITHUB_EVENT_PATH"))
	if err != nil {
		return err
	}
	var event agentoutcome.CloseEvent
	if err = json.Unmarshal(eventData, &event); err != nil {
		return err
	}
	client, err := fixer.EngineeringClientFromEnv(config.Repository, config.Token, "")
	if err != nil {
		return err
	}
	report, err := agentoutcome.ReadAndRecord(ctx, client, config, event)
	if report == nil {
		return err
	}
	return errors.Join(err, report.Save(directory))
}

func main() {
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	if err := run(ctx); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
