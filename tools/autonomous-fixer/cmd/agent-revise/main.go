package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"syscall"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentpr"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/hostedcandidate"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/hostedrevision"
)

func controller(ctx context.Context) (*hostedrevision.Controller, error) {
	token, reader := os.Getenv("GITHUB_TOKEN"), os.Getenv("AGENT_VARIABLES_READ_TOKEN")
	if token == "" || reader == "" {
		return nil, fmt.Errorf("the revision requires GITHUB_TOKEN and AGENT_VARIABLES_READ_TOKEN")
	}
	repo := os.Getenv("GITHUB_REPOSITORY")
	client, err := fixer.NewGitHubClient(repo, token, nil, "")
	if err != nil {
		return nil, err
	}
	variables, err := fixer.NewGitHubClient(repo, reader, nil, "")
	if err != nil {
		return nil, err
	}
	data, err := os.ReadFile(os.Getenv("GITHUB_EVENT_PATH"))
	if err != nil {
		return nil, err
	}
	var payload fixer.GitHubPayload
	if err := json.Unmarshal(data, &payload); err != nil {
		return nil, err
	}
	run, err := fixer.PositiveID(os.Getenv("GITHUB_RUN_ID"))
	if err != nil {
		return nil, err
	}
	attempt, err := strconv.Atoi(os.Getenv("GITHUB_RUN_ATTEMPT"))
	if err != nil {
		return nil, err
	}
	return hostedrevision.Open(ctx, hostedrevision.Config{
		Client: client, Variables: variables, Repository: repo, Ref: os.Getenv("GITHUB_REF"),
		Event: os.Getenv("GITHUB_EVENT_NAME"), Actor: os.Getenv("GITHUB_ACTOR"), TriggerActor: os.Getenv("GITHUB_TRIGGERING_ACTOR"),
		WorkflowSHA: os.Getenv("GITHUB_SHA"), Payload: payload, Run: fixer.RunIdentity{RunID: run, RunAttempt: attempt},
	})
}

func writeJSON(path string, value any) error {
	if !filepath.IsAbs(path) {
		return fmt.Errorf("the revision output path must be absolute")
	}
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return err
	}
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return err
	}
	defer file.Close()
	return json.NewEncoder(file).Encode(value)
}

func writeOutput(body string) error {
	file, err := os.OpenFile(os.Getenv("GITHUB_OUTPUT"), os.O_WRONLY|os.O_APPEND, 0600)
	if err != nil {
		return err
	}
	defer file.Close()
	_, err = file.WriteString(body)
	return err
}

func run(ctx context.Context, args []string) error {
	if len(args) != 1 || (args[0] != "admit" && args[0] != "work" && args[0] != "publish") {
		return fmt.Errorf("usage: agent-revise admit|work|publish")
	}
	c, err := controller(ctx)
	if err != nil {
		return err
	}
	if args[0] == "admit" {
		admitted, err := c.OpenRevision(ctx)
		if err != nil {
			if os.Getenv("GITHUB_EVENT_NAME") != "workflow_dispatch" || !automaticSkip(err) {
				return err
			}
			fmt.Fprintln(os.Stderr, "Automatic revision skipped:", err)
		}
		return writeOutput(fmt.Sprintf("admitted=%t\n", admitted))
	}
	a, found, err := c.Assignment(ctx)
	if err != nil {
		return err
	}
	if !found {
		return fmt.Errorf("the revision has no receipt for this workflow run")
	}
	if args[0] == "publish" {
		return publish(ctx, c, a)
	}
	a, checkpoint, err := c.VerifyWork(ctx, a)
	if err != nil {
		return err
	}
	if _, err = c.CheckHead(ctx, a, ""); err != nil {
		return err
	}
	if err = writeJSON(os.Getenv("AGENT_CHECKPOINT_PATH"), checkpoint); err != nil {
		return err
	}
	if err = writeJSON(os.Getenv("AGENT_REVISION_ASSIGNMENT_PATH"), a); err != nil {
		return err
	}
	return writeOutput(fmt.Sprintf("base_sha=%s\nissue=%d\n", checkpoint.Contract.BaseSHA, checkpoint.IssueNumber))
}

func automaticSkip(err error) bool {
	return errors.Is(err, hostedrevision.ErrUnauthorized) || errors.Is(err, hostedrevision.ErrAlreadyActive) ||
		errors.Is(err, hostedrevision.ErrStale) || errors.Is(err, hostedrevision.ErrClosed) ||
		errors.Is(err, agentpr.ErrNotPublished) || errors.Is(err, agentpr.ErrPublicationPending)
}

func publish(ctx context.Context, c *hostedrevision.Controller, a hostedrevision.Assignment) error {
	done, err := c.HasResult(ctx, a)
	if err != nil || done {
		return err
	}
	report := func(result hostedrevision.Result) error { return c.Report(ctx, a, result) }
	if os.Getenv("REVISION_WORK_RESULT") != "success" {
		_, err := c.CheckHead(ctx, a, "")
		if err == nil && a.Automatic {
			_, err = c.Verify(ctx, a)
		}
		return finishRevision(hostedrevision.Result{Status: "failed"}, err, report)
	}
	dir := os.Getenv("AGENT_HOSTED_ARTIFACT_DIR")
	if !filepath.IsAbs(dir) {
		return fmt.Errorf("the revision artifact directory must be absolute")
	}
	sealed, err := fixer.LoadHostedResult(dir)
	if err != nil {
		return err
	}
	if !a.MatchesCheckpoint(sealed.Checkpoint) {
		return fmt.Errorf("the worker artifact differs from the authorized revision")
	}
	result := hostedrevision.Result{Status: sealed.Outcome, Signature: sealed.Signature}
	if sealed.Outcome != "author-reported-fixed" {
		if _, err = c.CheckHead(ctx, a, ""); err == nil {
			_, err = c.Verify(ctx, a)
		}
		return finishRevision(result, err, report)
	}
	candidate, err := hostedcandidate.Read(filepath.Join(dir, "candidate.json"), sealed.CandidateSHA256)
	if err != nil {
		return err
	}
	tip, err := publishCandidate(ctx, c, a, sealed.Checkpoint, candidate)
	result.Status, result.Commit = "published", tip
	return finishRevision(result, err, report)
}

func finishRevision(result hostedrevision.Result, err error, report func(hostedrevision.Result) error) error {
	switch {
	case err == nil:
	case errors.Is(err, hostedrevision.ErrClosed):
		result.Status = "closed"
	case errors.Is(err, hostedrevision.ErrStale):
		result.Status = "stale"
	case errors.Is(err, hostedrevision.ErrUnauthorized):
		result.Status = "withdrawn"
	default:
		return err
	}
	return report(result)
}

func publishCandidate(ctx context.Context, c *hostedrevision.Controller, a hostedrevision.Assignment, checkpoint fixer.Checkpoint, candidate hostedcandidate.Candidate) (string, error) {
	created, err := time.Parse(time.RFC3339, a.CreatedAt)
	if err != nil {
		return "", err
	}
	prepared := false
	tip, err := hostedcandidate.PublishRevision(ctx, candidate, hostedcandidate.RevisionOptions{
		Workspace: os.Getenv("GITHUB_WORKSPACE"), Repository: checkpoint.Repository, Branch: a.Branch,
		Head: a.Head, Token: os.Getenv("AGENT_PUBLICATION_TOKEN"), Pull: a.PullNumber, Comment: a.CommentID,
		Run: a.Run.RunID, CreatedAt: created, Scope: checkpoint.Contract.ScopePaths,
		CheckHead: func(ctx context.Context, tip string) (bool, error) {
			prepared = true
			already, err := c.CheckHead(ctx, a, tip)
			if err != nil || already {
				return already, err
			}
			if _, err = c.Verify(ctx, a); err != nil {
				return false, err
			}
			return c.CheckHead(ctx, a, tip)
		},
	})
	if err != nil && !prepared {
		_, headErr := c.CheckHead(ctx, a, "")
		if errors.Is(headErr, hostedrevision.ErrClosed) || errors.Is(headErr, hostedrevision.ErrStale) {
			return "", headErr
		}
	}
	return tip, err
}

func main() {
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	if err := run(ctx, os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
