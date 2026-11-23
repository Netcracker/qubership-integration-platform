package docgithub

import (
	"net/http"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentsignature"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

func TestScheduleStopKeepsTheClaimAndDedupe(t *testing.T) {
	for _, tc := range []struct{ reason, message string }{
		{"needs-human", "Documentation review needs input"},
		{"rejected", "The documentation candidate was rejected"},
		{"rejected-result-format", "the agent returned an invalid result format"},
		{"verification-blocked", "Documentation verification is blocked"},
		{"work-stopped", "Documentation work stopped before publication"},
		{"publish-stopped", "Re-run failed jobs"},
	} {
		t.Run(tc.reason, func(t *testing.T) {
			ctx := t.Context()
			c, a, plan := scheduleFixture(t)
			plan.RequestID, plan.ID = "500:1", ""
			plan.ID = Digest(plan)
			attempt := ScheduledAttempt{RunID: 500, Attempt: 1}
			basis := scheduleBasis(plan)
			claimed, reason, err := ClaimSchedule(ctx, c, c.API, plan, basis, "alice", attempt, true)
			if err != nil || !claimed || reason != "" {
				t.Fatal(claimed, reason, err)
			}
			original := a.comments[0]
			signature := agentsignature.Signature{Model: "observed-model", Effort: "high"}
			stop := ScheduleStop{Actor: "alice", Basis: basis, Current: attempt, Reason: tc.reason, Signature: signature}
			for _, wantUpdate := range []bool{true, false} {
				if updated, err := RecordScheduleStop(ctx, c, plan, stop); err != nil || updated != wantUpdate {
					t.Fatalf("updated=%v, err=%v, want updated=%v", updated, err, wantUpdate)
				}
			}
			comment := a.comments[0]
			if comment.ID != original.ID || a.commentCreates != 1 ||
				!strings.HasPrefix(comment.Body, scheduleReceipt(basis, attempt)+"\n") ||
				!strings.Contains(comment.Body, tc.message) ||
				!strings.Contains(comment.Body, attempt.url(plan.Repository)) ||
				!strings.HasSuffix(comment.Body, signature.Line()) ||
				strings.Contains(comment.Body, "Documentation review started") {
				t.Fatalf("unexpected stop notice: %+v", a.comments)
			}

			const previous = "/actions/runs/500/attempts/1"
			a.attempts[previous] = fixer.WorkflowRun{ID: 500, RunAttempt: 1, HeadRepository: fixer.Repository{FullName: plan.Repository},
				HeadBranch: "main", Path: scheduleWorkflow, Event: "schedule", Status: "completed"}
			a.jobs[previous] = []any{map[string]any{
				"name": "Work on documentation issue #1",
				"steps": []any{map[string]string{
					"name": "Run documentation work", "status": "completed", "conclusion": "failure",
				}},
			}}
			claimed, reason, err = ClaimSchedule(ctx, c, c.API, plan, basis, "alice", ScheduledAttempt{RunID: 501, Attempt: 1}, true)
			if err != nil || claimed || reason != "basis-already-attempted" || a.commentCreates != 1 {
				t.Fatal(claimed, reason, err, a.commentCreates)
			}
		})
	}
}

func TestScheduleStopPreservesPartialPublicationAndAllowsRetry(t *testing.T) {
	for _, tc := range []struct {
		name    string
		failure PublicationFailure
		close   int
		project string
		message string
	}{
		{"close issue", PublicationFailedIssueClose, http.StatusForbidden, "", "while closing the issue"},
		{"set project Done", PublicationFailedProjectDone, 0, projectPermissionDenied, "while moving the project item to **Done**"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			testScheduleStopPreservesPartialPublicationAndAllowsRetry(t, tc.failure, tc.close, tc.project, tc.message)
		})
	}
}

func testScheduleStopPreservesPartialPublicationAndAllowsRetry(t *testing.T, failure PublicationFailure, closeFailure int, projectFailure, message string) {
	t.Helper()
	ctx := t.Context()
	plan, run, source := publicationFixture(t, false)
	plan.RequestID, plan.ID = "500:1", ""
	plan.IssueState = "open"
	plan.ID = Digest(plan)
	c, a := apiFixture(t, plan)
	basis := scheduleBasis(plan)
	claimAttempt := ScheduledAttempt{RunID: 500, Attempt: 1}
	a.comments = []issueComment{{ID: 5, User: fixer.GitHubUser{ID: 99}, Body: scheduleReceipt(basis, claimAttempt) + "\nDocumentation review started."}}
	a.state, a.closeFailure, a.projectFailure = "open", closeFailure, projectFailure
	options := PublishOptions{Actor: "alice", Apply: true, SourcePath: source}
	_, err := Publish(ctx, c, plan, run, options)
	gotFailure := PublicationFailureOf(err)
	if gotFailure != failure {
		t.Fatalf("got publication failure %q, want %q (error %v)", gotFailure, failure, err)
	}
	before := a.comments[0].Body
	state, err := decodePublicationState(before)
	if err != nil || state.Status != "completing" {
		t.Fatalf("state=%+v, err=%v", state, err)
	}

	failedAttempt := ScheduledAttempt{RunID: 500, Attempt: 2}
	stop := ScheduleStop{Actor: "alice", Basis: basis, Current: ScheduledAttempt{RunID: 500, Attempt: 3}, Reason: "publish-stopped",
		PublicationFailure: gotFailure, PublicationAttempt: failedAttempt.Attempt}
	for _, wantUpdate := range []bool{true, false} {
		if updated, err := RecordScheduleStop(ctx, c, plan, stop); err != nil || updated != wantUpdate {
			t.Fatalf("updated=%v, err=%v, want updated=%v", updated, err, wantUpdate)
		}
	}
	after := a.comments[0].Body
	want := before[strings.LastIndex(before, "<!-- qip-docs-state:"):]
	if !strings.Contains(after, "Re-run failed jobs") || !strings.Contains(after, "`reconcile`") || !strings.HasSuffix(after, want) ||
		!strings.Contains(after, message) || !strings.Contains(after, failedAttempt.url(plan.Repository)) ||
		strings.Contains(after, claimAttempt.url(plan.Repository)) || strings.Contains(after, stop.Current.url(plan.Repository)) {
		t.Fatalf("unexpected partial publication notice: %s", after)
	}
	if recovered, err := RecoverPlan(ctx, c, plan.IssueNumber); err != nil || recovered.ID != plan.ID {
		t.Fatalf("recovered=%+v, err=%v", recovered, err)
	}
	a.closeFailure, a.projectFailure = 0, ""
	options.Attempt = stop.Current
	result, err := Publish(ctx, c, plan, run, options)
	if err != nil || result.Status != "completed" || result.CommentID != 5 || a.commentCreates != 0 ||
		result.WorkflowAttempt != stop.Current.Attempt || !strings.Contains(a.comments[0].Body, stop.Current.url(plan.Repository)) ||
		strings.Contains(a.comments[0].Body, "Re-run failed jobs") || strings.Contains(a.comments[0].Body, failedAttempt.url(plan.Repository)) {
		t.Fatalf("retry result=%+v, err=%v, comments=%+v", result, err, a.comments)
	}
	if updated, err := RecordScheduleStop(ctx, c, plan, stop); err != nil || updated {
		t.Fatal("a completed publication was overwritten", updated, err)
	}
}

func TestScheduleStopRequiresTheOwnedClaim(t *testing.T) {
	c, a, plan := scheduleFixture(t)
	plan.RequestID, plan.ID = "500:1", ""
	plan.ID = Digest(plan)
	attempt := ScheduledAttempt{RunID: 500, Attempt: 1}
	basis := scheduleBasis(plan)
	original := scheduleReceipt(basis, attempt) + "\nForeign claim"
	a.comments = []issueComment{{ID: 5, User: fixer.GitHubUser{ID: 12}, Body: original}}
	stop := ScheduleStop{Actor: "alice", Basis: basis, Current: attempt, Reason: "needs-human"}
	if updated, err := RecordScheduleStop(t.Context(), c, plan, stop); err == nil || updated {
		t.Fatal(updated, err)
	}
	if a.commentCreates != 0 || len(a.comments) != 1 || a.comments[0].Body != original {
		t.Fatal("updated a foreign comment", a.comments)
	}
}

func TestStoppedPublicationRequiresFreshAssessmentAfterBaseChanges(t *testing.T) {
	c, a, plan := scheduleFixture(t)
	plan.RequestID, plan.ID = "500:1", ""
	plan.ID = Digest(plan)
	attempt := ScheduledAttempt{RunID: 500, Attempt: 1}
	basis := scheduleBasis(plan)
	a.comments = []issueComment{{ID: 5, User: fixer.GitHubUser{ID: 99}, Body: scheduleReceipt(basis, attempt) + "\nDocumentation review started."}}
	a.head = strings.Repeat("a", 40)
	if a.head == plan.BaseSHA {
		a.head = strings.Repeat("d", 40)
	}
	stop := ScheduleStop{Actor: "alice", Basis: basis, Current: attempt, Reason: "publish-stopped"}
	updated, err := RecordScheduleStop(t.Context(), c, plan, stop)
	if err != nil || !updated || !strings.Contains(a.comments[0].Body, "Start a fresh assessment") || strings.Contains(a.comments[0].Body, "Re-run failed jobs") {
		t.Fatal(updated, err, a.comments)
	}
}
