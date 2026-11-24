package hostedrevision

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentpr"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentsignature"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

type fixture struct {
	mu             sync.Mutex
	cfg            Config
	pr             agentpr.PullRequest
	issue          fixer.Issue
	origin         string
	bot            fixer.GitHubUser
	organization   fixer.GitHubUser
	readClient     *fixer.GitHubClient
	state          fixer.Comment
	permission     string
	allowlist      string
	comparison     string
	publishedTip   string
	status         string
	feedbackPull   int64
	reviewSize     int
	reviewBodySize int
	feedbackBody   string
	commands       map[int64]fixer.Comment
	comments       []fixer.Comment
	writes         int
	commandPull    int64
	reviews        []discussion
	reviewer       fixer.GitHubUser
	runs           map[int64]fixer.WorkflowRun
	ciConclusion   string
	ciAnnotation   string
	ciRunStatus    string
	e2eRerunning   bool
	requiredChecks string
}

func revisionFixture(t *testing.T) *fixture {
	return revisionFixtureForOrganizationBot(t, false)
}

func revisionFixtureForOrganizationBot(t *testing.T, useOrganizationBot bool) *fixture {
	t.Helper()
	repo := fixer.Repository{ID: 10, FullName: "org/repo", DefaultBranch: "main"}
	user := fixer.GitHubUser{ID: 34, Login: "maintainer", Type: "User"}
	bot := fixer.GitHubUser{ID: 56, Login: "github-actions[bot]", Type: "Bot"}
	base, origin, head := strings.Repeat("a", 40), strings.Repeat("b", 40), strings.Repeat("c", 40)
	issue := fixer.Issue{Number: 3, Title: "Keep the selected row", State: "open", Labels: []fixer.Label{{Name: "ready-for-agent"}}}
	state, err := fixer.NewState(fixer.Seed{
		TaskID: "qip-3", Repository: repo.FullName, IssueNumber: issue.Number, BaseSHA: base,
		TrustedSHA: base, Requirements: issue.Requirements(), ScopePaths: []string{"ui"},
	}, user.Login)
	if err != nil {
		t.Fatal(err)
	}
	originRun := fixer.RunIdentity{RunID: 44, RunAttempt: 1}
	state.Mode, state.Phase = "hosted", "waiting-ci"
	state.Executions = append(state.Executions, fixer.Execution{
		ID: "work-1", Mode: "hosted", AttemptID: state.Attempt.ID, Generation: 1, ContractVersion: 1,
		TrustedSHA: base, Stage: "work", Status: "completed", Run: &originRun,
	})
	state.HostedCandidate = &fixer.HostedCandidate{
		ExecutionID: "work-1", Generation: 1, ContractVersion: 1, Run: originRun, ArtifactID: 21,
		CandidateSHA256: strings.Repeat("a", 64), ReportSHA256: strings.Repeat("b", 64),
	}
	state.HostedPublication = &fixer.HostedPublication{Number: 4, Branch: "agent/fix/3/44-1", SHA: origin}
	canonical, err := fixer.EncodeStateComment(state)
	if err != nil {
		t.Fatal(err)
	}
	command := fixer.Comment{ID: 13, User: user, Body: "/agent revise #discussion_r7 https://github.com/org/repo/pull/4#issuecomment-8\nKeep keyboard navigation working.",
		CreatedAt: "2026-11-19T12:00:00Z", UpdatedAt: "2026-11-19T12:00:00Z"}
	f := &fixture{
		cfg: Config{
			Repository: repo.FullName, Ref: "refs/heads/main", Event: "issue_comment", Actor: user.Login,
			TriggerActor: user.Login, WorkflowSHA: base, Run: fixer.RunIdentity{RunID: 123, RunAttempt: 1},
			Payload: fixer.GitHubPayload{Repository: repo, Issue: fixer.Issue{Number: 4, PullRequest: json.RawMessage(`{}`)},
				Comment: command, Sender: user, Action: "created"},
		},
		pr: agentpr.PullRequest{
			ID: 17, Number: 4, State: "open", Head: agentpr.Ref{SHA: head, Ref: state.HostedPublication.Branch, Repo: repo},
			Base: agentpr.Ref{SHA: base, Ref: repo.DefaultBranch, Repo: repo},
		},
		issue: issue, origin: origin, state: fixer.Comment{ID: 50, User: bot, Body: canonical}, bot: bot,
		permission: "write", allowlist: user.Login, feedbackPull: 4, feedbackBody: "Handle focus after closing the dialog.",
		comments: []fixer.Comment{}, comparison: "ahead", status: "in_progress", ciRunStatus: "completed", commands: map[int64]fixer.Comment{13: command},
		commandPull: 4, runs: make(map[int64]fixer.WorkflowRun),
	}
	if useOrganizationBot {
		f.organization = fixer.GitHubUser{ID: 57, Login: fixer.OrganizationBotLogin, Type: "User"}
	}
	server := httptest.NewServer(http.HandlerFunc(f.serve))
	t.Cleanup(server.Close)
	client, err := fixer.NewEngineeringGitHubClient(repo.FullName, "token", "org-token", useOrganizationBot, server.Client(), server.URL)
	if err != nil {
		t.Fatal(err)
	}
	f.readClient, err = fixer.NewEngineeringGitHubClient(repo.FullName, "token", "", useOrganizationBot, server.Client(), server.URL)
	if err != nil {
		t.Fatal(err)
	}
	f.cfg.Client, f.cfg.Variables = client, client
	return f
}

func (f *fixture) serve(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	send := func(value any) { _ = json.NewEncoder(w).Encode(value) }
	p := strings.TrimPrefix(r.URL.Path, "/repos/org/repo")
	switch {
	case p == "/users/github-actions[bot]":
		send(f.bot)
	case p == "/users/"+fixer.OrganizationBotLogin:
		send(f.organization)
	case p == "/user" && r.Header.Get("Authorization") == "Bearer org-token":
		send(f.organization)
	case p == "":
		send(f.cfg.Payload.Repository)
	case p == "/pulls/4":
		send(f.pr)
	case p == "/issues/3":
		send(f.issue)
	case p == "/issues/3/comments":
		send([]fixer.Comment{f.state})
	case p == "/issues/4/comments" && r.Method == "GET":
		send(f.comments)
	case p == "/issues/4/comments" && r.Method == "POST":
		var input struct{ Body string }
		_ = json.NewDecoder(r.Body).Decode(&input)
		if len(input.Body) > 65536 {
			http.Error(w, "comment body is too long", http.StatusUnprocessableEntity)
			return
		}
		reply := fixer.Comment{ID: int64(200 + f.writes), User: f.bot, Body: input.Body}
		if r.Header.Get("Authorization") == "Bearer org-token" {
			reply.User = f.organization
		}
		f.writes++
		f.comments = append(f.comments, reply)
		send(reply)
	case p == "/actions/variables/AGENT_ENABLED":
		send(map[string]string{"value": "true"})
	case p == "/actions/variables/AGENT_MODE":
		send(map[string]string{"value": "hosted"})
	case p == "/actions/variables/AGENT_ALLOWED_ACTORS":
		send(map[string]string{"value": f.allowlist})
	case p == "/actions/variables/AGENT_CI_REQUIRED_CHECKS" && f.requiredChecks != "":
		send(map[string]string{"value": f.requiredChecks})
	case p == "/collaborators/maintainer/permission":
		send(map[string]any{"permission": f.permission, "user": f.cfg.Payload.Comment.User})
	case p == "/collaborators/reviewer/permission":
		send(map[string]any{"permission": f.permission, "user": f.reviewer})
	case strings.HasPrefix(p, "/issues/comments/") && p != "/issues/comments/8":
		var id int64
		fmt.Sscanf(p, "/issues/comments/%d", &id)
		cmd, ok := f.commands[id]
		if !ok {
			http.NotFound(w, r)
			return
		}
		send(struct {
			fixer.Comment
			URL string `json:"issue_url"`
		}{cmd, fmt.Sprintf("https://api.github.com/repos/org/repo/issues/%d", f.commandPull)})
	case p == "/issues/comments/8":
		send(discussion{ID: 8, IssueURL: "https://api.github.com/repos/org/repo/issues/4", Body: "Cover the other layout."})
	case p == "/pulls/comments/7":
		send(discussion{ID: 7, PullAPIURL: fmt.Sprintf("https://api.github.com/repos/org/repo/pulls/%d", f.feedbackPull), Body: f.feedbackBody, Path: "ui/a.ts", Line: 22, ReviewID: 11})
	case p == "/pulls/4/reviews":
		send(f.reviews)
	case strings.HasPrefix(p, "/pulls/4/reviews/") && !strings.HasSuffix(p, "/comments"):
		var id int64
		fmt.Sscanf(p, "/pulls/4/reviews/%d", &id)
		for _, review := range f.reviews {
			if review.ID == id {
				send(review)
				return
			}
		}
		send(discussion{ID: id})
	case strings.HasPrefix(p, "/pulls/4/reviews/") && strings.HasSuffix(p, "/comments"):
		var id int64
		fmt.Sscanf(p, "/pulls/4/reviews/%d/comments", &id)
		comments := make([]discussion, f.reviewSize)
		for i := range comments {
			comments[i] = discussion{ID: id*10 + int64(i), PullAPIURL: fmt.Sprintf("https://api.github.com/repos/org/repo/pulls/%d", f.feedbackPull),
				Body: reviewCommentBody(i, f.reviewBodySize), User: f.reviewer, ReviewID: id, Path: "ui/a.ts", Line: 22}
		}
		send(comments)
	case p == "/compare/"+f.origin+"..."+f.pr.Head.SHA || (f.publishedTip != "" && p == "/compare/"+f.publishedTip+"..."+f.pr.Head.SHA):
		send(map[string]string{"status": f.comparison})
	case p == "/actions/workflows/agent-work.yaml":
		send(map[string]any{"id": 21, "path": ".github/workflows/agent-work.yaml"})
	case strings.HasPrefix(p, "/actions/runs/") && strings.HasSuffix(p, "/attempts/1") && len(f.runs) > 0:
		var id int64
		fmt.Sscanf(p, "/actions/runs/%d/attempts/1", &id)
		if run, ok := f.runs[id]; ok {
			send(run)
			return
		}
		http.NotFound(w, r)
	case p == "/actions/runs/123/attempts/1":
		send(fixer.WorkflowRun{ID: 123, RunAttempt: 1, HeadRepository: f.cfg.Payload.Repository, Event: "issue_comment",
			Path: ".github/workflows/agent-work.yaml", HeadBranch: "main", HeadSHA: f.cfg.WorkflowSHA,
			DisplayTitle: "qip-agent-revise|4|13", Status: f.status})
	case p == "/rules/branches/main":
		send([]any{})
	case p == "/branches/main":
		send(map[string]any{})
	case p == "/commits/"+f.pr.Head.SHA+"/check-runs":
		checks := []map[string]any{{
			"id": 87, "name": "Compile", "head_sha": f.pr.Head.SHA, "status": "completed", "conclusion": f.ciConclusion,
			"check_suite": map[string]any{"id": 77}, "app": map[string]any{"id": 1234, "slug": "github-actions"},
		}}
		if f.e2eRerunning {
			checks = append(checks, map[string]any{"id": 88, "name": "E2E", "head_sha": f.pr.Head.SHA, "status": "queued",
				"check_suite": map[string]any{"id": 78}, "app": map[string]any{"id": 1234, "slug": "github-actions"}})
		}
		send(map[string]any{"check_runs": checks})
	case p == "/commits/"+f.pr.Head.SHA+"/statuses":
		send([]any{})
	case p == "/actions/runs" && r.URL.Query().Get("head_sha") == f.pr.Head.SHA:
		runs := []map[string]any{{
			"id": 767, "workflow_id": 83, "name": "UI build", "head_sha": f.pr.Head.SHA, "check_suite_id": 77,
			"run_attempt": 1, "event": "pull_request", "status": f.ciRunStatus, "conclusion": f.ciConclusion,
		}}
		if f.e2eRerunning {
			runs = append(runs, map[string]any{"id": 768, "workflow_id": 84, "name": "E2E PR", "head_sha": f.pr.Head.SHA,
				"check_suite_id": 78, "event": "pull_request", "run_attempt": 2, "status": "queued"})
		}
		send(map[string]any{"workflow_runs": runs})
	case p == "/actions/jobs/87":
		send(map[string]any{"id": 87, "run_id": 767, "head_sha": f.pr.Head.SHA, "status": "completed", "conclusion": f.ciConclusion})
	case p == "/check-runs/87/annotations":
		message := f.ciAnnotation
		if message == "" {
			message = "ui/a.ts(20,30): error TS1234: invalid argument"
		}
		send([]map[string]string{{"annotation_level": "failure", "path": "ui/a.ts", "message": message}})
	default:
		http.NotFound(w, r)
	}
}

func reviewCommentBody(index, size int) string {
	label := fmt.Sprintf("Review comment %d", index+1)
	return label + strings.Repeat("x", max(0, size-len(label)))
}

func (f *fixture) change(change func()) {
	f.mu.Lock()
	defer f.mu.Unlock()
	change()
}

func openFixture(t *testing.T, cfg Config) *Controller {
	t.Helper()
	c, err := Open(context.Background(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func acceptedFixture(t *testing.T) (*fixture, *Controller, Assignment) {
	t.Helper()
	f := revisionFixture(t)
	ctx := context.Background()
	c := openFixture(t, f.cfg)
	ok, err := c.OpenRevision(ctx)
	if err != nil || !ok {
		t.Fatalf("admission = %t, %v", ok, err)
	}
	a, ok, err := c.Assignment(ctx)
	if err != nil || !ok {
		t.Fatalf("assignment = %#v, %t, %v", a, ok, err)
	}
	return f, c, a
}

func TestRevisionAdmitsOnceAndFreezesTheHumanHeadAndSelectedFeedback(t *testing.T) {
	f, c, a := acceptedFixture(t)
	ctx := context.Background()
	if a.Head != f.pr.Head.SHA || a.Head == f.origin || len(a.Feedback) != 2 || a.Feedback[0].Line != 22 ||
		a.Instruction != "Keep keyboard navigation working." || a.OriginRun.RunID != 44 {
		t.Fatalf("assignment = %#v", a)
	}
	f.change(func() { f.feedbackBody = "Edited feedback." })
	got, err := c.Verify(ctx, a)
	if err != nil || got.Contract.BaseSHA != a.Head || got.RevisionSHA256 == "" || got.Run.RunID != a.Run.RunID ||
		got.IssueNumber != 3 || len(got.Contract.ScopePaths) != 1 || got.Contract.ScopePaths[0] != "ui" {
		t.Fatalf("checkpoint = %#v, %v", got, err)
	}
	if !a.MatchesCheckpoint(got) {
		t.Fatal("the admitted checkpoint does not match its receipt")
	}
	got.RevisionSHA256 = strings.Repeat("a", 64)
	if a.MatchesCheckpoint(got) {
		t.Fatal("a result carrying different selected feedback matched the receipt")
	}
	still, _, err := c.Assignment(ctx)
	if err != nil || still.Feedback[0].Body != a.Feedback[0].Body {
		t.Fatalf("assignment = %#v, %v", still, err)
	}
	duplicate := f.cfg
	duplicate.Run.RunID++
	if ok, err := openFixture(t, duplicate).OpenRevision(ctx); err != nil || ok {
		t.Fatalf("duplicate admission = %t, %v", ok, err)
	}
	if f.writes != 1 || !strings.Contains(f.comments[0].Body, "/actions/runs/123/attempts/1") {
		t.Fatalf("receipts = %#v", f.comments)
	}
}

func TestRevisionRejectsUnsupportedOrUntrustedRequests(t *testing.T) {
	for name, change := range map[string]func(*fixture){
		"insufficient permissions":  func(f *fixture) { f.permission = "read" },
		"removed allowlist":         func(f *fixture) { f.allowlist = "other" },
		"cross pull feedback":       func(f *fixture) { f.feedbackPull++ },
		"oversized feedback":        func(f *fixture) { f.feedbackBody = strings.Repeat("x", maxFeedbackBytes+1) },
		"fork head":                 func(f *fixture) { f.pr.Head.Repo.ID++ },
		"forged publication branch": func(f *fixture) { f.pr.Head.Ref = "agent/fix/3/45-1" },
		"closed pull request":       func(f *fixture) { f.pr.State = "closed" },
		"rewritten branch":          func(f *fixture) { f.comparison = "diverged" },
		"retargeted pull request":   func(f *fixture) { f.pr.Base.Ref = "other" },
		"edited command": func(f *fixture) {
			command := f.commands[13]
			command.Body += "\nEdited"
			f.commands[13] = command
		},
	} {
		t.Run(name, func(t *testing.T) {
			f := revisionFixture(t)
			f.change(func() { change(f) })
			c := openFixture(t, f.cfg)
			if ok, err := c.OpenRevision(context.Background()); err == nil || ok {
				t.Fatalf("admission = %t, %v", ok, err)
			}
			if f.writes != 0 {
				t.Fatalf("created %d receipts", f.writes)
			}
		})
	}
}

func TestRevisionRejectsAnotherCommandWhileAnAcceptedRunIsActive(t *testing.T) {
	f, _, _ := acceptedFixture(t)
	cfg := f.cfg
	cfg.Run.RunID++
	cfg.Payload.Comment.ID++
	f.change(func() { f.commands[14] = cfg.Payload.Comment })
	c := openFixture(t, cfg)
	if ok, err := c.OpenRevision(context.Background()); !errors.Is(err, ErrAlreadyActive) || ok {
		t.Fatalf("admission = %t, %v", ok, err)
	}
	if f.writes != 1 {
		t.Fatalf("created %d receipts", f.writes)
	}
}

func TestRevisionDetectsHeadChangesAndClosureAndSignsItsReport(t *testing.T) {
	f, c, a := acceptedFixture(t)
	ctx := context.Background()
	f.change(func() { f.pr.Head.SHA = strings.Repeat("d", 40) })
	if _, err := c.CheckHead(ctx, a, ""); !errors.Is(err, ErrStale) {
		t.Fatalf("stale head = %v", err)
	}
	f.change(func() { f.pr.State = "closed" })
	if _, err := c.CheckHead(ctx, a, ""); !errors.Is(err, ErrClosed) {
		t.Fatalf("closed PR = %v", err)
	}
	result := Result{Status: "closed", Signature: &agentsignature.Signature{Model: "openrouter/test/model", Effort: "high"}}
	for range 2 {
		if err := c.Report(ctx, a, result); err != nil {
			t.Fatal(err)
		}
	}
	if f.writes != 2 || !strings.HasSuffix(f.comments[1].Body, "\n\n"+result.Signature.Line()) ||
		!strings.Contains(f.comments[1].Body, "The pull request closed before publication") {
		t.Fatalf("reports = %#v", f.comments)
	}
}

func TestRevisionRecognizesAConfirmedPushWhenThePublisherRetries(t *testing.T) {
	f, _, a := acceptedFixture(t)
	cfg := f.cfg
	cfg.Run.RunAttempt++
	ctx := context.Background()
	c := openFixture(t, cfg)
	if ok, err := c.OpenRevision(ctx); err != nil || ok {
		t.Fatalf("retried admission = %t, %v", ok, err)
	}
	original, ok, err := c.Assignment(ctx)
	if err != nil || !ok || original.Run != a.Run {
		t.Fatalf("retried assignment = %#v, %t, %v", original, ok, err)
	}
	tip := strings.Repeat("d", 40)
	f.change(func() {
		f.publishedTip = tip
		f.pr.Head.SHA = strings.Repeat("e", 40)
	})
	if already, err := c.CheckHead(ctx, a, tip); err != nil || !already {
		t.Fatalf("published ancestor = %t, %v", already, err)
	}
	if err := c.Report(ctx, a, Result{Status: "published", Commit: tip}); err != nil {
		t.Fatal(err)
	}
	forged := f.comments[1]
	forged.User = f.cfg.Payload.Comment.User
	authors := fixer.EngineeringAuthors{Actions: f.bot}
	published := PublishedCommits([]fixer.Comment{f.comments[1], forged}, authors, f.pr.Head.Repo.ID, f.pr.Number)
	if len(published) != 1 || published[tip] != f.comments[1].ID {
		t.Fatalf("published commits = %v", published)
	}
	if wronglyAttributed := PublishedCommits([]fixer.Comment{forged}, authors, f.pr.Head.Repo.ID, f.pr.Number); len(wronglyAttributed) != 0 {
		t.Fatalf("forged publication = %v", wronglyAttributed)
	}
}

func TestRevisionExpandsAnExplicitReviewWithAnEmptySummary(t *testing.T) {
	f := revisionFixture(t)
	selected, _, err := parseCommand("/agent revise #pullrequestreview-11", pullURL("org/repo", 4))
	if err != nil {
		t.Fatal(err)
	}
	f.change(func() { f.reviewSize = 2 })
	got, err := loadFeedback(context.Background(), f.cfg.Client, "org/repo", 4, selected)
	if err != nil || len(got) != 2 || got[0].URL != "https://github.com/org/repo/pull/4#discussion_r110" {
		t.Fatalf("feedback = %#v, %v", got, err)
	}
	f.change(func() { f.reviewSize = maxFeedback + 1 })
	if _, err := loadFeedback(context.Background(), f.cfg.Client, "org/repo", 4, selected); err == nil {
		t.Fatal("accepted too many review messages")
	}
}

func TestRevisionRequiresConcreteDistinctRefs(t *testing.T) {
	for _, body := range []string{
		"/agent revise", "/agent revise https://github.com/org/repo/pull/9#discussion_r7",
		"/agent revise #discussion_r7 #discussion_r7", "/agent revise #issuecomment-1\n" + strings.Repeat("x", maxInstructionBytes+1),
	} {
		if _, _, err := parseCommand(body, pullURL("org/repo", 4)); err == nil {
			t.Errorf("accepted command %q", body)
		}
	}
}

func TestManualRevisionConsumesOnlyExplicitlySelectedWholeReviews(t *testing.T) {
	for _, test := range []struct {
		link string
		want int
	}{
		{link: "#discussion_r7", want: 0},
		{link: "#pullrequestreview-11", want: 1},
	} {
		t.Run(test.link, func(t *testing.T) {
			f := revisionFixture(t)
			f.change(func() {
				f.cfg.Payload.Comment.Body = "/agent revise " + test.link
				f.commands[13] = f.cfg.Payload.Comment
				f.reviewSize = 1
			})
			a := accepted(t, openFixture(t, f.cfg))
			if got := a.ConsumedReviews(); len(got) != test.want || (test.want == 1 && got[0] != 11) {
				t.Fatalf("consumed reviews = %v", got)
			}
		})
	}
}
