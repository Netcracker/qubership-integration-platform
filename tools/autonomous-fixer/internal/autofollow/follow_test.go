package autofollow

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentpr"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/autorequest"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/hostedrevision"
)

var (
	baseSHA = strings.Repeat("a", 40)
	headSHA = strings.Repeat("b", 40)
	nextSHA = strings.Repeat("c", 40)
)

type reviewPayload struct {
	ID    int64            `json:"id"`
	Actor fixer.GitHubUser `json:"user"`
	State string           `json:"state"`
	Head  string           `json:"commit_id"`
	Time  time.Time        `json:"submitted_at"`
}

type fixture struct {
	mu            sync.Mutex
	t             *testing.T
	client        *fixer.GitHubClient
	options       Options
	pr            agentpr.PullRequest
	bot           fixer.GitHubUser
	reviewer      fixer.GitHubUser
	issue         fixer.Issue
	state         fixer.Comment
	reviews       []reviewPayload
	comments      []fixer.Comment
	runs          []runRecord
	check         string
	annotation    string
	pendingStatus bool
	moveOnRead    int
	pullReads     int
	lastDispatch  map[string]string
	dispatches    int
	retries       int
	failedAttempt int
	loseDispatch  bool
	dropDispatch  bool
}

func newFixture(t *testing.T) *fixture {
	t.Helper()
	repo := fixer.Repository{ID: 1, FullName: "org/repo", DefaultBranch: "main"}
	issue := fixer.Issue{ID: 43, Number: 3, State: "open", Title: "Fix navigation", Labels: []fixer.Label{{Name: "ready-for-agent"}}}
	user := fixer.GitHubUser{ID: 4, Type: "User", Login: "maintainer"}
	bot := fixer.GitHubUser{ID: 8, Type: "Bot", Login: "github-actions[bot]"}
	state, err := fixer.NewState(fixer.Seed{Repository: repo.FullName, IssueNumber: issue.Number, TaskID: "qip-3", BaseSHA: baseSHA,
		TrustedSHA: baseSHA, Requirements: issue.Requirements(), ScopePaths: []string{"ui"}}, user.Login)
	if err != nil {
		t.Fatal(err)
	}
	origin := fixer.RunIdentity{RunID: 57, RunAttempt: 1}
	state.Mode, state.Phase = "hosted", "waiting-ci"
	state.Executions = []fixer.Execution{{ID: "work-1", AttemptID: state.Attempt.ID, Mode: "hosted", TrustedSHA: baseSHA,
		Generation: 1, ContractVersion: 1, Stage: "work", Status: "completed", Run: &origin}}
	state.HostedCandidate = &fixer.HostedCandidate{ExecutionID: "work-1", Generation: 1, ContractVersion: 1, Run: origin, ArtifactID: 23,
		CandidateSHA256: strings.Repeat("d", 64), ReportSHA256: strings.Repeat("e", 64)}
	state.HostedPublication = &fixer.HostedPublication{Number: 2, Branch: "agent/fix/3/57-1", SHA: headSHA}
	body, err := fixer.EncodeStateComment(state)
	if err != nil {
		t.Fatal(err)
	}
	f := &fixture{t: t, options: Options{Repository: repo.FullName, Ref: "refs/heads/main", PullRequest: 2, Apply: true},
		pr: agentpr.PullRequest{ID: 54, Number: 2, Draft: true, State: "open",
			Head: agentpr.Ref{SHA: headSHA, Ref: state.HostedPublication.Branch, Repo: repo}, Base: agentpr.Ref{SHA: baseSHA, Ref: "main", Repo: repo}},
		bot: bot, reviewer: user, issue: issue, state: fixer.Comment{ID: 19, User: bot, Body: body}, failedAttempt: 1,
		reviews: []reviewPayload{}, comments: []fixer.Comment{}, runs: []runRecord{}}
	server := httptest.NewServer(http.HandlerFunc(f.serve))
	t.Cleanup(server.Close)
	f.client, err = fixer.NewGitHubClient(repo.FullName, "test-token", server.Client(), server.URL)
	if err != nil {
		t.Fatal(err)
	}
	return f
}

func (f *fixture) serve(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	send := func(data any) { _ = json.NewEncoder(w).Encode(data) }
	p := strings.TrimPrefix(r.URL.Path, "/repos/org/repo")
	switch r.Method + " " + p {
	case "GET ":
		send(f.pr.Base.Repo)
	case "GET /users/github-actions[bot]":
		send(f.bot)
	case "GET /pulls/2":
		f.pullReads++
		if f.moveOnRead > 0 && f.pullReads >= f.moveOnRead {
			f.pr.Head.SHA = nextSHA
		}
		send(f.pr)
	case "GET /issues/3":
		send(f.issue)
	case "GET /issues/3/comments":
		send([]fixer.Comment{f.state})
	case "GET /actions/variables/AGENT_CI_REQUIRED_CHECKS":
		http.NotFound(w, r)
	case "GET /actions/workflows/agent-work.yaml":
		send(map[string]any{"id": 12, "path": ".github/workflows/agent-work.yaml"})
	case "GET /actions/runs/501/attempts/1":
		send(f.runs[0])
	case "GET /issues/comments/200":
		send(struct {
			fixer.Comment
			IssueURL string `json:"issue_url"`
		}{f.comments[0], "https://api.github.com/repos/org/repo/issues/2"})
	case "GET /pulls/2/reviews/21":
		send(struct {
			reviewPayload
			Body string `json:"body"`
		}{f.reviews[0], "Preserve focus when navigation fails."})
	case "GET /pulls/2/reviews/21/comments":
		send([]any{})
	case "GET /issues/2/comments":
		send(f.comments)
	case "POST /issues/2/comments":
		var reply fixer.Comment
		if err := json.NewDecoder(r.Body).Decode(&reply); err != nil {
			f.t.Errorf("invalid comment: %v", err)
		}
		reply.ID, reply.User = int64(200+len(f.comments)), f.bot
		reply.CreatedAt = time.Now().UTC().Format(time.RFC3339)
		reply.UpdatedAt = reply.CreatedAt
		f.comments = append(f.comments, reply)
		send(reply)
	case "GET /actions/variables/AGENT_ENABLED":
		send(map[string]string{"value": "true"})
	case "GET /actions/variables/AGENT_MODE":
		send(map[string]string{"value": "hosted"})
	case "GET /actions/variables/AGENT_ALLOWED_ACTORS":
		send(map[string]string{"value": f.reviewer.Login + ",reader"})
	case "GET /pulls/2/reviews":
		send(f.reviews)
	case "GET /collaborators/maintainer/permission":
		send(map[string]any{"user": f.reviewer, "permission": "write"})
	case "GET /collaborators/reader/permission":
		send(map[string]any{"user": fixer.GitHubUser{ID: 404, Type: "User", Login: "reader"}, "permission": "read"})
	case "GET /rules/branches/main":
		send([]string{})
	case "GET /branches/main":
		send(map[string]bool{"protected": false})
	case "GET /commits/" + f.pr.Head.SHA + "/statuses":
		status := "success"
		if f.pendingStatus {
			status = "pending"
		}
		send([]map[string]string{{"context": "title", "state": status}})
	case "GET /commits/" + f.pr.Head.SHA + "/check-runs":
		checks := []map[string]any{}
		if f.check != "" {
			checks = append(checks, map[string]any{"id": 81, "name": "Test frontend", "head_sha": f.pr.Head.SHA, "status": "completed",
				"conclusion": f.check, "check_suite": map[string]int{"id": 89}, "app": map[string]any{"id": 2, "slug": "github-actions"}})
		}
		send(map[string]any{"check_runs": checks})
	case "GET /actions/runs":
		send(map[string]any{"workflow_runs": []any{map[string]any{
			"name": "Ui Build", "head_sha": f.pr.Head.SHA, "event": "pull_request", "check_suite_id": 89,
			"id": 72, "workflow_id": 2, "run_attempt": f.failedAttempt, "status": "completed", "conclusion": f.check,
		}}})
	case "GET /actions/runs/72":
		send(fixer.WorkflowRun{ID: 72, HeadSHA: f.pr.Head.SHA, RunAttempt: f.failedAttempt, Status: "completed", Conclusion: f.check})
	case "GET /actions/jobs/81":
		send(map[string]any{"id": 81, "run_id": 72, "head_sha": f.pr.Head.SHA, "status": "completed", "conclusion": f.check})
	case "GET /check-runs/81/annotations":
		send([]map[string]string{{"annotation_level": "failure", "message": f.annotation}})
	case "POST /actions/runs/72/rerun-failed-jobs":
		f.retries++
		w.WriteHeader(http.StatusCreated)
	case "GET /actions/workflows/agent-work.yaml/runs":
		send(map[string]any{"workflow_runs": f.runs})
	case "POST /actions/workflows/agent-work.yaml/dispatches":
		var dispatch struct {
			Ref    string            `json:"ref"`
			Inputs map[string]string `json:"inputs"`
		}
		if err := json.NewDecoder(r.Body).Decode(&dispatch); err != nil || dispatch.Ref != "main" || dispatch.Inputs["auto_pr"] != "2" {
			f.t.Errorf("invalid dispatch %+v: %v", dispatch, err)
		}
		id, err := fixer.PositiveID(dispatch.Inputs["auto_request"])
		if err != nil {
			f.t.Errorf("invalid request ID: %v", err)
		}
		f.lastDispatch = dispatch.Inputs
		f.dispatches++
		if !f.dropDispatch {
			f.runs = append(f.runs, runRecord{WorkflowRun: f.worker(int64(500+f.dispatches), id, true, "queued"), Actor: f.bot})
		}
		if f.loseDispatch || f.dropDispatch {
			w.WriteHeader(http.StatusBadGateway)
		} else {
			send(map[string]int64{"workflow_run_id": int64(500 + f.dispatches)})
		}
	default:
		if r.Method == "GET" && strings.HasPrefix(p, "/compare/") {
			send(map[string]string{"status": "ahead"})
			return
		}
		for _, run := range f.runs {
			if r.Method == "GET" && p == fmt.Sprintf("/actions/runs/%d", run.ID) {
				send(run)
				return
			}
		}
		f.t.Errorf("unexpected request %s %s", r.Method, r.URL)
		http.NotFound(w, r)
	}
}

func (f *fixture) worker(run, request int64, automatic bool, status string) fixer.WorkflowRun {
	title, event := fmt.Sprintf("qip-agent-revise|2|%d", request), "issue_comment"
	if automatic {
		title, event = fmt.Sprintf("qip-agent-auto-revise|2|%d", request), "workflow_dispatch"
	}
	return fixer.WorkflowRun{ID: run, RunAttempt: 1, WorkflowID: 12, HeadSHA: baseSHA, HeadBranch: "main", HeadRepository: f.pr.Base.Repo,
		DisplayTitle: title, Path: ".github/workflows/agent-work.yaml", Event: event, Status: status}
}

func (f *fixture) reconcile() (Report, error) {
	f.t.Helper()
	return Reconcile(f.t.Context(), f.client, f.client, f.options)
}

func (f *fixture) review(id int64, actor fixer.GitHubUser, state, head string, submitted time.Time) {
	f.reviews = append(f.reviews, reviewPayload{ID: id, Actor: actor, State: state, Head: head, Time: submitted})
}

func (f *fixture) addRequest(r autorequest.Request, created time.Time) {
	f.t.Helper()
	body, err := requestBody(r)
	if err != nil {
		f.t.Fatal(err)
	}
	stamp := created.UTC().Format(time.RFC3339)
	f.comments = append(f.comments, fixer.Comment{ID: 200, User: f.bot, Body: body, CreatedAt: stamp, UpdatedAt: stamp})
}

func (f *fixture) admit(request, run int64, automatic bool, head, key string, reviews ...int64) {
	f.t.Helper()
	actor := f.reviewer
	if automatic {
		actor = f.bot
	}
	receipt := struct {
		hostedrevision.Assignment
		CheckpointSHA string `json:"checkpointSha256"`
	}{
		Assignment: hostedrevision.Assignment{RepositoryID: 1, PullID: 54, PullNumber: 2, Issue: 3, CommentID: request,
			Run: fixer.RunIdentity{RunID: run, RunAttempt: 1}, OriginRun: fixer.RunIdentity{RunID: 57, RunAttempt: 1},
			Actor: actor, Head: head, WorkflowSHA: baseSHA, Automatic: automatic, ReviewIDs: reviews,
			CreatedAt: time.Now().UTC().Add(-10 * time.Minute).Format(time.RFC3339)},
		CheckpointSHA: strings.Repeat("e", 64),
	}
	if key != "" {
		receipt.FailureKeys = []string{key}
	}
	data, err := json.Marshal(receipt)
	if err != nil {
		f.t.Fatal(err)
	}
	f.comments = append(f.comments, fixer.Comment{ID: 230, User: f.bot, Body: "<!-- qip-agent-revision:v1 " + base64.StdEncoding.EncodeToString(data) + " -->"})
}

func TestCombinesReviewAndRepairAndRecoversLostDispatchResponse(t *testing.T) {
	f := newFixture(t)
	now := time.Now().UTC()
	f.review(21, f.reviewer, "CHANGES_REQUESTED", headSHA, now)
	f.review(22, fixer.GitHubUser{ID: 404, Type: "User", Login: "reader"}, "CHANGES_REQUESTED", headSHA, now)
	f.review(23, f.reviewer, "COMMENTED", headSHA, now)
	f.check, f.annotation, f.loseDispatch = "failure", "src/nav.ts(15,3): error TS2322: unsafe type", true
	f.options.Apply = false
	plan, err := f.reconcile()
	if err != nil || plan.Status != "planned" || len(plan.Reviews) != 1 || len(plan.Failures) != 1 || f.dispatches != 0 || len(f.comments) != 0 {
		t.Fatalf("preview = %+v, %v", plan, err)
	}
	f.options.Apply = true
	report, err := f.reconcile()
	if err != nil || report.Status != "dispatched" || report.RunID != 501 || f.dispatches != 1 || len(f.comments) != 1 {
		t.Fatalf("follow-up = %+v, %v", report, err)
	}
	request, err := autorequest.Decode(f.comments[0].Body)
	if err != nil || len(request.Reviews) != 1 || request.Reviews[0].ID != 21 || len(request.Failures) != 1 ||
		!strings.Contains(request.Failures[0].Excerpt, "TS2322") {
		t.Fatalf("combined request = %+v, %v", request, err)
	}
	report, err = f.reconcile()
	if err != nil || report.Status != "active" || f.dispatches != 1 || len(f.comments) != 1 {
		t.Fatalf("duplicate follow-up = %+v, %v", report, err)
	}
}

func TestRecoversUndeliveredRequestAndStopsAfterUncertainWrite(t *testing.T) {
	for _, drop := range []bool{false, true} {
		t.Run(fmt.Sprintf("drop=%t", drop), func(t *testing.T) {
			f := newFixture(t)
			now := time.Now().UTC()
			f.review(21, f.reviewer, "CHANGES_REQUESTED", headSHA, now.Add(-time.Hour))
			f.addRequest(autorequest.Request{RepositoryID: 1, PullID: 54, PullNumber: 2, Head: headSHA,
				Reviews: []autorequest.Review{{ID: 21, Actor: f.reviewer}}}, now.Add(-time.Hour))
			f.dropDispatch = drop
			report, err := f.reconcile()
			if !drop && (err != nil || report.Status != "dispatched") || drop && (err == nil || report.Status != "delivery-uncertain") ||
				f.dispatches != 1 || report.RequestID != 200 {
				t.Fatalf("request recovery = %+v, %v", report, err)
			}
			if _, err := f.reconcile(); err != nil || f.dispatches != 1 {
				t.Fatalf("uncertain or recorded dispatch was repeated: %v", err)
			}
		})
	}
}

func TestManualRevisionDefersFollowUpAndLateReviewNeedsReconfirmation(t *testing.T) {
	f := newFixture(t)
	now := time.Now().UTC()
	f.review(21, f.reviewer, "CHANGES_REQUESTED", headSHA, now.Add(-15*time.Minute))
	f.review(22, f.reviewer, "CHANGES_REQUESTED", headSHA, now.Add(-time.Minute))
	f.admit(16, 400, false, headSHA, "", 21)
	f.runs = append(f.runs, runRecord{WorkflowRun: f.worker(400, 16, false, "in_progress")})
	if report, err := f.reconcile(); err != nil || report.Status != "active" || len(f.comments) != 1 || f.dispatches != 0 {
		t.Fatalf("active manual revision = %+v, %v", report, err)
	}
	f.runs = nil
	f.comments = append(f.comments, fixer.Comment{ID: 240, User: f.bot, Body: fmt.Sprintf(
		"<!-- qip-agent-revision-result:v1 1:2:16:400:1:%s:failed:- -->", headSHA)})
	f.pr.Head.SHA = nextSHA
	if report, err := f.reconcile(); err != nil || report.Status != "needs-human" || len(f.comments) != 3 ||
		!strings.Contains(f.comments[2].Body, "pullrequestreview-22") || strings.Contains(f.comments[2].Body, "pullrequestreview-21") || f.dispatches != 0 {
		t.Fatalf("moved head follow-up = %+v, %v", report, err)
	}
	if _, err := f.reconcile(); err != nil || len(f.comments) != 3 || f.dispatches != 0 {
		t.Fatalf("reconfirmation repeated: %v", err)
	}
}

func TestRepeatedCIFailureNeedsHumanButFreshReviewStillDispatches(t *testing.T) {
	f := newFixture(t)
	f.check, f.annotation = "failure", "src/nav.ts(15,3): error TS2322: incompatible type"
	f.options.Apply = false
	first, err := f.reconcile()
	if err != nil || len(first.Failures) != 1 {
		t.Fatalf("initial CI plan = %+v, %v", first, err)
	}
	f.addRequest(autorequest.Request{RepositoryID: 1, PullID: 54, PullNumber: 2, Head: headSHA, Failures: first.Failures}, time.Now().Add(-10*time.Minute))
	f.admit(200, 400, true, headSHA, first.Failures[0].Key)
	f.comments = append(f.comments, fixer.Comment{ID: 240, User: f.bot, Body: fmt.Sprintf(
		"<!-- qip-agent-revision-result:v1 1:2:200:400:1:%s:published:%s -->", headSHA, nextSHA)})
	f.pr.Head.SHA = nextSHA
	f.review(21, f.reviewer, "CHANGES_REQUESTED", nextSHA, time.Now().UTC())
	f.options.Apply = true
	report, err := f.reconcile()
	if err != nil || len(report.Failures) != 0 || len(report.Human) != 1 || report.Status != "dispatched" || len(f.comments) != 5 || f.dispatches != 1 {
		t.Fatalf("repeated CI follow-up = %+v, %v", report, err)
	}
}

func TestInfraRetryOccursOnceForTheSameRun(t *testing.T) {
	f := newFixture(t)
	f.check, f.annotation = "failure", "The hosted runner encountered an error while running your job."
	report, err := f.reconcile()
	if err != nil || report.Status != "infra-retry" || f.retries != 1 || f.dispatches != 0 {
		t.Fatalf("infra retry = %+v, %v", report, err)
	}
	if report, err := f.reconcile(); err != nil || len(report.Human) != 0 || f.retries != 1 || len(f.comments) != 1 {
		t.Fatalf("pending retry observation = %+v, %v", report, err)
	}
	stamp := time.Now().UTC().Add(-time.Hour).Format(time.RFC3339)
	f.comments[0].CreatedAt, f.comments[0].UpdatedAt = stamp, stamp
	report, err = f.reconcile()
	if err != nil || report.Status != "needs-human" || f.retries != 1 || f.dispatches != 0 {
		t.Fatalf("second infra retry = %+v, %v", report, err)
	}
}

func TestRejectsMovedHeadBeforePostingARequest(t *testing.T) {
	f := newFixture(t)
	f.review(21, f.reviewer, "CHANGES_REQUESTED", headSHA, time.Now().UTC())
	f.moveOnRead = 2
	report, err := f.reconcile()
	if err != nil || report.Status != "stale" || len(f.comments) != 0 || f.dispatches != 0 {
		t.Fatalf("stale follow-up = %+v, %v", report, err)
	}
}

func TestPendingCIAndReviewCoalesceWhenChecksSettle(t *testing.T) {
	f := newFixture(t)
	f.check, f.annotation = "failure", "src/nav.ts(15,3): error TS2322: incompatible type"
	f.pendingStatus = true
	if report, err := f.reconcile(); err != nil || report.Status != "waiting-ci" || len(f.comments) != 0 {
		t.Fatalf("pending CI = %+v, %v", report, err)
	}
	f.review(21, f.reviewer, "CHANGES_REQUESTED", headSHA, time.Now().UTC())
	report, err := f.reconcile()
	if err != nil || report.Status != "waiting-ci" || len(report.Reviews) != 1 || len(report.Failures) != 0 || len(f.comments) != 0 {
		t.Fatalf("review while CI runs = %+v, %v", report, err)
	}
	f.pendingStatus = false
	report, err = f.reconcile()
	if err != nil || report.Status != "dispatched" || len(report.Reviews) != 1 || len(report.Failures) != 1 || f.dispatches != 1 || len(f.comments) != 1 {
		t.Fatalf("delayed CI repair = %+v, %v", report, err)
	}
}

func TestPendingCIDoesNotStarveOldReview(t *testing.T) {
	f := newFixture(t)
	f.check, f.annotation = "failure", "src/nav.ts(15,3): error TS2322: incompatible type"
	f.pendingStatus = true
	f.review(21, f.reviewer, "CHANGES_REQUESTED", headSHA, time.Now().UTC().Add(-time.Hour))
	report, err := f.reconcile()
	if err != nil || report.Status != "dispatched" || len(report.Reviews) != 1 || len(report.Failures) != 0 || len(f.comments) != 1 {
		t.Fatalf("review after waiting for CI = %+v, %v", report, err)
	}
}

func TestUsesBotReviewInputsOnTheTrustedRef(t *testing.T) {
	f := newFixture(t)
	now := time.Now().UTC()
	f.review(21, f.reviewer, "DISMISSED", headSHA, now)
	f.comments = append(f.comments, fixer.Comment{ID: 201, User: f.reviewer, Body: "/agent revise #pullrequestreview-21"})
	if report, err := f.reconcile(); err != nil || report.Status != "unchanged" || f.dispatches != 0 || len(f.comments) != 1 {
		t.Fatalf("dismissed review and human comment = %+v, %v", report, err)
	}
	f.review(22, f.reviewer, "CHANGES_REQUESTED", headSHA, now.Add(time.Second))
	f.options.Ref = "refs/heads/agent/fix/3/57-1"
	if report, err := f.reconcile(); err == nil || report.Status != "ineligible" || f.dispatches != 0 || len(f.comments) != 1 {
		t.Fatalf("untrusted ref = %+v, %v", report, err)
	}
}

func TestDispatchedBatchReachesWorkerCheckpoint(t *testing.T) {
	f := newFixture(t)
	f.review(21, f.reviewer, "CHANGES_REQUESTED", headSHA, time.Now().UTC())
	f.check, f.annotation = "failure", "src/nav.ts(15,3): error TS2322: incompatible type"
	report, err := f.reconcile()
	if err != nil || report.Status != "dispatched" {
		t.Fatalf("dispatch = %+v, %v", report, err)
	}
	ctx := t.Context()
	cfg := hostedrevision.Config{
		Client: f.client, Variables: f.client, Actor: f.bot.Login, TriggerActor: f.bot.Login,
		Repository: f.options.Repository, Ref: f.options.Ref, Event: "workflow_dispatch", WorkflowSHA: baseSHA,
		Run:     fixer.RunIdentity{RunID: report.RunID, RunAttempt: 1},
		Payload: fixer.GitHubPayload{Repository: f.pr.Base.Repo, Sender: f.bot, Inputs: f.lastDispatch},
	}
	worker, err := hostedrevision.Open(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	if admitted, err := worker.OpenRevision(ctx); err != nil || !admitted {
		t.Fatalf("admitted = %v, %v", admitted, err)
	}
	a, found, err := worker.Assignment(ctx)
	if err != nil || !found {
		t.Fatalf("assignment = %+v, %v", a, err)
	}
	a, checkpoint, err := worker.VerifyWork(ctx, a)
	if err != nil || len(a.Feedback) != 2 || checkpoint.Contract.BaseSHA != headSHA || checkpoint.RevisionSHA256 == "" {
		t.Fatalf("checkpoint = %+v, %v", checkpoint, err)
	}
	if admitted, err := worker.OpenRevision(ctx); err != nil || admitted {
		t.Fatalf("duplicate admission = %v, %v", admitted, err)
	}
	if second, err := f.reconcile(); err != nil || second.Status != "active" || f.dispatches != 1 {
		t.Fatalf("second event = %+v, %v", second, err)
	}
}

func TestStaleRevisionAsksToReconfirmAcceptedAndLateReviews(t *testing.T) {
	f := newFixture(t)
	now := time.Now().UTC()
	f.review(21, f.reviewer, "CHANGES_REQUESTED", headSHA, now.Add(-15*time.Minute))
	f.review(22, f.reviewer, "CHANGES_REQUESTED", headSHA, now.Add(-time.Minute))
	f.review(23, f.reviewer, "CHANGES_REQUESTED", headSHA, now.Add(-20*time.Minute))
	f.admit(16, 400, true, headSHA, "", 21)
	f.runs = append(f.runs, runRecord{WorkflowRun: f.worker(400, 16, true, "in_progress")})
	f.pr.Head.SHA = nextSHA
	if report, err := f.reconcile(); err != nil || report.Status != "active" || len(f.comments) != 1 || f.dispatches != 0 {
		t.Fatalf("active revision = %+v, %v", report, err)
	}
	f.comments = append(f.comments, fixer.Comment{ID: 240, User: f.bot, Body: fmt.Sprintf(
		"<!-- qip-agent-revision-result:v1 1:2:16:400:1:%s:stale:- -->", headSHA)})
	if report, err := f.reconcile(); err != nil || report.Status != "needs-human" || len(report.Human) != 2 ||
		len(f.comments) != 4 || f.dispatches != 0 {
		t.Fatalf("moved head follow-up = %+v, %v", report, err)
	}
	for i, id := range []int64{21, 22} {
		if !strings.Contains(f.comments[i+2].Body, fmt.Sprintf("#pullrequestreview-%d", id)) {
			t.Fatalf("review %d reconfirmation = %s", id, f.comments[i+2].Body)
		}
	}
	if _, err := f.reconcile(); err != nil || len(f.comments) != 4 || f.dispatches != 0 {
		t.Fatalf("reconfirmation repeated: %v", err)
	}

	f.review(24, f.reviewer, "CHANGES_REQUESTED", nextSHA, now)
	if report, err := f.reconcile(); err != nil || report.Status != "dispatched" ||
		len(report.Reviews) != 1 || report.Reviews[0].ID != 24 || f.dispatches != 1 || len(f.comments) != 5 {
		t.Fatalf("reconfirmed review follow-up = %+v, %v", report, err)
	}
}

func TestStaleRevisionForCurrentHeadDoesNotDispatchAgain(t *testing.T) {
	f := newFixture(t)
	f.review(21, f.reviewer, "CHANGES_REQUESTED", headSHA, time.Now().Add(-15*time.Minute))
	f.admit(16, 400, true, headSHA, "", 21)
	f.comments = append(f.comments, fixer.Comment{ID: 240, User: f.bot, Body: fmt.Sprintf(
		"<!-- qip-agent-revision-result:v1 1:2:16:400:1:%s:stale:- -->", headSHA)})
	if report, err := f.reconcile(); err != nil || report.Status != "unchanged" || len(f.comments) != 2 || f.dispatches != 0 {
		t.Fatalf("same head follow-up = %+v, %v", report, err)
	}
}
