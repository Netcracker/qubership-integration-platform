package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentsignature"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
	hc "github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/hostedcandidate"
)

func run(ctx context.Context, args []string) error {
	if len(args) == 0 {
		return fmt.Errorf("choose pack or publish")
	}
	fs := flag.NewFlagSet(args[0], flag.ContinueOnError)
	workspace := fs.String("workspace", "", "Git checkout")
	base := fs.String("base", "", "Base commit")
	output := fs.String("output", "", "New candidate file")
	bundle := fs.String("bundle", "", "Candidate file")
	digest := fs.String("expected-sha256", "", "Authorized artifact digest")
	reportDigest := fs.String("expected-report-sha256", "", "Authorized author report digest")
	repo := fs.String("repository", os.Getenv("GITHUB_REPOSITORY"), "Repository")
	branch := fs.String("default-branch", "", "Default branch")
	scope := fs.String("scope-paths", os.Getenv("AGENT_SCOPE_PATHS"), "Allowed paths")
	issue := fs.Int64("issue", 0, "Issue number")
	id := fs.Int64("run", 0, "Workflow run")
	attempt := fs.Int64("attempt", 0, "Run attempt")
	if err := fs.Parse(args[1:]); err != nil {
		return err
	}
	switch args[0] {
	case "pack":
		c, err := hc.Pack(ctx, *workspace, *base)
		if err != nil {
			return err
		}
		file, err := os.OpenFile(*output, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
		if err != nil {
			return err
		}
		err = json.NewEncoder(file).Encode(c)
		closeErr := file.Close()
		if err != nil || closeErr != nil {
			os.Remove(*output)
			if err != nil {
				return err
			}
			return closeErr
		}
		return nil
	case "publish":
		c, err := hc.Read(*bundle, *digest)
		if err != nil {
			return err
		}
		authorSummary, err := fixer.ReadHostedAuthorSummary(filepath.Join(filepath.Dir(*bundle), "report.json"), *reportDigest)
		if err != nil {
			return err
		}
		token := os.Getenv("AGENT_PUBLICATION_TOKEN")
		if token == "" {
			return fmt.Errorf("AGENT_PUBLICATION_TOKEN is required")
		}
		api, err := fixer.NewGitHubClient(*repo, token, nil, "")
		if err != nil {
			return err
		}
		var signature *agentsignature.Signature
		if input := os.Getenv("AGENT_RESULT_SIGNATURE"); input != "" {
			var parsed agentsignature.Signature
			if err := fixer.DecodeJSON([]byte(input), &parsed); err != nil {
				return err
			}
			signature = &parsed
		}
		result, err := hc.Publish(ctx, api, c, hc.Options{Workspace: *workspace, Base: *base, Issue: *issue, Run: *id, Attempt: *attempt, Scope: fixer.ParseScopes(*scope), DefaultBranch: *branch, Repository: *repo, Signature: signature, AuthorSummary: authorSummary})
		if err != nil {
			return err
		}
		return json.NewEncoder(os.Stdout).Encode(result)
	default:
		return fmt.Errorf("unknown candidate operation")
	}
}

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := run(ctx, os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
