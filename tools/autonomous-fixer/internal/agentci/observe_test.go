package agentci

import (
	"encoding/json"
	"net/http"
	"strings"
	"sync"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentpr"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

type observerFixture struct {
	mu           sync.Mutex
	client       *fixer.GitHubClient
	repository   fixer.Repository
	pr           agentpr.PullRequest
	stateComment fixer.Comment
	prComments   []fixer.Comment
	opts         Options
	checkStatus  string
	posted       int
	patched      int
	checkReads   int
	prReads      int
	changeOnRead bool
	closeOnRead  bool
}

func observerTestFixture(t *testing.T) *observerFixture {
	t.Helper()
	repo := fixer.Repository{ID: 100, FullName: "org/repo", DefaultBranch: "main"}
	bot := fixer.GitHubUser{ID: 12, Login: "github-actions[bot]", Type: "Bot"}
	run := fixer.RunIdentity{RunID: 89, RunAttempt: 2}
	pr := agentpr.PullRequest{Number: 44, ID: 11, State: "open", Draft: true,
		Head: agentpr.Ref{Ref: "agent/fix/22/89-2", SHA: testHead, Repo: repo}, Base: agentpr.Ref{Ref: "main", SHA: testBase, Repo: repo}}
	state, err := fixer.NewState(fixer.Seed{TaskID: "qip-22", Repository: repo.FullName, IssueNumber: 22, BaseSHA: testBase, TrustedSHA: testBase,
		Requirements: "Fix the defect", ScopePaths: []string{"ui"}}, "someone")
	if err != nil {
		t.Fatal(err)
	}
	state.Mode = "hosted"
	state.Phase = "waiting-ci"
	state.Executions = []fixer.Execution{{ID: "original-worker", Mode: "hosted", AttemptID: state.Attempt.ID, Generation: state.Generation,
		ContractVersion: state.Contract.Version, TrustedSHA: testBase, Stage: "work", Status: "completed", Run: &run}}
	state.HostedPublication = &fixer.HostedPublication{Number: pr.Number, SHA: testPriorHead, Branch: pr.Head.Ref}
	body, err := fixer.EncodeStateComment(state)
	if err != nil {
		t.Fatal(err)
	}
	f := &observerFixture{repository: repo, pr: pr, stateComment: fixer.Comment{ID: 1000, User: bot, Body: body}, checkStatus: "pending",
		opts: Options{EventName: "workflow_dispatch", Repository: repo.FullName, Ref: "refs/heads/main", PullRequest: pr.Number, Apply: true,
			Event: Event{Repository: repo}, RequiredChecks: "build\nexpected"}}
	f.client = testClient(t, func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		defer f.mu.Unlock()
		switch r.Method + " " + r.URL.Path {
		case "GET /repos/org/repo":
			fixture(w, f.repository)
		case "GET /repos/org/repo/pulls/44":
			pr := f.pr
			if r.Header.Get("X-GitHub-Api-Version") != "2022-11-28" {
				pr.MergeSHA = ""
			}
			f.prReads++
			if f.prReads >= 2 {
				if f.changeOnRead {
					pr.Head.SHA = testPriorHead
				}
				if f.closeOnRead {
					pr.State = "closed"
				}
			}
			fixture(w, pr)
		case "GET /repos/org/repo/pulls":
			fixture(w, []agentpr.PullRequest{f.pr})
		case "GET /users/github-actions[bot]":
			fixture(w, bot)
		case "GET /repos/org/repo/issues/22/comments":
			fixture(w, []fixer.Comment{f.stateComment})
		case "GET /repos/org/repo/rules/branches/main":
			fixture(w, []string{})
		case "GET /repos/org/repo/branches/main":
			fixture(w, map[string]bool{"protected": false})
		case "GET /repos/org/repo/commits/" + testHead + "/check-runs", "GET /repos/org/repo/commits/" + testMerge + "/check-runs":
			f.checkReads++
			fixture(w, map[string][]string{"check_runs": {}})
		case "GET /repos/org/repo/commits/" + testMerge:
			if r.Header.Get("X-GitHub-Api-Version") != "2026-03-10" {
				t.Error("merge commit verification changed the API version")
			}
			fixture(w, map[string]any{"sha": testMerge, "parents": []map[string]string{{"sha": testBase}, {"sha": testHead}}})
		case "GET /repos/org/repo/commits/" + testMerge + "/statuses":
			fixture(w, []string{})
		case "GET /repos/org/repo/commits/" + testHead + "/statuses":
			statuses := []map[string]string{{"context": "build", "state": "success"}}
			if f.checkStatus != "" {
				statuses = append(statuses, map[string]string{"context": "expected", "state": f.checkStatus})
			}
			fixture(w, statuses)
		case "GET /repos/org/repo/issues/44/comments":
			comments := f.prComments
			if comments == nil {
				comments = []fixer.Comment{}
			}
			fixture(w, comments)
		case "POST /repos/org/repo/issues/44/comments", "PATCH /repos/org/repo/issues/comments/177":
			var comment fixer.Comment
			if err := json.NewDecoder(r.Body).Decode(&comment); err != nil {
				t.Errorf("invalid comment body: %v", err)
			}
			comment.ID, comment.User = 177, bot
			if r.Method == "POST" {
				f.posted++
				f.prComments = append(f.prComments, comment)
			} else {
				f.patched++
				f.prComments[len(f.prComments)-1] = comment
			}
			fixture(w, comment)
		default:
			t.Errorf("unexpected request %s %s", r.Method, r.URL)
			http.NotFound(w, r)
		}
	})
	return f
}

func TestObservationUpdatesOneVerifiedCommentAfterMissingCheckAppears(t *testing.T) {
	f := observerTestFixture(t)
	f.checkStatus = ""
	// A copied marker on a human comment carries no publication authority.
	f.prComments = []fixer.Comment{{ID: 9, User: fixer.GitHubUser{ID: 12, Type: "User"}, Body: "<!-- qip-agent-ci:v1 100 44 -->"}}
	first, err := Observe(t.Context(), f.client, f.opts)
	if err != nil || len(first) != 1 || first[0].Disposition != "published" || first[0].CI.Status != "missing" || first[0].CI.HeadSHA != testHead || first[0].PublicationSHA != testPriorHead {
		t.Fatalf("first observation = %+v, %v", first, err)
	}
	f.mu.Lock()
	comment := f.prComments[1]
	f.checkStatus = "success"
	f.mu.Unlock()
	for _, want := range []string{testHead, "| expected | missing | Head | Yes |", "Check whether their workflows ran", "<sub>Observed by QIP agent CI in GitHub Actions.</sub>"} {
		if !strings.Contains(comment.Body, want) {
			t.Errorf("observation comment missing %q", want)
		}
	}
	opts := f.opts
	opts.EventName = "schedule"
	second, err := Observe(t.Context(), f.client, opts)
	if err != nil || len(second) != 1 || second[0].Disposition != "published" || second[0].CI.Status != "passed" || second[0].CommentID != first[0].CommentID {
		t.Fatalf("second observation = %+v, %v", second, err)
	}
	third, err := Observe(t.Context(), f.client, opts)
	if err != nil || len(third) != 1 || third[0].Disposition != "unchanged" {
		t.Fatalf("duplicate observation = %+v, %v", third, err)
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.posted != 1 || f.patched != 1 || len(f.prComments) != 2 || f.prComments[0].ID != 9 {
		t.Fatal("CI wrote duplicate comments or modified a human comment")
	}
}

func TestObservationRequiresCanonicalPublicationAndUnchangedPR(t *testing.T) {
	for _, name := range []string{"forged receipt", "moved head", "closed PR", "stale completion", "test merge completion"} {
		t.Run(name, func(t *testing.T) {
			f := observerTestFixture(t)
			want := ""
			switch name {
			case "forged receipt":
				f.stateComment.User = fixer.GitHubUser{ID: 999, Type: "User"}
			case "moved head":
				f.changeOnRead, want = true, "stale"
			case "closed PR":
				f.closeOnRead, want = true, "stale"
			case "stale completion", "test merge completion":
				f.pr.MergeSHA = testMerge
				f.opts.EventName, f.opts.Event.Action = "workflow_run", "completed"
				run := fixer.WorkflowRun{Event: "pull_request", Status: "completed", ID: 144, HeadRepository: f.repository, HeadBranch: "refs/pull/44/merge", HeadSHA: testPriorHead}
				if name == "test merge completion" {
					run.HeadSHA, want = testMerge, "published"
				}
				f.opts.Event.WorkflowRun.WorkflowRun = run
			}
			reports, err := Observe(t.Context(), f.client, f.opts)
			if err != nil {
				t.Fatal(err)
			}
			if want == "" && len(reports) != 0 || want != "" && (len(reports) != 1 || reports[0].Disposition != want) {
				t.Fatalf("observation = %+v, want %q", reports, want)
			}
			if name == "test merge completion" && reports[0].CI.TestMergeSHA != testMerge {
				t.Fatal("observation did not verify the test merge SHA", reports[0].CI)
			}
			f.mu.Lock()
			defer f.mu.Unlock()
			if want == "" && f.checkReads != 0 || want != "published" && (f.posted != 0 || f.patched != 0) {
				t.Fatal("observer queried or modified a pull request without current authorization")
			}
		})
	}
}

func TestCommentEscapesUntrustedCheckNames(t *testing.T) {
	snapshot := Snapshot{HeadSHA: testHead, Status: "failed", Checks: []Check{{Name: "[label](https://example.com)\n@everyone | <!-- fake -->",
		SHA: testHead, Status: "failed", URL: "https://github.com/outside/repo/actions/runs/123"}}}
	body := commentBody(snapshot, "org/repo", "<!-- qip-agent-ci:v1 100 44 -->")
	for _, unexpected := range []string{"<-- fake", "<!-- fake", "[label]", "@everyone", "https://github.com/outside"} {
		if strings.Contains(body, unexpected) {
			t.Fatal("untrusted check data rendered literal markdown", body)
		}
	}
}
