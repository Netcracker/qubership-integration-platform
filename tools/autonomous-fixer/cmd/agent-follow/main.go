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

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/autofollow"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

func run(ctx context.Context, args []string) error {
	flags := flag.NewFlagSet("agent-follow", flag.ContinueOnError)
	preview := flags.Bool("preview", false, "Check eligibility and plan follow-up without writing to GitHub")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if flags.NArg() != 0 {
		return fmt.Errorf("usage: agent-follow [--preview]")
	}
	if os.Getenv("AGENT_ENABLED") != "true" || os.Getenv("AGENT_MODE") != "hosted" {
		return fmt.Errorf("automatic follow-up requires AGENT_ENABLED=true and AGENT_MODE=hosted")
	}
	pull, err := fixer.PositiveID(os.Getenv("INPUT_PULL_REQUEST"))
	if err != nil {
		return fmt.Errorf("INPUT_PULL_REQUEST must be a positive pull request number")
	}
	token, reader := os.Getenv("GITHUB_TOKEN"), os.Getenv("AGENT_VARIABLES_READ_TOKEN")
	if token == "" || reader == "" {
		return fmt.Errorf("automatic follow-up requires GITHUB_TOKEN and AGENT_VARIABLES_READ_TOKEN")
	}
	repo := os.Getenv("GITHUB_REPOSITORY")
	client, err := fixer.NewEngineeringGitHubClient(repo, token, os.Getenv("GH_ACCESS_TOKEN"), os.Getenv("AGENT_USE_ORG_BOT") == "true", nil, "")
	if err != nil {
		return err
	}
	variables, err := fixer.NewGitHubClient(repo, reader, nil, "")
	if err != nil {
		return err
	}
	report, err := autofollow.Reconcile(ctx, client, variables, autofollow.Options{
		Repository: repo, Ref: os.Getenv("GITHUB_REF"), PullRequest: pull,
		RequiredChecks: os.Getenv("AGENT_CI_REQUIRED_CHECKS"), Apply: !*preview,
	})
	encoder := json.NewEncoder(os.Stdout)
	encoder.SetIndent("", "  ")
	return errors.Join(err, encoder.Encode(report))
}

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := run(ctx, os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
