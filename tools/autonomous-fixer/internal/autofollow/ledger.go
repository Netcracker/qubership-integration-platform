package autofollow

import (
	"cmp"
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"html"
	"net/url"
	"slices"
	"strings"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentpr"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/autorequest"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/hostedrevision"
)

const workWorkflow = "agent-work.yaml"
const deliveryGrace = 2 * time.Minute
const botFooter = "<sub>QIP engineering workflow</sub>"

type requestRecord struct {
	id        int64
	createdAt time.Time
	request   autorequest.Request
}

type revisionInput struct {
	head      string
	createdAt time.Time
}

type ledger struct {
	requests    []requestRecord
	assignments map[int64]hostedrevision.Assignment
	results     map[int64]string
	published   map[string]int64
	markers     map[string]time.Time
}

func (c *controller) comments(ctx context.Context) ([]fixer.Comment, error) {
	items, err := c.client.List(ctx, fmt.Sprintf("/issues/%d/comments", c.pr.Number), "")
	if err != nil {
		return nil, err
	}
	var comments []fixer.Comment
	for _, item := range items {
		var comment fixer.Comment
		if err := json.Unmarshal(item, &comment); err != nil {
			return nil, fmt.Errorf("invalid pull request comment: %w", err)
		}
		if comment.ID > 0 && c.authors.Contains(comment.User) {
			comments = append(comments, comment)
		}
	}
	return comments, nil
}

func (c *controller) marker(key string) string {
	return fmt.Sprintf("<!-- qip-auto-follow:v1 %d:%d:%s -->", c.repo.ID, c.pr.ID, key)
}

func (c *controller) ledger(ctx context.Context) (ledger, error) {
	comments, err := c.comments(ctx)
	if err != nil {
		return ledger{}, err
	}
	assignments, err := hostedrevision.Assignments(comments, c.authors, c.repo.ID, c.pr.Number)
	if err != nil {
		return ledger{}, err
	}
	book := ledger{
		assignments: assignments,
		results:     map[int64]string{},
		published:   hostedrevision.PublishedRevisions(comments, c.authors, c.repo.ID, c.pr.Number),
		markers:     map[string]time.Time{},
	}
	for id, assignment := range assignments {
		book.results[id] = hostedrevision.RevisionResultStatus(comments, c.authors, assignment)
	}
	for _, comment := range comments {
		line, _, _ := strings.Cut(comment.Body, "\n")
		if comment.CreatedAt == "" || comment.CreatedAt != comment.UpdatedAt {
			if strings.HasPrefix(line, autorequest.Prefix) {
				return ledger{}, fmt.Errorf("automatic revision request %d was edited", comment.ID)
			}
			continue
		}
		created, err := time.Parse(time.RFC3339, comment.CreatedAt)
		if strings.HasPrefix(line, autorequest.Prefix) {
			if err != nil {
				return ledger{}, fmt.Errorf("automatic revision request %d has no creation time", comment.ID)
			}
			request, err := autorequest.Decode(comment.Body)
			if err != nil {
				return ledger{}, fmt.Errorf("invalid automatic revision request %d: %w", comment.ID, err)
			}
			if request.RepositoryID != c.repo.ID || request.PullID != c.pr.ID || request.PullNumber != c.pr.Number {
				return ledger{}, fmt.Errorf("automatic revision request %d differs from the pull request", comment.ID)
			}
			book.requests = append(book.requests, requestRecord{id: comment.ID, createdAt: created, request: request})
		}
		if err == nil {
			book.markers[line] = created
		}
	}
	slices.SortFunc(book.requests, func(a, b requestRecord) int { return cmp.Compare(a.id, b.id) })
	return book, nil
}

func (c *controller) post(ctx context.Context, body string) (int64, error) {
	var comment fixer.Comment
	writer := c.authors.Writer()
	err := c.client.WriteIssue(ctx, writer, "POST", fmt.Sprintf("/issues/%d/comments", c.pr.Number), map[string]string{"body": body}, &comment)
	if err == nil && comment.ID > 0 && comment.User == writer && comment.Body == body {
		return comment.ID, nil
	}
	comments, readErr := c.comments(ctx)
	for _, existing := range comments {
		if existing.Body == body && existing.CreatedAt != "" && existing.CreatedAt == existing.UpdatedAt {
			return existing.ID, nil
		}
	}
	return 0, errors.Join(err, readErr, fmt.Errorf("GitHub did not confirm the follow-up comment"))
}

func markdownText(text string) string {
	return strings.NewReplacer(
		"@", "&#64;", "#", "&#35;", "[", "&#91;", "]", "&#93;", "`", "&#96;",
		"*", "&#42;", "_", "&#95;", "~", "&#126;", "\\", "&#92;", ":", "&#58;",
	).Replace(html.EscapeString(strings.Join(strings.Fields(text), " ")))
}

func (c *controller) note(ctx context.Context, book ledger, key, message string) error {
	if !c.options.Apply {
		return nil
	}
	marker := c.marker(key)
	if !book.markers[marker].IsZero() {
		return nil
	}
	if err := c.current(ctx); err != nil {
		return err
	}
	_, err := c.post(ctx, marker+"\n\n"+message+"\n\n"+botFooter)
	if err == nil {
		book.markers[marker] = time.Now()
	}
	return err
}

func digest(parts []string) string {
	return fmt.Sprintf("%x", sha256.Sum256([]byte(strings.Join(parts, "\x00"))))
}

type runRecord struct {
	fixer.WorkflowRun
	Actor fixer.GitHubUser `json:"actor"`
}

func redeliveryKey(id int64) string {
	return fmt.Sprintf("redelivery:%d", id)
}

func (c *controller) workflowRun(run fixer.WorkflowRun) bool {
	branch := c.repo.DefaultBranch
	file := ".github/workflows/" + workWorkflow
	return run.ID > 0 && run.RunAttempt > 0 && shaPattern.MatchString(run.HeadSHA) && run.HeadBranch == branch &&
		agentpr.SameRepository(run.HeadRepository, c.repo) &&
		(run.Path == file || run.Path == file+"@"+branch || run.Path == file+"@refs/heads/"+branch)
}

func (c *controller) active(ctx context.Context, book ledger) (bool, error) {
	for id, assignment := range book.assignments {
		if assignment.PullID != c.pr.ID {
			return false, fmt.Errorf("revision receipt belongs to another pull request")
		}
		if book.results[id] != "" {
			continue
		}
		var run fixer.WorkflowRun
		path := fmt.Sprintf("/actions/runs/%d", assignment.Run.RunID)
		if err := c.client.Request(ctx, "GET", path, nil, &run); err != nil {
			return false, err
		}
		identity, err := run.RevisionIdentity()
		if err != nil || run.ID != assignment.Run.RunID || !c.workflowRun(run) || run.HeadSHA != assignment.WorkflowSHA ||
			identity != (fixer.RevisionRunIdentity{PullNumber: c.pr.Number, RequestID: id, Automatic: assignment.Automatic}) || run.Status == "" {
			return false, fmt.Errorf("revision workflow differs from its receipt")
		}
		if run.Status != "completed" {
			return true, nil
		}
	}
	return false, nil
}

func (c *controller) findDispatches(ctx context.Context, earliest time.Time) (map[int64]runRecord, error) {
	query := url.Values{
		"branch":  {c.repo.DefaultBranch},
		"created": {">=" + earliest.Format(time.RFC3339)},
		"event":   {"workflow_dispatch"},
	}
	items, err := c.client.List(ctx, "/actions/workflows/"+workWorkflow+"/runs?"+query.Encode(), "workflow_runs")
	if err != nil {
		return nil, err
	}
	runs := map[int64]runRecord{}
	for _, item := range items {
		var run runRecord
		if err := json.Unmarshal(item, &run); err != nil {
			return nil, fmt.Errorf("invalid revision workflow run: %w", err)
		}
		identity, err := run.RevisionIdentity()
		if !identity.Automatic || identity.PullNumber != c.pr.Number || run.Actor != c.authors.Actions {
			continue
		}
		id := identity.RequestID
		if err != nil || !c.workflowRun(run.WorkflowRun) || run.Status == "" {
			return nil, fmt.Errorf("invalid automatic revision workflow identity")
		}
		existing, found := runs[id]
		if !found || existing.Status == "completed" && run.Status != "completed" {
			runs[id] = run
		}
	}
	return runs, nil
}
