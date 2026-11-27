package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentci"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

func run(ctx context.Context, args []string) error {
	flags := flag.NewFlagSet("agent-ci", flag.ContinueOnError)
	apply := flags.Bool("apply", false, "Update the pull request CI observation comment")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if flags.NArg() != 0 {
		return fmt.Errorf("usage: agent-ci [--apply]")
	}
	if os.Getenv("AGENT_ENABLED") != "true" || os.Getenv("AGENT_MODE") != "hosted" {
		return fmt.Errorf("CI observation requires AGENT_ENABLED=true and AGENT_MODE=hosted")
	}
	repository, token := os.Getenv("GITHUB_REPOSITORY"), os.Getenv("GITHUB_TOKEN")
	if token == "" {
		return fmt.Errorf("GITHUB_TOKEN is required")
	}
	client, err := fixer.EngineeringClientFromEnv(repository, token, "")
	if err != nil {
		return err
	}
	data, err := os.ReadFile(os.Getenv("GITHUB_EVENT_PATH"))
	if err != nil {
		return err
	}
	options := agentci.Options{EventName: os.Getenv("GITHUB_EVENT_NAME"), Repository: repository,
		Ref: os.Getenv("GITHUB_REF"), RequiredChecks: os.Getenv("AGENT_CI_REQUIRED_CHECKS"), Apply: *apply}
	if err := json.Unmarshal(data, &options.Event); err != nil {
		return fmt.Errorf("invalid GitHub event: %w", err)
	}
	if options.EventName == "workflow_dispatch" {
		if !fixer.Allowed(os.Getenv("AGENT_ALLOWED_ACTORS"), os.Getenv("GITHUB_ACTOR"), os.Getenv("GITHUB_TRIGGERING_ACTOR")) {
			return fmt.Errorf("the workflow operator is outside AGENT_ALLOWED_ACTORS")
		}
		options.PullRequest, err = fixer.PositiveID(os.Getenv("INPUT_PULL_REQUEST"))
		if err != nil {
			return fmt.Errorf("invalid pull request number")
		}
	}
	reports, err := agentci.Observe(ctx, client, options)
	if reports == nil {
		reports = []agentci.Report{}
	}
	encoder := json.NewEncoder(os.Stdout)
	encoder.SetIndent("", "  ")
	return errors.Join(err, encoder.Encode(reports))
}

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := run(ctx, os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
