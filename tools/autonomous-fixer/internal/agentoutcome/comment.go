package agentoutcome

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"html"
	"regexp"
	"strings"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

const markerPrefix = "<!-- qip-agent-outcome:"

var outcomeMarker = regexp.MustCompile(`^<!-- qip-agent-outcome:v1 ([1-9][0-9]*) ([1-9][0-9]*) ([1-9][0-9]*) ([1-9][0-9]*) -->` + "\n")

type savedOutcome struct {
	comment       fixer.Comment
	closure       timelineEvent
	workflowRunID int64
}

func (saved savedOutcome) olderThan(event timelineEvent) bool {
	return saved.closure.ID != event.ID && (saved.closure.CreatedAt.Before(event.CreatedAt) ||
		(saved.closure.CreatedAt.Equal(event.CreatedAt) && saved.closure.ID < event.ID))
}

func (saved savedOutcome) sameRunRetry(event timelineEvent, workflow Workflow) bool {
	return saved.closure.ID == event.ID && saved.workflowRunID == workflow.RunID && workflow.RunAttempt > 1
}

func pullComments(ctx context.Context, client *fixer.GitHubClient, pr int64) ([]fixer.Comment, error) {
	items, err := client.List(ctx, fmt.Sprintf("/issues/%d/comments", pr), "")
	if err != nil {
		return nil, err
	}
	result := make([]fixer.Comment, 0, len(items))
	for _, item := range items {
		var comment fixer.Comment
		if err := json.Unmarshal(item, &comment); err != nil {
			return nil, fmt.Errorf("invalid pull request discussion comment")
		}
		result = append(result, comment)
	}
	return result, nil
}

func findOutcome(comments []fixer.Comment, authors fixer.EngineeringAuthors, pr int64) (*savedOutcome, error) {
	var result *savedOutcome
	for _, comment := range comments {
		if !authors.Contains(comment.User) || !strings.HasPrefix(comment.Body, markerPrefix) {
			continue
		}
		fields := outcomeMarker.FindStringSubmatch(comment.Body)
		if comment.ID <= 0 || len(fields) == 0 || fields[1] != fmt.Sprint(pr) || result != nil {
			return nil, fmt.Errorf("invalid or duplicate pull request outcome comment")
		}
		id, idErr := fixer.PositiveID(fields[2])
		seconds, timeErr := fixer.PositiveID(fields[3])
		runID, runErr := fixer.PositiveID(fields[4])
		if idErr != nil || timeErr != nil || runErr != nil {
			return nil, fmt.Errorf("pull request outcome comment has an invalid closure identity")
		}
		result = &savedOutcome{comment: comment, closure: timelineEvent{ID: id, CreatedAt: time.Unix(seconds, 0)}, workflowRunID: runID}
	}
	return result, nil
}

func code(value string) string {
	if value == "" {
		return "Unavailable"
	}
	return "<code>" + html.EscapeString(value) + "</code>"
}

func snapshotLine(label string, snapshot Snapshot) string {
	return fmt.Sprintf("- %s head %s, target %s at %s, comparison base %s",
		label, code(snapshot.HeadSHA), code(snapshot.BaseRef), code(snapshot.BaseSHA), code(snapshot.ComparisonBaseSHA))
}

func outcomeBody(report Report) string {
	lines := []string{
		fmt.Sprintf("<!-- qip-agent-outcome:v1 %d %d %d %d -->", report.PullRequest, report.ClosureID, report.ClosedAt.Unix(), report.Workflow.RunID),
		"", "## Engineering draft outcome", "",
		fmt.Sprintf("- %s on %s", map[string]string{"merged": "Merged", "closed-unmerged": "Closed without merging"}[report.Disposition], report.ClosedAt.Format(time.DateTime)+" UTC"),
		fmt.Sprintf("- Issue #%d, worker run %d, attempt %d", report.Issue, report.Worker.RunID, report.Worker.RunAttempt),
		snapshotLine("Published", report.Published),
		snapshotLine("Final", report.Final),
		"- Pull request head change " + code(report.HeadChange),
		"- Patch content change " + code(report.ContentChange),
		fmt.Sprintf("- Final head CI %s, observed %s UTC", code(report.CI.Status), report.CIObservedAt.Format(time.DateTime)),
		"- Human participation evidence " + code(report.HumanInvolvement.Status),
	}
	if report.Disposition == "merged" {
		lines = append(lines, "- Merge commit "+code(report.MergeSHA))
	}
	if report.Published.Diagnostic != "" {
		lines = append(lines, "- Published comparison unavailable because "+report.Published.Diagnostic+".")
	}
	if report.Final.Diagnostic != "" {
		lines = append(lines, "- Final comparison unavailable because "+report.Final.Diagnostic+".")
	}
	lines = append(lines, "", fmt.Sprintf("[Outcome report and normalized patches](https://github.com/%s/actions/runs/%d)",
		report.Repository.FullName, report.Workflow.RunID))
	return strings.Join(lines, "\n")
}

func saveOutcome(ctx context.Context, client *fixer.GitHubClient, authors fixer.EngineeringAuthors, previous *savedOutcome, report Report) error {
	body := outcomeBody(report)
	payload := map[string]string{"body": body}
	var writeErr error
	if previous == nil {
		writeErr = client.WriteIssue(ctx, fixer.GitHubUser{}, "POST", fmt.Sprintf("/issues/%d/comments", report.PullRequest), payload, nil)
	} else {
		writeErr = client.WriteIssue(ctx, previous.comment.User, "PATCH", fmt.Sprintf("/issues/comments/%d", previous.comment.ID), payload, nil)
	}
	comments, err := pullComments(ctx, client, report.PullRequest)
	if err != nil {
		return errors.Join(writeErr, err)
	}
	current, err := findOutcome(comments, authors, report.PullRequest)
	if err != nil {
		return errors.Join(writeErr, err)
	}
	if current == nil || current.comment.Body != body {
		return errors.Join(writeErr, fmt.Errorf("pull request outcome comment was not confirmed"))
	}
	return nil
}
