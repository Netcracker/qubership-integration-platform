package docgithub

import (
	"encoding/base64"
	"encoding/json"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

func mergedDocumentationFixture(t *testing.T) (*Client, *publicationAPI, Plan, DocumentationMergeEvent) {
	t.Helper()
	plan, run, _ := publicationFixture(t, true)
	plan.IssueState = "open"
	plan.ID = ""
	plan.ID = Digest(plan)
	c, api := apiFixture(t, plan)
	api.state = "open"
	published, err := Publish(t.Context(), c, plan, run, PublishOptions{Actor: "alice", Apply: true})
	if err != nil || published.Status != "awaiting-merge" {
		t.Fatal(published, err)
	}
	api.pr.Merged, api.pr.State = true, "closed"
	api.pr.Head.SHA = strings.Repeat("e", 40)
	api.mergeSHA = strings.Repeat("f", 40)
	api.head = api.mergeSHA
	event := DocumentationMergeEvent{
		Action: "closed", Repository: fixer.Repository{FullName: plan.Repository, DefaultBranch: "main"}, PullRequest: *api.pr,
	}
	return c, api, plan, event
}

func TestMergedDocumentationReconcilesOnceWithoutOperatorAccessForMerger(t *testing.T) {
	c, api, plan, event := mergedDocumentationFixture(t)
	ctx := t.Context()
	if _, err := Reconcile(ctx, c, plan, PublishOptions{Actor: "reviewer", Apply: true}); err == nil {
		t.Fatal("a reviewer outside the operator allowlist started manual reconciliation")
	}
	route, err := RouteDocumentationMerge(ctx, c, event, "agent-bot")
	if err != nil || route.Issue != plan.IssueNumber || route.PlanID != plan.ID || api.closeCalls+api.doneCalls != 0 {
		t.Fatal(route, err)
	}
	for range 2 {
		result, err := ReconcileDocumentationMerge(ctx, c, event, route)
		if err != nil || result.Status != "completed" || result.MergedHeadSHA != event.PullRequest.Head.SHA || !result.UpdatedAfterPublication {
			t.Fatal(result, err)
		}
	}
	if api.state != "closed" || api.project != "DONE" || api.closeCalls != 1 || api.doneCalls != 1 || api.commentCreates != 1 {
		t.Fatal("merged documentation repeated or missed completion effects")
	}
	api.state = "open"
	if _, err := ReconcileDocumentationMerge(ctx, c, event, route); err == nil || api.closeCalls != 1 || api.doneCalls != 1 {
		t.Fatal("a repeated merge delivery closed a reopened issue", err)
	}
}

func TestDocumentationMergeRouteRejectsUntrustedEvents(t *testing.T) {
	for _, change := range []struct {
		name string
		edit func(*publicationAPI, *DocumentationMergeEvent)
	}{
		{"closed without merge", func(api *publicationAPI, event *DocumentationMergeEvent) {
			event.PullRequest.Merged = false
		}},
		{"fork head", func(api *publicationAPI, event *DocumentationMergeEvent) {
			event.PullRequest.Head.Repo.FullName = "fork/repo"
		}},
		{"current default branch differs", func(api *publicationAPI, event *DocumentationMergeEvent) {
			api.defaultBranch = "other"
		}},
		{"current GitHub pull request author is foreign", func(api *publicationAPI, event *DocumentationMergeEvent) {
			api.pr.User.Login = "outsider"
		}},
		{"canonical comment belongs to another author", func(api *publicationAPI, event *DocumentationMergeEvent) {
			api.comments[0].User = fixer.GitHubUser{ID: 12, Login: "outsider"}
		}},
		{"merged head differs from the event", func(api *publicationAPI, event *DocumentationMergeEvent) {
			api.pr.Head.SHA = strings.Repeat("c", 40)
		}},
		{"PR changes source code", func(api *publicationAPI, event *DocumentationMergeEvent) {
			api.prFiles = []map[string]string{{"filename": "engine/source.go"}}
		}},
	} {
		t.Run(change.name, func(t *testing.T) {
			c, api, _, event := mergedDocumentationFixture(t)
			change.edit(api, &event)
			if _, err := RouteDocumentationMerge(t.Context(), c, event, "agent-bot"); err == nil {
				t.Fatal("merge route accepted invalid evidence")
			}
			if api.closeCalls+api.doneCalls != 0 {
				t.Fatal("merge routing changed the issue or project")
			}
		})
	}
}

func TestDocumentationMergeWritesRequireFreshAuthorizationAndExactRoute(t *testing.T) {
	c, api, _, event := mergedDocumentationFixture(t)
	ctx := t.Context()
	route, err := RouteDocumentationMerge(ctx, c, event, "agent-bot")
	if err != nil {
		t.Fatal(err)
	}
	for _, damage := range []string{"disabled", "wrong publisher credential", "wrong routed issue", "wrong routed plan", "changed help scope", "foreign canonical commenter"} {
		t.Run(damage, func(t *testing.T) {
			admitted := route
			api.disableDocs = damage == "disabled"
			api.viewerLogin = "agent-bot"
			api.prFiles = []map[string]string{{"filename": "help/docs/topic.md"}}
			api.comments[0].User.ID = 99
			switch damage {
			case "wrong publisher credential":
				api.viewerLogin = "reader-bot"
			case "wrong routed issue":
				admitted.Issue++
			case "wrong routed plan":
				admitted.PlanID = strings.Repeat("a", 64)
			case "changed help scope":
				api.prFiles[0]["previous_filename"] = "engine/source.go"
			case "foreign canonical commenter":
				api.comments[0].User.ID = 12
			}
			if _, err := ReconcileDocumentationMerge(ctx, c, event, admitted); err == nil {
				t.Fatal("merge reconciliation accepted stale or unauthorized evidence")
			}
			if api.closeCalls+api.doneCalls != 0 {
				t.Fatal("merge reconciliation changed the issue or project")
			}
		})
	}
}

func TestDocumentationMergeRequiresRecordedPullRequestNumber(t *testing.T) {
	c, api, plan, event := mergedDocumentationFixture(t)
	state, err := decodePublicationState(api.comments[0].Body)
	if err != nil {
		t.Fatal(err)
	}
	state.PRNumber = 0
	state.Status = "publishing"
	body, err := json.Marshal(state)
	if err != nil {
		t.Fatal(err)
	}
	api.comments[0].Body = marker(plan) + "\n<!-- qip-docs-state:" + base64.StdEncoding.EncodeToString(body) + " -->"
	if err = VerifyDocumentationPR(t.Context(), c, event.PullRequest.Number, "agent-bot"); err != nil {
		t.Fatal("the regular PR gate must allow pending publication readback", err)
	}
	if _, err = RouteDocumentationMerge(t.Context(), c, event, "agent-bot"); err == nil || api.closeCalls+api.doneCalls != 0 {
		t.Fatal("a merge event completed an unconfirmed publication")
	}
}
