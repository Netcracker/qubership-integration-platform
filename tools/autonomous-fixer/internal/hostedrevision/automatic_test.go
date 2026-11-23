package hostedrevision

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"slices"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentci"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/autorequest"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

func automaticFixture(t *testing.T) (*fixture, autorequest.Request) {
	t.Helper()
	f := revisionFixture(t)
	original := f.cfg.Payload.Comment.User
	f.reviewer = fixer.GitHubUser{ID: 35, Login: "reviewer", Type: "User"}
	f.allowlist = original.Login + "," + f.reviewer.Login
	f.cfg.Event, f.cfg.Actor, f.cfg.TriggerActor = "workflow_dispatch", f.bot.Login, f.bot.Login
	f.cfg.Payload = fixer.GitHubPayload{Repository: f.cfg.Payload.Repository, Sender: f.bot}
	f.reviews = []discussion{{
		ID: 11, User: f.reviewer, CommitID: f.pr.Head.SHA, State: "CHANGES_REQUESTED",
		Body: "Cover restored focus.", SubmittedAt: time.Date(2026, 11, 19, 11, 55, 0, 0, time.UTC),
	}}
	f.reviewSize = 2
	r := autorequest.Request{
		RepositoryID: f.cfg.Payload.Repository.ID, PullID: f.pr.ID, PullNumber: f.pr.Number, Head: f.pr.Head.SHA,
		Reviews: []autorequest.Review{{ID: f.reviews[0].ID, Actor: f.reviewer}},
	}
	f.cfg = f.dispatch(t, r, 123, 13)
	return f, r
}

func (f *fixture) dispatch(t *testing.T, request autorequest.Request, run, comment int64) Config {
	t.Helper()
	marker, err := autorequest.Encode(request)
	if err != nil {
		t.Fatal(err)
	}
	cfg := f.cfg
	cfg.Run.RunID = run
	cfg.Payload.Inputs = map[string]string{"auto_pr": strconv.FormatInt(f.pr.Number, 10), "auto_request": strconv.FormatInt(comment, 10)}
	f.change(func() {
		f.commands[comment] = fixer.Comment{ID: comment, User: f.bot, Body: marker + "\n\nAutomatic revision request.",
			CreatedAt: "2026-11-19T12:00:00Z", UpdatedAt: "2026-11-19T12:00:00Z"}
		f.runs[run] = fixer.WorkflowRun{
			ID: run, RunAttempt: 1, WorkflowID: 21, HeadRepository: cfg.Payload.Repository, Event: "workflow_dispatch",
			Path: ".github/workflows/agent-work.yaml", HeadBranch: "main", HeadSHA: cfg.WorkflowSHA,
			DisplayTitle: fmt.Sprintf("qip-agent-auto-revise|%d|%d", f.pr.Number, comment), Status: "in_progress",
		}
	})
	return cfg
}

func accepted(t *testing.T, c *Controller) Assignment {
	t.Helper()
	ctx := context.Background()
	admitted, err := c.OpenRevision(ctx)
	if err != nil || !admitted {
		t.Fatalf("admission = %t, %v", admitted, err)
	}
	a, found, err := c.Assignment(ctx)
	if err != nil || !found {
		t.Fatalf("assignment = %#v, %t, %v", a, found, err)
	}
	return a
}

func TestAutomaticRevisionUsesCurrentReviewAndInlineFeedback(t *testing.T) {
	f, r := automaticFixture(t)
	c := openFixture(t, f.cfg)
	a := accepted(t, c)
	ctx := t.Context()
	a, checkpoint, err := c.VerifyWork(ctx, a)
	if err != nil || !a.MatchesCheckpoint(checkpoint) {
		t.Fatalf("verification = %#v, %v", checkpoint, err)
	}
	if !a.Automatic || a.Actor != f.bot || a.Head != r.Head || !slices.Equal(a.ConsumedReviews(), []int64{11}) ||
		len(a.Feedback) != 3 || a.Feedback[0].Body != f.reviews[0].Body ||
		a.Feedback[2].URL != "https://github.com/org/repo/pull/4#discussion_r111" || a.Feedback[2].Line != 22 {
		t.Fatalf("assignment = %#v", a)
	}
	f.change(func() { f.permission = "read" })
	if _, err := c.Verify(ctx, a); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("revoked reviewer permission = %v", err)
	}
}

func TestAutomaticRevisionRejectsUntrustedDispatchOrRequest(t *testing.T) {
	tests := map[string]func(*fixture, *autorequest.Request){
		"wrong repository": func(_ *fixture, r *autorequest.Request) { r.RepositoryID++ },
		"wrong pull ID":    func(_ *fixture, r *autorequest.Request) { r.PullID++ },
		"wrong pull":       func(_ *fixture, r *autorequest.Request) { r.PullNumber++ },
		"other head":       func(_ *fixture, r *autorequest.Request) { r.Head = strings.Repeat("d", 40) },
		"review impersonation": func(_ *fixture, r *autorequest.Request) {
			r.Reviews[0].Actor.ID++
		},
		"review dismissed":       func(f *fixture, _ *autorequest.Request) { f.reviews[0].State = "DISMISSED" },
		"review wrong head":      func(f *fixture, _ *autorequest.Request) { f.reviews[0].CommitID = strings.Repeat("d", 40) },
		"review cross PR":        func(f *fixture, _ *autorequest.Request) { f.feedbackPull++ },
		"review removed access":  func(f *fixture, _ *autorequest.Request) { f.permission = "triage" },
		"original actor removed": func(f *fixture, _ *autorequest.Request) { f.allowlist = f.reviewer.Login },
		"source issue changed":   func(f *fixture, _ *autorequest.Request) { f.issue.Title = "New requirements" },
		"source issue closed":    func(f *fixture, _ *autorequest.Request) { f.issue.State = "closed" },
		"source issue unready":   func(f *fixture, _ *autorequest.Request) { f.issue.Labels = nil },
	}
	for name, change := range tests {
		t.Run(name, func(t *testing.T) {
			f, r := automaticFixture(t)
			f.change(func() { change(f, &r) })
			cfg := f.dispatch(t, r, 123, 13)
			if ok, err := openFixture(t, cfg).OpenRevision(context.Background()); ok || err == nil || f.writes != 0 {
				t.Fatalf("admission = %t, %v, writes = %d", ok, err, f.writes)
			}
		})
	}

	for name, change := range map[string]func(*fixture){
		"human authored": func(f *fixture) {
			cmd := f.commands[13]
			cmd.User = f.reviewer
			f.commands[13] = cmd
		},
		"edited comment": func(f *fixture) {
			cmd := f.commands[13]
			cmd.UpdatedAt = "2026-11-19T12:01:00Z"
			f.commands[13] = cmd
		},
		"comment on another PR": func(f *fixture) { f.commandPull++ },
		"wrong workflow": func(f *fixture) {
			run := f.runs[123]
			run.WorkflowID++
			f.runs[123] = run
		},
		"wrong workflow title": func(f *fixture) {
			run := f.runs[123]
			run.DisplayTitle = "qip-agent-auto-revise|4|14"
			f.runs[123] = run
		},
	} {
		t.Run(name, func(t *testing.T) {
			f, _ := automaticFixture(t)
			f.change(func() { change(f) })
			if ok, err := openFixture(t, f.cfg).OpenRevision(context.Background()); ok || err == nil || f.writes != 0 {
				t.Fatalf("admission = %t, %v, writes = %d", ok, err, f.writes)
			}
		})
	}

	f, _ := automaticFixture(t)
	cfg := f.cfg
	cfg.Payload.Sender.ID++
	if _, err := Open(context.Background(), cfg); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("dispatch sender = %v", err)
	}
	cfg = f.cfg
	cfg.Ref = "refs/heads/agent-branch"
	if _, err := Open(context.Background(), cfg); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("dispatch branch = %v", err)
	}
}

func TestAutomaticRevisionConsumesEachReviewOnlyOnce(t *testing.T) {
	f, r := automaticFixture(t)
	ctx := context.Background()
	first := openFixture(t, f.cfg)
	a := accepted(t, first)
	if ok, err := first.OpenRevision(ctx); ok || err != nil || f.writes != 1 {
		t.Fatalf("duplicate dispatch = %t, %v, writes = %d", ok, err, f.writes)
	}
	second := f.dispatch(t, r, 124, 14)
	if ok, err := openFixture(t, second).OpenRevision(ctx); ok || !errors.Is(err, ErrAlreadyActive) || f.writes != 1 {
		t.Fatalf("concurrent request = %t, %v, writes = %d", ok, err, f.writes)
	}
	f.change(func() {
		run := f.runs[123]
		run.Status = "completed"
		f.runs[123] = run
	})
	if ok, err := openFixture(t, second).OpenRevision(ctx); ok || !errors.Is(err, ErrUnauthorized) || f.writes != 1 {
		t.Fatalf("duplicate comment = %t, %v, writes = %d", ok, err, f.writes)
	}
	newReview := f.reviews[0]
	newReview.ID++
	newReview.SubmittedAt = newReview.SubmittedAt.Add(time.Minute)
	f.change(func() { f.reviews = append(f.reviews, newReview) })
	r.Reviews = append(r.Reviews, autorequest.Review{ID: newReview.ID, Actor: newReview.User})
	next := openFixture(t, f.dispatch(t, r, 125, 15))
	b := accepted(t, next)
	if !slices.Equal(b.ReviewIDs, []int64{12}) || f.writes != 2 {
		t.Fatalf("remaining review = %#v, writes = %d", b, f.writes)
	}

	tip := strings.Repeat("d", 40)
	if err := first.Report(ctx, a, Result{Status: "published", Commit: tip}); err != nil {
		t.Fatal(err)
	}
	published := PublishedRevisions(f.comments, f.bot, f.pr.Base.Repo.ID, f.pr.Number)
	if published[tip] != 13 {
		t.Fatalf("published revision = %v", published)
	}
}

func TestAutomaticRevisionBatchesReviewsAndChecksCurrentCI(t *testing.T) {
	f, r := automaticFixture(t)
	f.change(func() { f.ciConclusion = "failure" })
	ctx := context.Background()
	snapshot, err := agentci.Read(ctx, f.cfg.Client, r.Head, "", nil)
	if err != nil {
		t.Fatal(err)
	}
	plan, err := agentci.PlanRepair(ctx, f.cfg.Client, snapshot)
	if err != nil || !plan.Ready || len(plan.Failures) != 1 {
		t.Fatalf("repair plan = %#v, %v", plan, err)
	}
	r.Failures = plan.Failures
	newReview := f.reviews[0]
	newReview.ID++
	newReview.SubmittedAt = newReview.SubmittedAt.Add(time.Minute)
	f.change(func() { f.reviews = append(f.reviews, newReview) })
	r.Reviews = append(r.Reviews, autorequest.Review{ID: newReview.ID, Actor: newReview.User})
	c := openFixture(t, f.dispatch(t, r, 123, 13))
	a := accepted(t, c)
	a, _, err = c.VerifyWork(ctx, a)
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(a.ReviewIDs, []int64{11, 12}) || len(a.FailureKeys) != 1 ||
		len(a.Feedback) != 7 || a.Feedback[6].Kind != "ci" || !strings.Contains(a.Feedback[6].Body, "TypeScript reported TS1234 at ui/a.ts:20:30.") {
		t.Fatalf("combined assignment = %#v", a)
	}
	if _, err := c.Verify(ctx, a); err != nil {
		t.Fatalf("current evidence = %v", err)
	}
	f.change(func() { f.ciConclusion = "success" })
	if _, err := c.Verify(ctx, a); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("superseded CI = %v", err)
	}
}

func TestAutomaticCIWaitsForRequiredChecksAndSkipsGreenReruns(t *testing.T) {
	f, r := automaticFixture(t)
	f.change(func() { f.ciConclusion = "failure" })
	ctx := context.Background()
	snapshot, err := agentci.Read(ctx, f.cfg.Client, r.Head, "", nil)
	if err != nil {
		t.Fatal(err)
	}
	plan, err := agentci.PlanRepair(ctx, f.cfg.Client, snapshot)
	if err != nil || len(plan.Failures) != 1 {
		t.Fatalf("repair plan = %#v, %v", plan, err)
	}
	r.Reviews, r.Failures = nil, plan.Failures
	c := openFixture(t, f.dispatch(t, r, 123, 13))
	f.change(func() { f.requiredChecks = "Compile\nRequired Later" })
	if ok, err := c.OpenRevision(ctx); ok || !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("incomplete required CI = %t, %v", ok, err)
	}
	f.change(func() { f.requiredChecks, f.ciConclusion = "Compile", "success" })
	if ok, err := c.OpenRevision(ctx); ok || !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("green rerun = %t, %v", ok, err)
	}
	f.change(func() { f.ciConclusion = "failure" })
	a := accepted(t, c)
	a, _, err = c.VerifyWork(ctx, a)
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(a.FailureKeys, []string{plan.Failures[0].Key}) || len(a.ReviewIDs) != 0 || len(a.Feedback) != 1 {
		t.Fatalf("CI-only assignment = %#v", a)
	}
}

func TestAutomaticRevisionKeepsFailedJobWhileUnrelatedWorkflowRetries(t *testing.T) {
	f, request := automaticFixture(t)
	ctx := t.Context()
	f.change(func() { f.ciConclusion = "failure" })
	read := func() agentci.RepairPlan {
		t.Helper()
		snapshot, err := agentci.Read(ctx, f.cfg.Client, request.Head, "", nil)
		if err != nil {
			t.Fatal(err)
		}
		plan, err := agentci.PlanRepair(ctx, f.cfg.Client, snapshot)
		if err != nil {
			t.Fatal(err)
		}
		return plan
	}
	first := read()
	if !first.Ready || len(first.Failures) != 1 {
		t.Fatalf("initial CI plan = %+v", first)
	}
	request.Reviews, request.Failures = nil, first.Failures
	c := openFixture(t, f.dispatch(t, request, 123, 13))
	f.change(func() { f.e2eRerunning = true })
	if read().Ready {
		t.Fatal("the coordinator would dispatch a new batch while E2E is queued")
	}
	f.change(func() { f.requiredChecks = "E2E" })
	if ok, err := c.OpenRevision(ctx); ok || !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("pending required E2E = %t, %v", ok, err)
	}
	f.change(func() { f.requiredChecks = "" })
	a := accepted(t, c)
	work, _, err := c.VerifyWork(ctx, a)
	if err != nil || !slices.Equal(work.FailureKeys, []string{first.Failures[0].Key}) || len(work.Feedback) != 1 {
		t.Fatalf("revision with pending optional E2E = %+v, %v", work, err)
	}
	f.change(func() { f.ciRunStatus = "queued" })
	if _, err := c.Verify(ctx, a); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("selected workflow rerunning = %v", err)
	}
}

func TestAutomaticRevisionRecoversLargeFeedbackForWorker(t *testing.T) {
	f, request := automaticFixture(t)
	f.change(func() {
		f.reviewSize = 13
		f.reviewBodySize = 4000
		f.ciConclusion = "failure"
	})
	ctx := t.Context()
	snapshot, err := agentci.Read(ctx, f.cfg.Client, request.Head, "", nil)
	if err != nil {
		t.Fatal(err)
	}
	plan, err := agentci.PlanRepair(ctx, f.cfg.Client, snapshot)
	if err != nil || len(plan.Failures) != 1 {
		t.Fatalf("repair plan = %#v, %v", plan, err)
	}
	request.Failures = plan.Failures
	cfg := f.dispatch(t, request, 123, 13)
	c := openFixture(t, cfg)
	stored := accepted(t, c)
	if len(stored.Feedback) != 0 || len(f.comments[0].Body) > 65536 || !slices.Equal(stored.ConsumedReviews(), []int64{11}) ||
		!slices.Equal(stored.FailureKeys, []string{plan.Failures[0].Key}) {
		t.Fatalf("receipt = %#v, comment size = %d", stored, len(f.comments[0].Body))
	}
	worker := openFixture(t, cfg)
	stored, found, err := worker.Assignment(ctx)
	if err != nil || !found {
		t.Fatalf("recovered receipt = %#v, %t, %v", stored, found, err)
	}
	a, checkpoint, err := worker.VerifyWork(ctx, stored)
	if err != nil || !stored.MatchesCheckpoint(checkpoint) || len(a.Feedback) != 15 {
		t.Fatalf("worker verification = %v, feedback count = %d", err, len(a.Feedback))
	}
	if a.Feedback[0].Body != f.reviews[0].Body {
		t.Fatal("the worker lost the review summary")
	}
	for i, feedback := range a.Feedback[1:14] {
		if feedback.Body != reviewCommentBody(i, 4000) ||
			feedback.URL != fmt.Sprintf("https://github.com/org/repo/pull/4#discussion_r%d", 110+i) ||
			feedback.Path != "ui/a.ts" || feedback.Line != 22 {
			t.Fatalf("worker changed inline feedback %d", i+1)
		}
	}
	if ci := a.Feedback[14]; ci.Kind != "ci" || ci.URL != plan.Failures[0].URL || !strings.Contains(ci.Body, plan.Failures[0].Excerpt) {
		t.Fatalf("worker CI feedback = %#v", ci)
	}
	input, err := json.Marshal(a)
	if err != nil || fmt.Sprintf("%x", sha256.Sum256(input)) != checkpoint.RevisionSHA256 {
		t.Fatalf("worker JSON differs from checkpoint: %v", err)
	}
	if len(base64.StdEncoding.EncodeToString(input)) <= 65536 {
		t.Fatal("the full worker input fits in a GitHub comment")
	}
	cfg.Run.RunAttempt = 2
	cfg.TriggerActor = f.reviewer.Login
	publisher := openFixture(t, cfg)
	stored, found, err = publisher.Assignment(ctx)
	if err != nil || !found || !stored.MatchesCheckpoint(checkpoint) {
		t.Fatalf("recovered publisher receipt = %#v, %t, %v", stored, found, err)
	}
	if _, err := publisher.Verify(ctx, stored); err != nil {
		t.Fatalf("publisher verification = %v", err)
	}
	f.change(func() { f.reviews[0].Body = "Edited feedback on the same commit." })
	if _, err := publisher.Verify(ctx, stored); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("changed feedback = %v", err)
	}
	f.change(func() { f.pr.Head.SHA = strings.Repeat("d", 40) })
	if _, err := publisher.Verify(ctx, stored); !errors.Is(err, ErrStale) {
		t.Fatalf("changed head = %v", err)
	}
}

func TestAutomaticRevisionAcceptsExistingReceiptWithInlineFeedback(t *testing.T) {
	f, _ := automaticFixture(t)
	c := openFixture(t, f.cfg)
	stored := accepted(t, c)
	a, _, err := c.VerifyWork(t.Context(), stored)
	if err != nil {
		t.Fatal(err)
	}
	legacy, err := json.Marshal(receipt{Assignment: a, CheckpointSHA: a.checkpointSHA})
	if err != nil {
		t.Fatal(err)
	}
	f.change(func() {
		f.comments[0].Body = receiptMarker + base64.StdEncoding.EncodeToString(legacy) + " -->"
		f.reviews[0].Body = "Edited feedback after admission."
	})
	stored, found, err := c.Assignment(t.Context())
	if err != nil || !found {
		t.Fatalf("legacy receipt = %#v, %t, %v", stored, found, err)
	}
	work, checkpoint, err := c.VerifyWork(t.Context(), stored)
	if err != nil || !stored.MatchesCheckpoint(checkpoint) || !slices.Equal(work.Feedback, a.Feedback) {
		t.Fatalf("legacy worker feedback = %#v, %v", work.Feedback, err)
	}
}

func TestLatestChangeRequestsUsesSubmissionOrderAndPreservesOtherReviews(t *testing.T) {
	f, _ := automaticFixture(t)
	old := f.reviews[0]
	approval := old
	approval.ID++
	approval.State = "APPROVED"
	next := old
	next.ID += 2
	last := old
	last.ID += 3
	last.SubmittedAt = last.SubmittedAt.Add(time.Minute)
	dismissed := last
	dismissed.ID++
	dismissed.State = "DISMISSED"
	f.change(func() { f.reviews = []discussion{dismissed, next, last, old, approval} })
	got, err := LatestChangeRequests(context.Background(), f.cfg.Client, f.pr.Number)
	if err != nil || len(got) != 2 || got[0].ID != next.ID || got[1].ID != last.ID {
		t.Fatalf("outstanding reviews = %#v, %v", got, err)
	}
}

func TestRevisionResultRecordedRequiresTheExactBotResult(t *testing.T) {
	f, r := automaticFixture(t)
	c := openFixture(t, f.cfg)
	a := accepted(t, c)
	ctx := context.Background()
	if err := c.Report(ctx, a, Result{Status: "failed"}); err != nil {
		t.Fatal(err)
	}
	result := f.comments[len(f.comments)-1]
	marker, err := autorequest.Encode(r)
	if err != nil {
		t.Fatal(err)
	}
	for _, test := range []struct {
		name string
		edit func(*fixer.Comment)
	}{
		{name: "human", edit: func(comment *fixer.Comment) { comment.User = f.reviewer }},
		{name: "wrong run", edit: func(comment *fixer.Comment) { comment.Body = strings.Replace(comment.Body, ":123:", ":124:", 1) }},
		{name: "wrong status", edit: func(comment *fixer.Comment) { comment.Body = strings.Replace(comment.Body, ":failed:", ":unknown:", 1) }},
		{name: "request body", edit: func(comment *fixer.Comment) { comment.Body = marker + "\n" + comment.Body }},
	} {
		t.Run(test.name, func(t *testing.T) {
			comment := result
			test.edit(&comment)
			if RevisionResultRecorded([]fixer.Comment{comment}, f.bot, a) {
				t.Fatal("accepted a noncanonical terminal result")
			}
		})
	}
	if !RevisionResultRecorded([]fixer.Comment{result}, f.bot, a) {
		t.Fatal("missed the canonical terminal result")
	}
}

func TestAutomaticPublisherRerunRequiresAnAllowedOperator(t *testing.T) {
	f, _ := automaticFixture(t)
	cfg := f.cfg
	cfg.Run.RunAttempt = 2
	cfg.TriggerActor = "another-user"
	if _, err := Open(t.Context(), cfg); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("unlisted rerun operator: %v", err)
	}
	cfg.TriggerActor = f.reviewer.Login
	if _, err := Open(t.Context(), cfg); err != nil {
		t.Fatalf("allowed rerun operator: %v", err)
	}
}
