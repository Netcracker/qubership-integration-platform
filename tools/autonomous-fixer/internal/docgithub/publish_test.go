package docgithub

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

type publicationAPI struct {
	disableDocs                                      bool
	defaultBranch                                    string
	markDoneOnClose                                  bool
	changeHeadDuringFiles                            bool
	headComparison                                   string
	prFiles                                          []map[string]string
	viewerLogin                                      string
	orgBotLogin                                      string
	dropComment                                      bool
	ignoreCommentPatch                               bool
	plan                                             Plan
	state, project, head, branch                     string
	mergeSHA, mergeHead                              string
	implementationSHA                                string
	comments                                         []issueComment
	pr                                               *pullRequest
	commentCreates, prCreates, closeCalls, doneCalls int
	uncertainComment, uncertainPR, foreignComment    bool
	closeFailure                                     int
	projectFailure                                   string
	failFirstProjectReadAfterUpdate                  bool
	branchCreates, branchFailure                     int
	hidePR                                           bool
	publicationWrite                                 func(Publication)
}

func apiFixture(t *testing.T, p Plan) (*Client, *publicationAPI) {
	t.Helper()
	a := &publicationAPI{viewerLogin: "agent-bot", orgBotLogin: "agent-bot", headComparison: "ahead", prFiles: []map[string]string{{"filename": "help/docs/readme.md"}}, plan: p, state: "closed", project: "REVIEW", head: p.BaseSHA}
	return projectClientFixture(t, p.Repository, a.serve), a
}

func projectClientFixture(t *testing.T, repository string, serve http.HandlerFunc) *Client {
	t.Helper()
	const repositoryToken, projectToken = "repository-token", "project-token"
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		want := repositoryToken
		if r.URL.Path == "/graphql" {
			body, err := io.ReadAll(r.Body)
			if err != nil {
				t.Errorf("read GraphQL request: %v", err)
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			r.Body = io.NopCloser(bytes.NewReader(body))
			if bytes.Contains(body, []byte("projectV2(number:")) || bytes.Contains(body, []byte("updateProjectV2ItemFieldValue")) {
				want = projectToken
			}
		}
		if r.Header.Get("Authorization") != "Bearer "+want {
			t.Errorf("unexpected credentials for %s %s", r.Method, r.URL.Path)
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		serve(w, r)
	}))
	t.Cleanup(server.Close)
	client, e := NewClient(repository, repositoryToken, ClientOptions{UseOrgBot: true, ProjectToken: projectToken, HTTPClient: server.Client(), BaseURL: server.URL})
	if e != nil {
		t.Fatal(e)
	}
	return client
}
func (a *publicationAPI) serve(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	write := func(v any) { _ = json.NewEncoder(w).Encode(v) }
	fail := func() { w.WriteHeader(http.StatusNotFound); write(map[string]string{"message": "not found"}) }
	recordPublication := func(body string) {
		if a.publicationWrite != nil {
			state, err := decodePublicationState(body)
			if err == nil {
				a.publicationWrite(state)
			}
		}
	}
	if r.URL.Path == "/graphql" {
		var request struct {
			Query     string         `json:"query"`
			Variables map[string]any `json:"variables"`
		}
		_ = json.NewDecoder(r.Body).Decode(&request)
		switch {
		case strings.Contains(request.Query, "viewer"):
			write(map[string]any{"data": map[string]any{"viewer": map[string]any{"databaseId": 99, "login": a.viewerLogin}}})
		case strings.Contains(request.Query, "updateProjectV2ItemFieldValue"):
			if request.Variables["option"] != "DONE" || request.Variables["item"] != "ITEM" {
				w.WriteHeader(400)
				write(map[string]string{})
				return
			}
			a.doneCalls++
			if a.projectFailure != "" {
				fmt.Fprint(w, a.projectFailure)
				return
			}
			a.project = "DONE"
			write(map[string]any{"data": map[string]any{"updateProjectV2ItemFieldValue": map[string]any{"projectV2Item": map[string]string{"id": "ITEM"}}}})
		case strings.Contains(request.Query, "projectV2(number"):
			if a.failFirstProjectReadAfterUpdate && a.doneCalls > 0 {
				a.failFirstProjectReadAfterUpdate = false
				w.WriteHeader(http.StatusServiceUnavailable)
				write(map[string]string{})
				return
			}
			write(map[string]any{"data": map[string]any{"repository": map[string]any{"owner": map[string]any{"projectV2": map[string]any{"id": "PROJECT", "fields": map[string]any{"pageInfo": map[string]bool{"hasNextPage": false}, "nodes": []any{map[string]any{"id": "FIELD", "name": "Status", "options": []any{map[string]string{"id": "REVIEW", "name": "In doc review"}, map[string]string{"id": "DONE", "name": "Done"}}}}}}}}, "node": map[string]any{"projectItems": map[string]any{"pageInfo": map[string]bool{"hasNextPage": false}, "nodes": []any{map[string]any{"id": "ITEM", "project": map[string]string{"id": "PROJECT"}, "fieldValueByName": map[string]any{"optionId": a.project, "field": map[string]string{"id": "FIELD"}}}}}}}})
		case strings.Contains(request.Query, "pullRequest(number:"):
			if a.pr == nil || request.Variables["number"] != float64(a.pr.Number) || request.Variables["owner"] != "org" || request.Variables["repo"] != "repo" {
				fail()
				return
			}
			head := a.pr.Head.SHA
			if a.mergeHead != "" {
				head = a.mergeHead
			}
			pr := map[string]any{"number": a.pr.Number, "merged": a.pr.Merged, "headRefOid": head, "baseRefName": a.pr.Base.Ref, "mergeCommit": map[string]string{"oid": a.mergeSHA}}
			write(map[string]any{"data": map[string]any{"repository": map[string]any{"pullRequest": pr}}})
		case strings.Contains(request.Query, "timelineItems"):
			mergeSHA := a.implementationSHA
			if mergeSHA == "" {
				mergeSHA = a.plan.BaseSHA
			}
			pr := map[string]any{"number": 2, "merged": true, "baseRefName": "main", "repository": map[string]string{"nameWithOwner": "org/repo"}, "mergeCommit": map[string]string{"oid": mergeSHA}, "closingIssuesReferences": map[string]any{"nodes": []any{map[string]int{"number": 1}}, "pageInfo": map[string]bool{"hasNextPage": false}}}
			write(map[string]any{"data": map[string]any{"repository": map[string]any{"issue": map[string]any{"timelineItems": map[string]any{"pageInfo": map[string]bool{"hasNextPage": false}, "nodes": []any{map[string]any{"closer": pr}}}}}}})
		default:
			fail()
		}
		return
	}
	path := strings.TrimPrefix(r.URL.Path, "/repos/org/repo")
	switch {
	case path == "":
		branch := a.defaultBranch
		if branch == "" {
			branch = "main"
		}
		write(map[string]string{"full_name": a.plan.Repository, "default_branch": branch})
	case path == "/actions/variables/AGENT_DOCS_ENABLED":
		value := "true"
		if a.disableDocs {
			value = "false"
		}
		write(map[string]string{"value": value})
	case path == "/actions/variables/AGENT_DOCS_PUBLISHER_LOGIN":
		write(map[string]string{"value": "agent-bot"})
	case path == "/actions/variables/AGENT_ORG_BOT_LOGIN":
		write(map[string]string{"value": a.orgBotLogin})
	case path == "/actions/variables/AGENT_ALLOWED_ACTORS":
		write(map[string]string{"value": "alice"})
	case path == "/issues/1" && r.Method == "GET":
		write(map[string]any{"number": 1, "node_id": "ISSUE", "title": a.plan.IssueTitle, "body": "Issue body", "state": a.state})
	case path == "/issues/1" && r.Method == "PATCH":
		a.closeCalls++
		if a.closeFailure != 0 {
			w.WriteHeader(a.closeFailure)
			write(map[string]string{})
			return
		}
		a.state = "closed"
		if a.markDoneOnClose {
			a.project = "DONE"
		}
		write(map[string]string{"state": "closed"})
	case path == "/issues/1/comments" && r.Method == "GET":
		if a.comments == nil {
			write([]any{})
		} else {
			write(a.comments)
		}
	case path == "/issues/1/comments" && r.Method == "POST":
		var body struct {
			Body string `json:"body"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		c := issueComment{ID: int64(5 + len(a.comments)), Body: body.Body}
		c.User.ID = 99
		c.User.Login = a.viewerLogin
		if a.foreignComment {
			c.User.ID = 12
		}
		a.commentCreates++
		if a.dropComment {
			w.WriteHeader(500)
			write(map[string]string{})
			return
		}
		a.comments = append(a.comments, c)
		recordPublication(c.Body)
		if a.uncertainComment {
			a.uncertainComment = false
			w.WriteHeader(500)
			write(map[string]string{})
			return
		}
		write(c)
	case strings.HasPrefix(path, "/issues/comments/") && r.Method == "PATCH":
		var body struct {
			Body string `json:"body"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		for i := range a.comments {
			if strconv.FormatInt(a.comments[i].ID, 10) != strings.TrimPrefix(path, "/issues/comments/") {
				continue
			}
			if !a.ignoreCommentPatch {
				a.comments[i].Body = body.Body
				recordPublication(body.Body)
			}
			write(a.comments[i])
			return
		}
		fail()
	case path == "/git/ref/heads/main":
		write(map[string]any{"object": map[string]string{"sha": a.head}})
	case strings.HasPrefix(path, "/git/ref/heads/agent/docs/"):
		if a.branch == "" {
			fail()
		} else {
			write(map[string]any{"object": map[string]string{"sha": a.branch}})
		}
	case path == "/git/refs" && r.Method == "POST":
		a.branchCreates++
		if a.branchFailure != 0 {
			w.WriteHeader(a.branchFailure)
			write(map[string]string{})
			return
		}
		var body map[string]string
		_ = json.NewDecoder(r.Body).Decode(&body)
		a.branch = body["sha"]
		write(map[string]any{"object": map[string]string{"sha": a.branch}})
	case strings.HasPrefix(path, "/git/commits/"):
		write(map[string]any{"tree": map[string]string{"sha": strings.Repeat("a", 40)}})
	case path == "/git/blobs":
		write(map[string]string{"sha": strings.Repeat("b", 40)})
	case path == "/git/trees":
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		if body["base_tree"] != strings.Repeat("a", 40) {
			w.WriteHeader(400)
			write(map[string]string{})
			return
		}
		write(map[string]string{"sha": strings.Repeat("c", 40)})
	case path == "/git/commits" && r.Method == "POST":
		write(map[string]string{"sha": strings.Repeat("d", 40)})
	case path == "/pulls/3/files":
		write(a.prFiles)
		if a.changeHeadDuringFiles {
			a.pr.Head.SHA = strings.Repeat("1", 40)
		}
	case path == "/pulls/2/files":
		write([]any{map[string]string{"filename": "engine/file.go"}})
	case path == "/pulls" && r.Method == "GET":
		if a.pr == nil || a.hidePR {
			write([]any{})
		} else {
			write([]pullRequest{*a.pr})
		}
	case path == "/pulls" && r.Method == "POST":
		var body struct {
			Body, Head, Base string
			Draft            bool
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		if !body.Draft || hasClosingKeyword(body.Body) {
			w.WriteHeader(400)
			write(map[string]string{})
			return
		}
		a.pr = &pullRequest{ChangedFiles: 1, Number: 3, State: "open", Body: body.Body, HTMLURL: "https://github.com/org/repo/pull/3"}
		a.pr.User.Login = a.viewerLogin
		a.pr.Head.SHA = a.branch
		a.pr.Head.Ref = body.Head
		a.pr.Head.Repo.FullName = "org/repo"
		a.pr.Base.Ref = body.Base
		a.pr.Base.SHA = a.plan.BaseSHA
		a.pr.Base.Repo.FullName = "org/repo"
		a.prCreates++
		if a.uncertainPR {
			a.uncertainPR = false
			w.WriteHeader(500)
			write(map[string]string{})
			return
		}
		write(a.pr)
	case path == "/pulls/3":
		write(a.pr)
	case strings.HasPrefix(path, "/compare/"+strings.Repeat("d", 40)+"..."), strings.HasPrefix(path, "/compare/"+a.plan.BaseSHA+"..."):
		write(map[string]string{"status": a.headComparison})
	case strings.HasPrefix(path, "/compare/"):
		write(map[string]string{"status": "ahead"})
	default:
		fmt.Println("unhandled fake request", r.Method, path)
		fail()
	}
}
func TestPublishNoChangeIsIdempotentForClosedIssue(t *testing.T) {
	plan, run, _ := publicationFixture(t, false)
	c, a := apiFixture(t, plan)
	a.uncertainComment = true
	for i := 0; i < 2; i++ {
		result, e := Publish(context.Background(), c, plan, run, PublishOptions{Actor: "alice", Apply: true})
		if e != nil || result.Status != "completed" {
			t.Fatal(result, e)
		}
	}
	if a.commentCreates != 1 || a.prCreates != 0 || a.closeCalls != 0 || a.doneCalls != 1 || a.project != "DONE" {
		t.Fatalf("%+v", a)
	}
	const signature = "<sub>🤖 typesafe/jev-1.14 out=30 usd=0.0042</sub>"
	body := a.comments[0].Body
	if !strings.HasSuffix(body, "\n\n"+signature) || strings.Count(body, "<sub>🤖") != 1 {
		t.Fatalf("Jev-only comment does not carry exactly one last-line signature: %s", body)
	}
	result, e := Reconcile(context.Background(), c, plan, PublishOptions{Actor: "alice", Apply: true})
	if e != nil || result.Status != "completed" || a.comments[0].Body != body {
		t.Fatalf("reconciliation changed the signed result: %s, %v", result.Status, e)
	}
}
func TestPublishUpdatesScheduledClaim(t *testing.T) {
	for _, needed := range []bool{false, true} {
		plan, run, source := publicationFixture(t, needed)
		plan.RequestID = "42:2"
		plan.ID = ""
		plan.ID = Digest(plan)
		c, a := apiFixture(t, plan)
		claim := issueComment{ID: 5, User: fixer.GitHubUser{ID: 99}, Body: scheduleReceipt(scheduleBasis(plan), ScheduledAttempt{42, 2}) + "\nChecking documentation."}
		a.comments = []issueComment{claim}
		options := PublishOptions{Actor: "alice", Apply: true, SourcePath: source}
		for i := 0; i < 2; i++ {
			result, err := Publish(context.Background(), c, plan, run, options)
			if err != nil || result.CommentID != claim.ID {
				t.Fatalf("needed=%t: result=%+v, err=%v", needed, result, err)
			}
		}
		if len(a.comments) != 1 || a.commentCreates != 0 || !strings.Contains(a.comments[0].Body, "[View workflow run](https://github.com/org/repo/actions/runs/42/attempts/2)") {
			t.Fatal("expected the result and run link on the original comment")
		}
	}
}

func TestPublishOpenIssueClosesAfterComment(t *testing.T) {
	plan, run, _ := publicationFixture(t, false)
	plan.IssueState = "open"
	plan.ID = ""
	plan.ID = Digest(plan)
	c, a := apiFixture(t, plan)
	a.state = "open"
	result, e := Publish(context.Background(), c, plan, run, PublishOptions{Actor: "alice", Apply: true})
	if e != nil || result.Status != "completed" || a.closeCalls != 1 || a.commentCreates != 1 {
		t.Fatal(result, e, a.closeCalls)
	}
	a.state = "open"
	if _, e = Publish(context.Background(), c, plan, run, PublishOptions{Actor: "alice", Apply: true}); e == nil || a.closeCalls != 1 {
		t.Fatal("reopened issue was closed again", e)
	}
}
func TestProjectAutomationCompletesAfterIssueClose(t *testing.T) {
	plan, run, _ := publicationFixture(t, false)
	plan.IssueState = "open"
	plan.ID = ""
	plan.ID = Digest(plan)
	c, a := apiFixture(t, plan)
	a.state, a.markDoneOnClose = "open", true
	for i := 0; i < 2; i++ {
		result, err := Publish(context.Background(), c, plan, run, PublishOptions{Actor: "alice", Apply: true})
		if err != nil || result.Status != "completed" || !result.CloseAttempted || result.ProjectDoneAttempted {
			t.Fatalf("publication %d: %+v, %v", i, result, err)
		}
	}
	if a.closeCalls != 1 || a.doneCalls != 0 || a.commentCreates != 1 {
		t.Fatalf("close=%d project updates=%d comments=%d", a.closeCalls, a.doneCalls, a.commentCreates)
	}
}

const projectPermissionDenied = `{"data":{"updateProjectV2ItemFieldValue":null},"errors":[{"type":"FORBIDDEN","path":["updateProjectV2ItemFieldValue"],"message":"Resource not accessible by integration"}]}`

func TestReconcileRetriesRejectedCompletion(t *testing.T) {
	for _, step := range []string{"issue close", "project completion"} {
		t.Run(step, func(t *testing.T) {
			ctx := context.Background()
			plan, run, _ := publicationFixture(t, false)
			plan.IssueState = "open"
			plan.ID = ""
			plan.ID = Digest(plan)
			c, api := apiFixture(t, plan)
			api.state = "open"
			wantCloses, wantProjectUpdates := 1, 2
			if step == "issue close" {
				api.closeFailure = http.StatusForbidden
				wantCloses, wantProjectUpdates = 2, 1
			} else {
				api.projectFailure = projectPermissionDenied
			}
			options := PublishOptions{Actor: "alice", Apply: true}
			if _, err := Publish(ctx, c, plan, run, options); err == nil {
				t.Fatal("rejected write reported success")
			}
			api.closeFailure, api.projectFailure = 0, ""
			result, err := Reconcile(ctx, c, plan, options)
			if err != nil || result.Status != "completed" {
				t.Fatalf("reconcile after rejection: status=%s, error=%v", result.Status, err)
			}
			if api.closeCalls != wantCloses || api.doneCalls != wantProjectUpdates || api.commentCreates != 1 {
				t.Fatalf("close=%d, project updates=%d, comments=%d", api.closeCalls, api.doneCalls, api.commentCreates)
			}
		})
	}
}

func TestReconcileAfterFailedProjectReadback(t *testing.T) {
	for _, test := range []struct {
		name, response, wantStatus string
		wantUpdates                int
	}{
		{"rejection", projectPermissionDenied, "completed", 2},
		{"ambiguous response", `{`, "completing", 1},
	} {
		t.Run(test.name, func(t *testing.T) {
			ctx := context.Background()
			plan, run, _ := publicationFixture(t, false)
			c, api := apiFixture(t, plan)
			api.projectFailure = test.response
			api.failFirstProjectReadAfterUpdate = true
			options := PublishOptions{Actor: "alice", Apply: true}
			if _, err := Publish(ctx, c, plan, run, options); err == nil || api.failFirstProjectReadAfterUpdate {
				t.Fatalf("expected the first project read after the write to fail: %v", err)
			}
			api.projectFailure = ""
			api.head = strings.Repeat("e", 40)
			result, err := Reconcile(ctx, c, plan, options)
			if result.Status != test.wantStatus || (err == nil) != (test.wantStatus == "completed") || api.doneCalls != test.wantUpdates {
				t.Fatalf("status=%s, project updates=%d, error=%v", result.Status, api.doneCalls, err)
			}
		})
	}
}

func TestReconcileBlocksAmbiguousCompletion(t *testing.T) {
	for _, test := range []struct {
		name           string
		closeFailure   int
		projectFailure string
		wantDoneCalls  int
	}{
		{name: "server error", closeFailure: http.StatusInternalServerError},
		{name: "malformed response", projectFailure: `{`, wantDoneCalls: 1},
		{
			name:           "permission error in mutation payload",
			projectFailure: `{"data":{"updateProjectV2ItemFieldValue":null},"errors":[{"type":"FORBIDDEN","path":["updateProjectV2ItemFieldValue","projectV2Item"],"message":"Resource not accessible by integration"}]}`,
			wantDoneCalls:  1,
		},
	} {
		t.Run(test.name, func(t *testing.T) {
			ctx := context.Background()
			plan, run, _ := publicationFixture(t, false)
			plan.IssueState = "open"
			plan.ID = ""
			plan.ID = Digest(plan)
			c, api := apiFixture(t, plan)
			api.state = "open"
			api.closeFailure, api.projectFailure = test.closeFailure, test.projectFailure
			options := PublishOptions{Actor: "alice", Apply: true}
			if _, err := Publish(ctx, c, plan, run, options); err == nil {
				t.Fatal("ambiguous write reported success")
			}
			api.closeFailure, api.projectFailure = 0, ""
			if _, err := Reconcile(ctx, c, plan, options); err == nil {
				t.Fatal("reconciliation accepted an ambiguous write without readback proof")
			}
			if api.closeCalls != 1 || api.doneCalls != test.wantDoneCalls {
				t.Fatalf("close=%d, project updates=%d", api.closeCalls, api.doneCalls)
			}
		})
	}
}

func TestReconcilePreservesManualReopenAfterRejectedProjectCompletion(t *testing.T) {
	ctx := context.Background()
	plan, run, _ := publicationFixture(t, false)
	plan.IssueState = "open"
	plan.ID = ""
	plan.ID = Digest(plan)
	c, api := apiFixture(t, plan)
	api.state, api.projectFailure = "open", projectPermissionDenied
	options := PublishOptions{Actor: "alice", Apply: true}
	if _, err := Publish(ctx, c, plan, run, options); err == nil || api.state != "closed" {
		t.Fatalf("expected issue to close before the rejected project update: state=%s, error=%v", api.state, err)
	}
	api.state, api.projectFailure, api.head = "open", "", strings.Repeat("e", 40)
	if _, err := Reconcile(ctx, c, plan, options); err == nil || !strings.Contains(err.Error(), "reopened") {
		t.Fatal("reconciliation accepted a manually reopened issue")
	}
	if api.closeCalls != 1 || api.doneCalls != 1 || api.state != "open" {
		t.Fatalf("close=%d, project updates=%d, state=%s", api.closeCalls, api.doneCalls, api.state)
	}
}

func TestReconcileNoChangeRequiresCurrentMainBeforeClose(t *testing.T) {
	ctx := context.Background()
	plan, run, _ := publicationFixture(t, false)
	plan.IssueState = "open"
	plan.ID = ""
	plan.ID = Digest(plan)
	c, api := apiFixture(t, plan)
	api.state, api.closeFailure = "open", http.StatusForbidden
	options := PublishOptions{Actor: "alice", Apply: true}
	if _, err := Publish(ctx, c, plan, run, options); err == nil || api.state != "open" {
		t.Fatalf("expected GitHub to reject the close: state=%s, error=%v", api.state, err)
	}
	api.closeFailure, api.head = 0, strings.Repeat("e", 40)
	if result, err := Reconcile(ctx, c, plan, options); err != nil || result.Status != "superseded" || !strings.Contains(api.comments[0].Body, "Start a fresh assessment") {
		t.Fatalf("expected a fresh documentation assessment: status=%s, error=%v", result.Status, err)
	}
	if api.state != "open" || api.closeCalls != 1 || api.doneCalls != 0 {
		t.Fatalf("state=%s, close=%d, project updates=%d", api.state, api.closeCalls, api.doneCalls)
	}
}

func TestPublishStopsWhenMainAdvancesAfterWritingIntent(t *testing.T) {
	for _, test := range []struct {
		name, decision, issueState string
		at                         func(Publication) bool
	}{
		{"publishing", "no_change", "closed", func(state Publication) bool { return state.Status == "publishing" }},
		{"issue close", "no_change", "open", func(state Publication) bool { return state.CloseAttempted }},
		{"project completion", "no_change", "closed", func(state Publication) bool { return state.ProjectDoneAttempted }},
		{"branch creation", "needed", "closed", func(state Publication) bool { return state.BranchWrite == branchAttempted }},
	} {
		t.Run(test.name, func(t *testing.T) {
			plan, run, _ := publicationFixture(t, test.decision == "needed")
			plan.IssueState, plan.ID = test.issueState, ""
			plan.ID = Digest(plan)
			c, api := apiFixture(t, plan)
			api.state = test.issueState
			api.publicationWrite = func(state Publication) {
				if test.at(state) {
					api.head = strings.Repeat("e", 40)
				}
			}
			options := PublishOptions{Actor: "alice", Apply: true}
			for i := 0; i < 2; i++ {
				result, err := Publish(t.Context(), c, plan, run, options)
				if !errors.Is(err, errStaleBase) || result.Status != "superseded" || result.CommentID != 5 {
					t.Fatalf("publication %d: state=%+v, err=%v", i, result, err)
				}
			}
			if api.branchCreates+api.prCreates+api.closeCalls+api.doneCalls != 0 || api.commentCreates != 1 {
				t.Fatalf("unexpected writes: %+v", api)
			}
		})
	}
}

func TestPublishFreshAssessmentReplacesStalePublication(t *testing.T) {
	plan, run, _ := publicationFixture(t, false)
	c, api := apiFixture(t, plan)
	oldPlan := plan
	oldPlan.BaseSHA, oldPlan.ID = strings.Repeat("e", 40), ""
	oldPlan.ID = Digest(oldPlan)
	old := &publisher{client: c, plan: oldPlan}
	if err := old.saveState(t.Context(), Publication{Decision: "no_change", Status: "publishing", InitialIssueState: "closed"}); err != nil {
		t.Fatal(err)
	}
	options := PublishOptions{Actor: "alice", Apply: true}
	result, err := Publish(t.Context(), c, plan, run, options)
	if err != nil || result.Status != "completed" || api.commentCreates != 2 || api.doneCalls != 1 {
		t.Fatalf("fresh publication: state=%+v, writes=%d, err=%v", result, api.doneCalls, err)
	}
	oldState, err := decodePublicationState(api.comments[0].Body)
	if err != nil || oldState.Status != "superseded" || oldState.PlanID != oldPlan.ID {
		t.Fatalf("old publication: state=%+v, err=%v", oldState, err)
	}
	if recovered, err := RecoverPlan(t.Context(), c, plan.IssueNumber); err != nil || recovered.ID != plan.ID {
		t.Fatalf("recovered plan=%+v, err=%v", recovered, err)
	}
}

func TestScheduleStopSupersedesStaleStateWithFailureReason(t *testing.T) {
	plan, _, _ := publicationFixture(t, false)
	plan.IssueState, plan.RequestID, plan.ID = "open", "500:1", ""
	plan.ID = Digest(plan)
	c, api := apiFixture(t, plan)
	api.state, api.head = "open", strings.Repeat("e", 40)
	p := &publisher{client: c, plan: plan}
	if err := p.saveState(t.Context(), Publication{Decision: "no_change", Status: "completing", InitialIssueState: "open"}); err != nil {
		t.Fatal(err)
	}
	stop := ScheduleStop{Actor: "alice", Basis: scheduleBasis(plan), Current: ScheduledAttempt{RunID: 500, Attempt: 1}, Reason: "publish-stopped", PublicationFailure: PublicationFailedIssueClose}
	for _, wantUpdate := range []bool{true, false} {
		if updated, err := RecordScheduleStop(t.Context(), c, plan, stop); err != nil || updated != wantUpdate {
			t.Fatalf("record stop: updated=%v, want=%v, err=%v", updated, wantUpdate, err)
		}
	}
	state, err := decodePublicationState(api.comments[0].Body)
	if err != nil || state.Status != "superseded" || !strings.Contains(state.IssueComment, "stopped while closing the issue") || !strings.Contains(state.IssueComment, "Start a fresh assessment") {
		t.Fatalf("stale publication notice: state=%+v, err=%v", state, err)
	}
}

func TestStalePRCreationRecoversByReadback(t *testing.T) {
	plan, run, _ := publicationFixture(t, true)
	plan.RequestID, plan.ID = "500:1", ""
	plan.ID = Digest(plan)
	c, api := apiFixture(t, plan)
	api.hidePR, api.uncertainPR = true, true
	options := PublishOptions{Actor: "alice", Apply: true}
	if result, err := Publish(t.Context(), c, plan, run, options); err == nil || !result.PRAttempted || result.PRNumber != 0 || api.prCreates != 1 {
		t.Fatalf("initial publication: state=%+v, PR writes=%d, err=%v", result, api.prCreates, err)
	}
	if err := os.Remove(filepath.Join(run, "publication-journal.jsonl")); err != nil {
		t.Fatal(err)
	}
	if _, err := Publish(t.Context(), c, plan, run, options); err == nil || api.prCreates != 1 || api.branchCreates != 1 {
		t.Fatalf("unresolved publication retried without its local journal: PR writes=%d, branch writes=%d, err=%v", api.prCreates, api.branchCreates, err)
	}
	api.head = strings.Repeat("e", 40)
	if result, err := Reconcile(t.Context(), c, plan, options); !errors.Is(err, errPublicationEffects) || result.Status != "publishing" {
		t.Fatalf("reconciliation of unresolved PR: state=%+v, err=%v", result, err)
	}
	api.hidePR = false
	stop := ScheduleStop{Actor: "alice", Basis: scheduleBasis(plan), Current: ScheduledAttempt{RunID: 500, Attempt: 1}, Reason: "publish-stopped", PublicationFailure: PublicationFailedDraftPR}
	if updated, err := RecordScheduleStop(t.Context(), c, plan, stop); err != nil || !updated || !strings.Contains(api.comments[0].Body, "Pull request #3") || !strings.Contains(api.comments[0].Body, "`reconcile`") || strings.Contains(api.comments[0].Body, "Re-run failed jobs") {
		t.Fatalf("stale publication notice: updated=%v, err=%v, body=%s", updated, err, api.comments[0].Body)
	}
	result, err := Reconcile(t.Context(), c, plan, options)
	if err != nil || result.Status != "awaiting-merge" || result.PRNumber != 3 || api.prCreates != 1 || api.branchCreates != 1 || api.doneCalls+api.closeCalls != 0 {
		t.Fatalf("recovered publication: state=%+v, writes=%d/%d, err=%v", result, api.prCreates, api.branchCreates, err)
	}
}

func TestStaleBranchCreationRequiresReadback(t *testing.T) {
	plan, run, _ := publicationFixture(t, true)
	c, api := apiFixture(t, plan)
	api.branchFailure = http.StatusInternalServerError
	options := PublishOptions{Actor: "alice", Apply: true}
	if result, err := Publish(t.Context(), c, plan, run, options); err == nil || result.BranchWrite != branchAttempted {
		t.Fatalf("initial publication: state=%+v, err=%v", result, err)
	}
	if err := os.Remove(filepath.Join(run, "publication-journal.jsonl")); err != nil {
		t.Fatal(err)
	}
	api.branchFailure = 0
	if _, err := Publish(t.Context(), c, plan, run, options); err == nil || api.branchCreates != 1 {
		t.Fatalf("unresolved branch creation retried without its local journal: writes=%d, err=%v", api.branchCreates, err)
	}
	api.head = strings.Repeat("e", 40)
	if result, err := Reconcile(t.Context(), c, plan, options); !errors.Is(err, errPublicationEffects) || result.Status != "publishing" || api.branchCreates != 1 || api.prCreates != 0 {
		t.Fatalf("stale publication discarded unresolved branch creation: state=%+v, err=%v", result, err)
	}
}

func TestStaleBranchCanBeRetiredAfterObservedRemoval(t *testing.T) {
	for _, branchWrite := range []string{"", branchObserved} {
		t.Run(branchWrite, func(t *testing.T) {
			plan, _, _ := publicationFixture(t, true)
			c, api := apiFixture(t, plan)
			p := &publisher{client: c, plan: plan}
			api.branch = strings.Repeat("d", 40)
			api.head = strings.Repeat("e", 40)
			state := Publication{Decision: "needed", Status: "publishing", InitialIssueState: "closed", CandidateDigest: "candidate", Branch: p.documentationBranch(), CommitSHA: api.branch, BranchWrite: branchWrite}
			if err := p.saveState(t.Context(), state); err != nil {
				t.Fatal(err)
			}
			options := PublishOptions{Actor: "alice", Apply: true}
			if result, err := Reconcile(t.Context(), c, plan, options); !errors.Is(err, errPublicationEffects) || result.Status != "publishing" || !strings.Contains(err.Error(), p.documentationBranch()) {
				t.Fatalf("expected the branch to block retirement: state=%+v, err=%v", result, err)
			}
			api.branch = ""
			if result, err := Reconcile(t.Context(), c, plan, options); err != nil || result.Status != "superseded" || api.branchCreates+api.prCreates+api.closeCalls+api.doneCalls != 0 {
				t.Fatalf("expected to retire the removed branch: state=%+v, err=%v", result, err)
			}
		})
	}
}

func TestReconcileNoChangeAfterMainAdvances(t *testing.T) {
	for _, test := range []struct {
		name      string
		change    func(*publicationAPI)
		wantError string
	}{
		{name: "unrelated commit"},
		{name: "rewritten main", change: func(a *publicationAPI) { a.headComparison = "diverged" }, wantError: "is not on the default branch"},
		{name: "edited requirements", change: func(a *publicationAPI) { a.plan.IssueTitle += " (updated)" }, wantError: "issue requirements changed"},
		{name: "changed implementation", change: func(a *publicationAPI) { a.implementationSHA = strings.Repeat("f", 40) }, wantError: "implementing pull requests changed"},
		{name: "moved project item", change: func(a *publicationAPI) { a.project = "OTHER" }, wantError: "issue moved out of documentation review"},
	} {
		t.Run(test.name, func(t *testing.T) {
			ctx := context.Background()
			plan, run, _ := publicationFixture(t, false)
			plan.IssueState = "open"
			plan.ID = ""
			plan.ID = Digest(plan)
			c, api := apiFixture(t, plan)
			api.state, api.projectFailure = "open", projectPermissionDenied
			options := PublishOptions{Actor: "alice", Apply: true}
			if _, err := Publish(ctx, c, plan, run, options); err == nil || api.state != "closed" {
				t.Fatalf("expected the issue to close before the rejected project update: state=%s, error=%v", api.state, err)
			}
			api.head, api.projectFailure = strings.Repeat("e", 40), ""
			if test.change != nil {
				test.change(api)
			}
			result, err := Reconcile(ctx, c, plan, options)
			if test.wantError != "" {
				if err == nil || !strings.Contains(err.Error(), test.wantError) || api.doneCalls != 1 {
					t.Fatalf("expected %q without a project write: %+v, error=%v, writes=%d", test.wantError, result, err, api.doneCalls)
				}
				return
			}
			if err != nil || result.Status != "completed" || api.closeCalls != 1 || api.doneCalls != 2 || api.commentCreates != 1 || api.project != "DONE" {
				t.Fatalf("reconciliation did not complete the existing publication: %+v, error=%v, close=%d, project updates=%d, comments=%d", result, err, api.closeCalls, api.doneCalls, api.commentCreates)
			}
			if result, err = Publish(ctx, c, plan, run, options); err != nil || result.Status != "completed" || api.closeCalls != 1 || api.doneCalls != 2 || api.commentCreates != 1 {
				t.Fatalf("publication retry changed the completed task: %+v, error=%v", result, err)
			}
		})
	}
}

func TestPublicationStopsForStaleMovedOrUnauthorizedTask(t *testing.T) {
	for _, change := range []string{"main", "project", "actor"} {
		t.Run(change, func(t *testing.T) {
			plan, run, _ := publicationFixture(t, false)
			c, a := apiFixture(t, plan)
			opts := PublishOptions{Actor: "alice", Apply: true}
			switch change {
			case "main":
				a.head = strings.Repeat("e", 40)
			case "project":
				a.project = "OTHER"
			case "actor":
				opts.Actor = "outsider"
			}
			if _, e := Publish(context.Background(), c, plan, run, opts); e == nil {
				t.Fatal("invalid task published")
			}
			if a.commentCreates+a.prCreates+a.doneCalls != 0 {
				t.Fatal("created effects")
			}
		})
	}
}
func TestDocsPRWaitsForMergeAndRecoversUncertainCreate(t *testing.T) {
	plan, run, _ := publicationFixture(t, true)
	c, a := apiFixture(t, plan)
	a.uncertainPR = true
	for i := 0; i < 2; i++ {
		result, e := Publish(context.Background(), c, plan, run, PublishOptions{Actor: "alice", Apply: true})
		if e != nil || result.Status != "awaiting-merge" {
			t.Fatal(result, e)
		}
	}
	if a.prCreates != 1 || a.doneCalls != 0 || a.closeCalls != 0 {
		t.Fatal("PR was duplicated or task completed before merge")
	}
	prBody := a.pr.Body
	const signature = "<sub>🤖 gpt-6.1-terra high out=60</sub>"
	if !strings.HasSuffix(prBody, marker(plan)+"\n\n"+signature) || strings.Count(prBody, "<sub>🤖") != 1 {
		t.Fatalf("missing author signature on the final PR line: %s", prBody)
	}
	a.pr.Merged = true
	a.pr.State = "closed"
	a.mergeSHA = strings.Repeat("f", 40)
	a.head = strings.Repeat("9", 40)
	for i := 0; i < 2; i++ {
		result, e := Reconcile(context.Background(), c, plan, PublishOptions{Actor: "alice", Apply: true})
		if e != nil || result.Status != "completed" {
			t.Fatal(result, e)
		}
	}
	if a.doneCalls != 1 || a.pr.Body != prBody || strings.Contains(a.comments[0].Body, "<sub>🤖") {
		t.Fatal("merge completion repeated the signature or updated the PR")
	}
}

func TestSaveStateVerifiesPRURL(t *testing.T) {
	for _, tt := range []struct {
		name        string
		ignorePatch bool
	}{
		{name: "record missing URL"},
		{name: "reject stale readback", ignorePatch: true},
	} {
		t.Run(tt.name, func(t *testing.T) {
			plan, _, _ := publicationFixture(t, true)
			client, api := apiFixture(t, plan)
			p := &publisher{client: client, plan: plan}
			state := Publication{Decision: "needed", Status: "awaiting-merge", PRNumber: 3}
			if err := p.saveState(t.Context(), state); err != nil {
				t.Fatal(err)
			}

			api.ignoreCommentPatch = tt.ignorePatch
			state.PRURL = "https://github.com/org/repo/pull/3"
			err := p.saveState(t.Context(), state)
			if tt.ignorePatch {
				if err == nil || err.Error() != "documentation state readback differs" {
					t.Fatalf("expected a readback mismatch, got %v", err)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if p.state.PRURL != state.PRURL {
				t.Fatalf("publication state has PR URL %q, want %q", p.state.PRURL, state.PRURL)
			}
			if !strings.Contains(api.comments[0].Body, "[Draft documentation PR]("+state.PRURL+")") {
				t.Fatal("publication comment has no draft PR link")
			}
		})
	}
}

func TestReconcileRejectsInconsistentMergeEvidence(t *testing.T) {
	for _, test := range []struct {
		name     string
		mergeSHA string
		headSHA  string
	}{
		{name: "missing merge commit"},
		{name: "invalid merge commit", mergeSHA: "invalid"},
		{name: "different pull request head", mergeSHA: strings.Repeat("f", 40), headSHA: strings.Repeat("c", 40)},
	} {
		t.Run(test.name, func(t *testing.T) {
			plan, run, _ := publicationFixture(t, true)
			c, api := apiFixture(t, plan)
			options := PublishOptions{Actor: "alice", Apply: true}
			if _, err := Publish(context.Background(), c, plan, run, options); err != nil {
				t.Fatal(err)
			}
			api.pr.Merged = true
			api.pr.State = "closed"
			api.mergeSHA = test.mergeSHA
			api.mergeHead = test.headSHA
			if _, err := Reconcile(context.Background(), c, plan, options); err == nil {
				t.Fatal("reconciliation accepted inconsistent merge evidence")
			}
			if api.closeCalls != 0 || api.doneCalls != 0 {
				t.Fatal("inconsistent merge evidence completed the documentation task")
			}
		})
	}
}

func TestDryRunAndClosedUnmergedPR(t *testing.T) {
	plan, run, _ := publicationFixture(t, true)
	c, a := apiFixture(t, plan)
	result, e := Publish(context.Background(), c, plan, run, PublishOptions{})
	if e != nil || result.Applied || a.commentCreates != 0 {
		t.Fatal(result, e)
	}
	if _, e = Publish(context.Background(), c, plan, run, PublishOptions{Actor: "alice", Apply: true}); e != nil {
		t.Fatal(e)
	}
	a.pr.State = "closed"
	result, e = Reconcile(context.Background(), c, plan, PublishOptions{Actor: "alice", Apply: true})
	if e != nil || result.Status != "needs-human" || a.doneCalls != 0 {
		t.Fatal(result, e)
	}
	if !strings.Contains(a.pr.Body, "<sub>🤖 gpt-6.1-terra high") || strings.Contains(a.comments[0].Body, "<sub>🤖") || a.commentCreates != 1 {
		t.Fatal("the closed PR status repeated or replaced the run signature")
	}
}

func TestReconcileEditsTheSignedNoChangeCommentInPlace(t *testing.T) {
	plan, _, _ := publicationFixture(t, false)
	c, api := apiFixture(t, plan)
	ctx := context.Background()
	const signature = "<sub>🤖 typesafe/jev-1.14 out=30 usd=0.0042</sub>"
	p := &publisher{client: c, plan: plan, state: Publication{
		Decision: "no_change", Status: "publishing", CandidateDigest: "candidate", InitialIssueState: "closed",
		IssueComment: "Help already describes the behavior.\n\n<sub>🤖 draft-model in=999</sub>", RunSignature: signature,
	}}
	if e := p.saveState(ctx, p.state); e != nil {
		t.Fatal(e)
	}
	recovered, e := RecoverPlan(ctx, c, plan.IssueNumber)
	if e != nil || recovered.ID != plan.ID {
		t.Fatalf("could not recover the signed plan: %v", e)
	}
	result, e := Reconcile(ctx, c, recovered, PublishOptions{Actor: "alice", Apply: true})
	body := api.comments[0].Body
	if e != nil || result.Status != "completed" || api.commentCreates != 1 || strings.Contains(body, "**publishing**") ||
		!strings.HasSuffix(body, "\n\n"+signature) || strings.Count(body, "<sub>🤖") != 1 {
		t.Fatalf("reconciliation lost or duplicated signed totals: %s, %v", body, e)
	}
}

func TestUnknownCommentCreationDoesNotBlindlyRetry(t *testing.T) {
	plan, run, _ := publicationFixture(t, false)
	c, a := apiFixture(t, plan)
	a.dropComment = true
	for i := 0; i < 2; i++ {
		if _, e := Publish(context.Background(), c, plan, run, PublishOptions{Actor: "alice", Apply: true}); e == nil {
			t.Fatal("unknown creation reported success")
		}
	}
	if a.commentCreates != 1 || a.doneCalls != 0 || a.prCreates != 0 {
		t.Fatal("uncertain creation repeated", a.commentCreates)
	}
}
func TestRecoverPlanAndRejectForeignMarker(t *testing.T) {
	plan, run, _ := publicationFixture(t, false)
	c, a := apiFixture(t, plan)
	if _, e := Publish(context.Background(), c, plan, run, PublishOptions{Actor: "alice", Apply: true}); e != nil {
		t.Fatal(e)
	}
	recovered, e := RecoverPlan(context.Background(), c, 1)
	if e != nil || recovered.ID != plan.ID {
		t.Fatal(recovered, e)
	}
	a.comments[0].User.ID = 12
	if _, e = Publish(context.Background(), c, plan, run, PublishOptions{Actor: "alice", Apply: true}); e == nil {
		t.Fatal("foreign state accepted")
	}
	if _, e = RecoverPlan(context.Background(), c, 1); !errors.Is(e, ErrNoPublication) {
		t.Fatal("foreign state recovered", e)
	}
}

func TestReconcileDoesNotCompleteAnInterruptedPRPublication(t *testing.T) {
	plan, _, _ := publicationFixture(t, true)
	c, a := apiFixture(t, plan)
	p := &publisher{client: c, plan: plan, state: Publication{PlanID: plan.ID, Decision: "needed", Status: "publishing", CandidateDigest: "candidate", InitialIssueState: "closed"}}
	if _, e := p.readState(context.Background()); e != nil {
		t.Fatal(e)
	}
	if e := p.saveState(context.Background(), p.state); e != nil {
		t.Fatal(e)
	}
	if _, e := Reconcile(context.Background(), c, plan, PublishOptions{Actor: "alice", Apply: true}); e == nil {
		t.Fatal("interrupted PR publication treated as no-change")
	}
	if a.doneCalls != 0 || a.closeCalls != 0 {
		t.Fatal("task completed without a PR")
	}
}

func TestReadIdentityCanAuthorizeActorButCannotPublish(t *testing.T) {
	plan, run, _ := publicationFixture(t, false)
	c, a := apiFixture(t, plan)
	a.viewerLogin = "reader-bot"
	if err := AuthorizeActor(context.Background(), c, "alice"); err != nil {
		t.Fatal("read identity could not authorize initiator", err)
	}
	if err := Authorize(context.Background(), c, "alice"); err == nil {
		t.Fatal("read identity passed publisher authorization")
	}
	if _, err := Publish(context.Background(), c, plan, run, PublishOptions{Actor: "alice", Apply: true}); err == nil {
		t.Fatal("read identity published")
	}
	if a.commentCreates+a.prCreates+a.closeCalls+a.doneCalls != 0 {
		t.Fatal("publication performed effects")
	}
	if err := AuthorizeActor(context.Background(), c, "outsider"); err == nil {
		t.Fatal("unauthorized actor admitted")
	}
}

func TestReconcileAllowsAppendedHelpCorrections(t *testing.T) {
	plan, run, _ := publicationFixture(t, true)
	c, a := apiFixture(t, plan)
	original, err := Publish(context.Background(), c, plan, run, PublishOptions{Actor: "alice", Apply: true})
	if err != nil {
		t.Fatal(err)
	}
	corrected := strings.Repeat("e", 40)
	a.pr.Head.SHA = corrected
	a.branch = corrected
	observed, err := Reconcile(context.Background(), c, plan, PublishOptions{Actor: "alice", Apply: true})
	if err != nil || observed.Status != "awaiting-merge" || !observed.UpdatedAfterPublication || observed.MergedHeadSHA != "" || observed.CommitSHA != original.CommitSHA || observed.CandidateDigest != original.CandidateDigest || a.doneCalls != 0 {
		t.Fatal(observed, err)
	}
	a.pr.Merged = true
	a.pr.State = "closed"
	a.mergeSHA = strings.Repeat("f", 40)
	a.head = a.mergeSHA
	result, err := Reconcile(context.Background(), c, plan, PublishOptions{Actor: "alice", Apply: true})
	if err != nil || result.Status != "completed" || !result.UpdatedAfterPublication || result.MergedHeadSHA != corrected || result.CommitSHA != original.CommitSHA || result.CandidateDigest != original.CandidateDigest || a.doneCalls != 1 {
		t.Fatal(result, err)
	}
	again, err := Reconcile(context.Background(), c, plan, PublishOptions{Actor: "alice", Apply: true})
	if err != nil || again.MergedHeadSHA != corrected || a.doneCalls != 1 {
		t.Fatal(again, err)
	}
}

func TestPublishRejectsAnExistingPRWithAnEditedClosingKeyword(t *testing.T) {
	ctx := context.Background()
	plan, run, _ := publicationFixture(t, true)
	c, a := apiFixture(t, plan)
	if _, err := Publish(ctx, c, plan, run, PublishOptions{Actor: "alice", Apply: true}); err != nil {
		t.Fatal(err)
	}
	a.pr.Body += "\nResolves: #1"
	if _, err := Publish(ctx, c, plan, run, PublishOptions{Actor: "alice", Apply: true}); err == nil || !strings.Contains(err.Error(), "issue-closing keyword") {
		t.Fatal("publication accepted an edited closing keyword", err)
	}
	if a.prCreates != 1 || a.doneCalls != 0 || a.closeCalls != 0 {
		t.Fatal("invalid PR triggered publication effects")
	}
}

func TestReconcileRejectsRewrittenOrOutOfScopeCorrections(t *testing.T) {
	for _, change := range []string{"rewritten", "outside", "rename_outside", "changed_during_check", "closing_keyword"} {
		t.Run(change, func(t *testing.T) {
			plan, run, _ := publicationFixture(t, true)
			c, a := apiFixture(t, plan)
			if _, err := Publish(context.Background(), c, plan, run, PublishOptions{Actor: "alice", Apply: true}); err != nil {
				t.Fatal(err)
			}
			a.pr.Head.SHA = strings.Repeat("e", 40)
			a.pr.Merged = true
			a.pr.State = "closed"
			a.mergeSHA = strings.Repeat("f", 40)
			a.head = a.mergeSHA
			switch change {
			case "closing_keyword":
				a.pr.Body += "\nCloses: #1"
			case "changed_during_check":
				a.changeHeadDuringFiles = true
			case "rewritten":
				a.headComparison = "diverged"
			case "outside":
				a.prFiles = []map[string]string{{"filename": "engine/source.go"}}
			case "rename_outside":
				a.prFiles = []map[string]string{{"filename": "help/docs/readme.md", "previous_filename": "engine/source.go"}}
			}
			if _, err := Reconcile(context.Background(), c, plan, PublishOptions{Actor: "alice", Apply: true}); err == nil {
				t.Fatal("invalid reviewer correction accepted")
			}
			if a.doneCalls != 0 || a.closeCalls != 0 {
				t.Fatal("invalid PR completed the documentation task")
			}
		})
	}
}

func TestIssueReopenedAfterPreparationStopsBeforeEffects(t *testing.T) {
	plan, run, _ := publicationFixture(t, false)
	c, a := apiFixture(t, plan)
	a.state = "open"
	if _, e := Publish(context.Background(), c, plan, run, PublishOptions{Actor: "alice", Apply: true}); e == nil {
		t.Fatal("reopened issue accepted")
	}
	if a.commentCreates != 0 || a.closeCalls != 0 || a.doneCalls != 0 || a.prCreates != 0 {
		t.Fatal("effects occurred before reopened-issue check")
	}
}

func TestRecoverPlanRejectsAppliedStateWithoutPacketBinding(t *testing.T) {
	plan, _, _ := publicationFixture(t, false)
	c, a := apiFixture(t, plan)
	plan.PacketSHA256 = ""
	plan.ID = ""
	plan.ID = Digest(plan)
	state := Publication{Plan: &plan, PlanID: plan.ID, Status: "completed", Decision: "no_change", Applied: true}
	data, err := json.Marshal(state)
	if err != nil {
		t.Fatal(err)
	}
	comment := issueComment{ID: 5, Body: marker(plan) + "\n<!-- qip-docs-state:" + base64.StdEncoding.EncodeToString(data) + " -->"}
	comment.User.ID = 99
	a.comments = []issueComment{comment}
	if _, err = RecoverPlan(context.Background(), c, 1); err == nil {
		t.Fatal("recovered an applied plan without trusted preparation binding")
	}
}
