package agentoutcome

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentpr"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

type outcomeFixture struct {
	gitFixture
	client            *fixer.GitHubClient
	config            Config
	event             CloseEvent
	current           agentpr.PullRequest
	state             *fixer.State
	stateAuthor, bot  fixer.GitHubUser
	organization      fixer.GitHubUser
	timeline          []timelineEvent
	comments          []fixer.Comment
	writes            []string
	commits, reviews  []map[string]any
	loseWriteResponse bool
}

func newOutcomeFixture(t *testing.T) *outcomeFixture {
	t.Helper()
	return newOutcomeFixtureWithWriter(t, false)
}

func newOutcomeFixtureWithWriter(t *testing.T, organization bool) *outcomeFixture {
	t.Helper()
	f := &outcomeFixture{gitFixture: newGitFixture(t)}
	f.file("product.txt", "baseline\n")
	base := f.commit("baseline")
	f.run("switch", "-qc", "draft")
	f.file("product.txt", "candidate\n")
	head := f.commit("candidate")
	repository := fixer.Repository{ID: 10, FullName: "org/repo", DefaultBranch: "main"}
	closedAt := time.Date(2026, 11, 19, 14, 43, 7, 0, time.UTC)
	f.current = agentpr.PullRequest{
		ID: 110, Number: 19, State: "closed", ClosedAt: closedAt, CreatedAt: closedAt.Add(-time.Hour),
		User: fixer.GitHubUser{ID: 501, Login: "publisher", Type: "User"},
		Head: agentpr.Ref{SHA: head, Ref: "agent/fix/8/73-2", Repo: repository},
		Base: agentpr.Ref{SHA: base, Ref: "main", Repo: repository},
	}
	f.event = CloseEvent{Action: "closed", Repository: repository, PullRequest: f.current}
	f.timeline = []timelineEvent{{ID: 102, Event: "closed", CreatedAt: closedAt}}
	f.comments, f.commits, f.reviews = []fixer.Comment{}, []map[string]any{}, []map[string]any{}
	f.bot = fixer.GitHubUser{ID: 42, Login: "github-actions[bot]", Type: "Bot"}
	f.stateAuthor = f.bot
	if organization {
		f.organization = fixer.GitHubUser{ID: 716, Login: "QipAutomation", Type: "User"}
	}
	state, err := fixer.NewState(fixer.Seed{TaskID: "qip-8", Repository: repository.FullName, IssueNumber: 8,
		BaseSHA: base, TrustedSHA: base, Requirements: "Fix the product defect.", ScopePaths: []string{"ui"}}, "operator")
	if err != nil {
		t.Fatal(err)
	}
	state.Mode, state.Phase = "hosted", "waiting-ci"
	run := fixer.RunIdentity{RunID: 73, RunAttempt: 2}
	execution := fixer.Execution{ID: "qip-8/execution/1", Mode: "hosted", AttemptID: state.Attempt.ID,
		Generation: 1, ContractVersion: 1, TrustedSHA: base, Stage: "work", Status: "completed", Run: &run}
	state.Executions = []fixer.Execution{execution}
	state.HostedCandidate = &fixer.HostedCandidate{ExecutionID: execution.ID, Generation: 1, ContractVersion: 1,
		Run: run, ArtifactID: 1, CandidateSHA256: strings.Repeat("a", 64), ReportSHA256: strings.Repeat("b", 64)}
	state.HostedPublication = &fixer.HostedPublication{Number: f.current.Number, SHA: head, Branch: f.current.Head.Ref,
		BaseRef: f.current.Base.Ref, BaseSHA: base}
	f.state = state
	server := httptest.NewServer(http.HandlerFunc(f.serve))
	t.Cleanup(server.Close)
	f.client, err = fixer.NewEngineeringGitHubClient(repository.FullName, "test-token", "organization-token", f.organization.Login, organization, server.Client(), server.URL)
	if err != nil {
		t.Fatal(err)
	}
	f.config = Config{Repository: repository.FullName, Workspace: f.workspace, Workflow: Workflow{RunID: 100, RunAttempt: 1}}
	return f
}

func (f *outcomeFixture) serve(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/repos/org/repo")
	var value any
	if r.Method == "GET" {
		switch {
		case path == "":
			value = f.event.Repository
		case path == "/users/"+f.organization.Login || path == "/user" && r.Header.Get("Authorization") == "Bearer organization-token":
			value = f.organization
		case path == "/users/github-actions[bot]":
			value = f.bot
		case path == "/pulls/19":
			value = f.current
		case path == "/issues/8/comments":
			body, err := fixer.EncodeStateComment(f.state)
			if err != nil {
				f.t.Error(err)
				w.WriteHeader(http.StatusInternalServerError)
				return
			}
			value = []fixer.Comment{{ID: 123, Body: body, User: f.stateAuthor}}
		case path == "/issues/19/events":
			value = f.timeline
		case path == "/issues/19/comments":
			value = f.comments
		case path == "/pulls/19/reviews":
			value = f.reviews
		case path == "/pulls/19/commits":
			value = f.commits
		case path == "/pulls/19/comments" || strings.HasPrefix(path, "/rules/branches/"):
			value = []any{}
		case strings.HasPrefix(path, "/branches/"):
			value = map[string]bool{"protected": false}
		case path == "/commits/"+f.current.Head.SHA+"/check-runs":
			value = map[string]any{"check_runs": []map[string]string{{
				"name": "Product checks", "head_sha": f.current.Head.SHA, "status": "completed", "conclusion": "success",
			}}}
		case path == "/commits/"+f.current.Head.SHA+"/statuses":
			value = []any{}
		default:
			f.t.Errorf("unexpected API read %s", path)
			w.WriteHeader(http.StatusNotFound)
			return
		}
	} else {
		var edit struct {
			Body string `json:"body"`
		}
		if json.NewDecoder(r.Body).Decode(&edit) != nil {
			f.t.Errorf("invalid outcome write %s", path)
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		f.writes = append(f.writes, r.Method+" "+path)
		comment := fixer.Comment{ID: 900, Body: edit.Body, User: f.bot}
		if r.Header.Get("Authorization") == "Bearer organization-token" {
			comment.User = f.organization
		}
		switch {
		case r.Method == "POST" && path == "/issues/19/comments":
			f.comments = append(f.comments, comment)
		case r.Method == "PATCH" && path == "/issues/comments/900":
			f.comments[len(f.comments)-1] = comment
		default:
			f.t.Errorf("unexpected API write %s %s", r.Method, path)
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		if f.loseWriteResponse {
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		value = comment
	}
	if json.NewEncoder(w).Encode(value) != nil {
		f.t.Error("could not encode API fixture")
	}
}

func (f *outcomeFixture) read() (*Report, error) {
	return ReadAndRecord(context.Background(), f.client, f.config, f.event)
}

func TestClosedUnmergedIsIdempotentAcrossReopenAndReclose(t *testing.T) {
	f := newOutcomeFixture(t)
	f.loseWriteResponse = true
	reviewer := fixer.GitHubUser{ID: 31, Login: "reviewer", Type: "User"}
	f.comments = []fixer.Comment{{
		ID: 87, Body: "<!-- qip-agent-outcome:v1 19 102 1795099387 100 -->\n", User: reviewer,
		CreatedAt: f.current.ClosedAt.Add(-time.Minute).Format(time.RFC3339),
	}}
	f.reviews = []map[string]any{{"id": 412, "user": reviewer, "submitted_at": f.current.ClosedAt.Add(-time.Hour)}}
	first, err := f.read()
	if err != nil || first == nil || first.Disposition != "closed-unmerged" || first.HeadChange != "unchanged" ||
		first.ContentChange != "unchanged" || first.MergeSHA != "" || first.CI.Status != "passed" ||
		first.CI.HeadSHA != first.Final.HeadSHA || first.CIObservedAt.IsZero() || first.HumanInvolvement.Status != "observed" ||
		len(first.HumanInvolvement.Discussion) != 2 {
		t.Fatalf("unexpected first outcome: %+v, %v", first, err)
	}
	dir := t.TempDir()
	if err = first.Save(dir); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"outcome.json", "published.normalized.diff", "final.normalized.diff"} {
		if _, err = os.Stat(filepath.Join(dir, name)); err != nil {
			t.Fatal(err)
		}
	}
	if duplicate, err := f.read(); err != nil || duplicate != nil || len(f.writes) != 1 {
		t.Fatalf("duplicate closure changed the stored report: %+v, %v, %v", duplicate, err, f.writes)
	}

	f.current.State = "open"
	f.timeline = append(f.timeline, timelineEvent{ID: 103, Event: "reopened", CreatedAt: f.current.ClosedAt.Add(time.Second)})
	if stale, err := f.read(); err != nil || stale != nil {
		t.Fatalf("reopened pull request accepted a stale closure: %+v, %v", stale, err)
	}
	f.current.State = "closed"
	f.current.ClosedAt = f.current.ClosedAt.Add(2 * time.Second)
	f.file("regression.txt", "test the fix\n")
	f.current.Head.SHA = f.commit("add a regression case")
	f.timeline = append(f.timeline, timelineEvent{ID: 104, Event: "closed", CreatedAt: f.current.ClosedAt})
	if stale, err := f.read(); err != nil || stale != nil {
		t.Fatalf("new closure accepted the old webhook: %+v, %v", stale, err)
	}
	f.event.PullRequest = f.current
	f.config.Workflow.RunID++
	f.commits = []map[string]any{{"sha": f.current.Head.SHA, "author": reviewer,
		"committer": fixer.GitHubUser{ID: 198, Login: "web-flow", Type: "Bot"},
		"commit":    map[string]any{"verification": map[string]bool{"verified": true}}}}
	second, err := f.read()
	if err != nil || second == nil || second.ClosureID != 104 || second.HeadChange != "changed" || second.ContentChange != "changed" ||
		!slices.Equal(second.Final.Paths, []string{"product.txt", "regression.txt"}) ||
		len(second.HumanInvolvement.Commits) != 1 || second.HumanInvolvement.Commits[0].Author != reviewer ||
		!slices.Equal(f.writes, []string{"POST /issues/19/comments", "PATCH /issues/comments/900"}) {
		t.Fatalf("unexpected second outcome: %+v, %v, %v", second, err, f.writes)
	}
}

func TestMergedOutcomeUsesMergeTimelineEventAndFinalHead(t *testing.T) {
	f := newOutcomeFixture(t)
	f.run("switch", "-q", "main")
	f.run("merge", "-q", "--squash", "draft")
	merge := f.commit("integrate the candidate")
	f.current.Merged = true
	f.current.MergedAt = f.current.ClosedAt
	f.current.MergeSHA = merge
	f.current.Base.SHA = merge
	f.event.PullRequest = f.current
	f.timeline = []timelineEvent{
		{ID: 102, Event: "merged", CreatedAt: f.current.ClosedAt},
		{ID: 103, Event: "closed", CreatedAt: f.current.ClosedAt.Add(time.Second)},
	}
	report, err := f.read()
	if err != nil || report == nil || report.Disposition != "merged" || report.ClosureID != 102 ||
		report.MergeSHA != merge || report.Final.HeadSHA == merge || report.Final.BaseSHA != merge ||
		report.CI.TestMergeSHA != "" || report.ContentChange != "unchanged" {
		t.Fatalf("merge SHA contaminated the comparison: %+v, %v", report, err)
	}
	if duplicate, err := f.read(); err != nil || duplicate != nil || len(f.writes) != 1 {
		t.Fatalf("delayed closed event made a duplicate merged report: %+v, %v, %v", duplicate, err, f.writes)
	}
}

func TestOutcomeUnknownWithoutHistoricalPublicationBase(t *testing.T) {
	f := newOutcomeFixture(t)
	f.state.HostedPublication.BaseRef, f.state.HostedPublication.BaseSHA = "", ""
	f.state.Phase, f.state.Desired, f.state.HostedCandidate = "stopped", "stopped", nil
	f.state.Generation++
	report, err := f.read()
	if err != nil || report == nil || report.HeadChange != "unchanged" || report.ContentChange != "unknown" ||
		report.Final.PatchSHA256 == "" || report.Published.Diagnostic == "" || report.Worker.RunID != 73 {
		t.Fatalf("published receipt was lost after stop or an absent base was guessed: %+v, %v", report, err)
	}
}

func TestOutcomeDoesNotTrustUnconfirmedPublications(t *testing.T) {
	f := newOutcomeFixture(t)
	f.stateAuthor = fixer.GitHubUser{ID: 501, Login: "publisher", Type: "User"}
	if report, err := f.read(); err != nil || report != nil {
		t.Fatalf("outcome accepted a forged state comment: %+v, %v", report, err)
	}
	f.stateAuthor = f.bot
	f.state.Phase = "awaiting-publication"
	f.state.HostedPublication = nil
	if report, err := f.read(); !errors.Is(err, agentpr.ErrPublicationPending) || report != nil || len(f.writes) != 0 {
		t.Fatalf("outcome wrote before publication confirmation: %+v, %v", report, err)
	}
	f.event.PullRequest.Head.Repo.ID++
	if report, err := f.read(); err != nil || report != nil || len(f.writes) != 0 {
		t.Fatalf("outcome accepted a fork closure: %+v, %v", report, err)
	}
}

func TestAgentRevisionReceiptKeepsCommitAttributionSeparateFromHumanActivity(t *testing.T) {
	f := newOutcomeFixture(t)
	f.file("product.txt", "revised candidate\n")
	revised := f.commit("revise the candidate")
	f.current.Head.SHA = revised
	f.event.PullRequest = f.current
	f.commits = []map[string]any{{"sha": revised, "author": fixer.GitHubUser{ID: 50, Login: "someone", Type: "User"},
		"committer": fixer.GitHubUser{ID: 198, Login: "web-flow", Type: "Bot"},
		"commit":    map[string]any{"verification": map[string]bool{"verified": true}}}}
	f.comments = []fixer.Comment{{ID: 135, User: f.bot, Body: "### Agent revision\n\n" +
		"<!-- qip-agent-revision-result:v1 10:19:125:673:1:" + f.state.HostedPublication.SHA + ":published:" + revised + " -->"}}
	report, err := f.read()
	if err != nil || report == nil || report.ContentChange != "changed" || report.HeadChange != "changed" ||
		report.HumanInvolvement.Status != "unknown" || len(report.HumanInvolvement.Commits) != 1 ||
		report.HumanInvolvement.Commits[0].RevisionCommentID != 135 {
		t.Fatalf("agent revision was attributed to a human: %+v, %v", report, err)
	}
}

func TestRerunningTheOutcomeWorkflowRecoversAnArtifactWithoutEditingTheComment(t *testing.T) {
	f := newOutcomeFixture(t)
	first, err := f.read()
	if err != nil || first == nil || len(f.comments) != 1 {
		t.Fatalf("did not record the initial outcome: %+v, %v", first, err)
	}
	firstComment := f.comments[0]
	wantLink := "https://github.com/org/repo/actions/runs/100)"
	if !strings.Contains(firstComment.Body, wantLink) {
		t.Fatal("outcome link does not survive a workflow rerun", firstComment.Body)
	}
	f.config.Workflow.RunAttempt++
	retry, err := f.read()
	if err != nil || retry == nil || retry.ClosureID != first.ClosureID || retry.Workflow.RunAttempt != 2 ||
		retry.Published.PatchSHA256 != first.Published.PatchSHA256 || retry.Final.PatchSHA256 != first.Final.PatchSHA256 ||
		f.comments[0] != firstComment || len(f.writes) != 1 {
		t.Fatalf("retry did not recover the missing artifact without an external write: %+v, %v, %v", retry, err, f.writes)
	}
	dir := t.TempDir()
	if err = retry.Save(dir); err != nil {
		t.Fatal(err)
	}
	if _, err = os.Stat(filepath.Join(dir, "outcome.json")); err != nil {
		t.Fatal(err)
	}
	f.config.Workflow.RunID++
	if duplicate, err := f.read(); err != nil || duplicate != nil || len(f.writes) != 1 {
		t.Fatalf("unequal run identity was accepted as the retry: %+v, %v", duplicate, err)
	}
}

func TestOrganizationOutcomeRecognizesLegacyStateAndIgnoresBotAsHuman(t *testing.T) {
	f := newOutcomeFixtureWithWriter(t, true)
	f.comments = append(f.comments, fixer.Comment{ID: 700, User: f.organization, Body: "CI observation", CreatedAt: f.current.CreatedAt.Format(time.RFC3339)})
	report, err := f.read()
	if err != nil || report == nil {
		t.Fatalf("record outcome: %v, %v", report, err)
	}
	if len(report.HumanInvolvement.Discussion) != 0 || len(f.comments) != 2 || f.comments[1].User != f.organization {
		t.Fatalf("unexpected participants or publisher: %+v, %+v", report.HumanInvolvement, f.comments)
	}
	if retry, err := f.read(); err != nil || retry != nil || len(f.writes) != 1 {
		t.Fatalf("replayed outcome: %v, %v, writes %v", retry, err, f.writes)
	}
}
