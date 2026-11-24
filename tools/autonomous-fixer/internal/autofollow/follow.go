package autofollow

import (
	"context"
	"errors"
	"fmt"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentci"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentpr"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/autorequest"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

var shaPattern = regexp.MustCompile(`^[0-9a-f]{40}$`)
var errStale = errors.New("the pull request or source issue changed")

const reviewBatchGrace = 10 * time.Minute

type Options struct {
	Repository     string
	Ref            string
	PullRequest    int64
	RequiredChecks string
	Apply          bool
}

type Report struct {
	PullRequest int64                 `json:"pull_request"`
	Head        string                `json:"head,omitempty"`
	Status      string                `json:"status"`
	RequestID   int64                 `json:"request_id,omitempty"`
	RunID       int64                 `json:"run_id,omitempty"`
	Reviews     []autorequest.Review  `json:"reviews,omitempty"`
	Failures    []autorequest.Failure `json:"failures,omitempty"`
	RetryRuns   []int64               `json:"retry_runs,omitempty"`
	Human       []string              `json:"human,omitempty"`
}

type controller struct {
	client, variables *fixer.GitHubClient
	options           Options
	repo              fixer.Repository
	authors           fixer.EngineeringAuthors
	pr                agentpr.PullRequest
	state             *fixer.State
	issue             fixer.Issue
}

func Reconcile(ctx context.Context, client, variables *fixer.GitHubClient, options Options) (Report, error) {
	c := controller{client: client, variables: variables, options: options}
	report := Report{PullRequest: options.PullRequest, Status: "ineligible"}
	if client == nil || variables == nil || options.PullRequest <= 0 {
		return report, fmt.Errorf("automatic follow-up requires a pull request and repository clients")
	}
	if err := client.Request(ctx, "GET", "", nil, &c.repo); err != nil {
		return report, err
	}
	if c.repo.ID <= 0 || c.repo.FullName != options.Repository || c.repo.DefaultBranch == "" ||
		options.Ref != "refs/heads/"+c.repo.DefaultBranch {
		return report, fmt.Errorf("automatic follow-up requires the repository default branch")
	}
	pr, state, err := agentpr.Read(ctx, client, c.repo, options.PullRequest)
	if errors.Is(err, agentpr.ErrNotPublished) || errors.Is(err, agentpr.ErrPublicationPending) {
		return report, nil
	}
	if err != nil {
		return report, err
	}
	if pr.State != "open" || pr.Merged || pr.Base.Ref != c.repo.DefaultBranch || !shaPattern.MatchString(pr.Head.SHA) {
		return report, nil
	}
	c.pr, c.state = pr, state
	report.Head = pr.Head.SHA
	issue, err := c.sourceIssue(ctx)
	if err != nil {
		return report, err
	}
	c.issue = issue
	if !fixer.RevisionEligible(state, issue) {
		return report, nil
	}
	allowed, err := c.actors(ctx)
	if err != nil || !fixer.Allowed(allowed, state.Initiator) {
		return report, err
	}
	ancestor, err := c.ancestor(ctx, state.HostedPublication.SHA)
	if err != nil || !ancestor {
		return report, err
	}
	c.authors, err = client.EngineeringAuthors(ctx)
	if err != nil {
		return report, err
	}
	err = c.reconcile(ctx, &report, allowed)
	if errors.Is(err, errStale) {
		report.Status = "stale"
		return report, nil
	}
	return report, err
}

func (c *controller) sourceIssue(ctx context.Context) (fixer.Issue, error) {
	var issue fixer.Issue
	err := c.client.Request(ctx, "GET", fmt.Sprintf("/issues/%d", c.state.IssueNumber), nil, &issue)
	if issue.ID <= 0 || len(issue.PullRequest) > 0 && string(issue.PullRequest) != "null" {
		return fixer.Issue{}, err
	}
	return issue, err
}

func (c *controller) actors(ctx context.Context) (string, error) {
	for _, flag := range []struct{ name, value string }{
		{"AGENT_ENABLED", "true"},
		{"AGENT_MODE", "hosted"},
	} {
		value, err := c.variables.RepositoryVariable(ctx, flag.name)
		if err != nil || value != flag.value {
			return "", err
		}
	}
	return c.variables.RepositoryVariable(ctx, "AGENT_ALLOWED_ACTORS")
}

func (c *controller) ancestor(ctx context.Context, sha string) (bool, error) {
	if !shaPattern.MatchString(sha) {
		return false, fmt.Errorf("invalid published commit SHA")
	}
	if sha == c.pr.Head.SHA {
		return true, nil
	}
	var comparison struct {
		Status string `json:"status"`
	}
	err := c.client.Request(ctx, "GET", "/compare/"+sha+"..."+c.pr.Head.SHA, nil, &comparison)
	return comparison.Status == "ahead", err
}

func (c *controller) current(ctx context.Context) error {
	var repo fixer.Repository
	if err := c.client.Request(ctx, "GET", "", nil, &repo); err != nil {
		return err
	}
	if repo != c.repo {
		return errStale
	}
	live, state, err := agentpr.Read(ctx, c.client, c.repo, c.pr.Number)
	if errors.Is(err, agentpr.ErrNotPublished) || errors.Is(err, agentpr.ErrPublicationPending) {
		return errStale
	}
	if err != nil {
		return err
	}
	if live.ID != c.pr.ID || live.State != "open" || live.Merged || live.Head != c.pr.Head ||
		live.Base.Ref != c.pr.Base.Ref || live.Base.SHA != c.pr.Base.SHA || live.MergeSHA != c.pr.MergeSHA ||
		state.Generation != c.state.Generation || state.Contract.Version != c.state.Contract.Version ||
		state.Initiator != c.state.Initiator || *state.HostedPublication != *c.state.HostedPublication {
		return errStale
	}
	issue, err := c.sourceIssue(ctx)
	if err != nil {
		return err
	}
	if issue.ID != c.issue.ID || !fixer.RevisionEligible(state, issue) {
		return errStale
	}
	actors, err := c.actors(ctx)
	if err != nil {
		return err
	}
	if !fixer.Allowed(actors, state.Initiator) {
		return errStale
	}
	return nil
}

func (c *controller) reconcile(ctx context.Context, report *Report, allowed string) error {
	book, err := c.ledger(ctx)
	if err != nil {
		return err
	}
	active, err := c.active(ctx, book)
	if err != nil {
		return err
	}
	if active {
		report.Status = "active"
		return nil
	}
	reviews, err := c.reviews(ctx, allowed)
	if err != nil {
		message := "GitHub did not confirm the submitted reviews. Check the follow-up workflow and retry it to read current feedback."
		return errors.Join(err, c.note(ctx, book, "reviews:"+c.pr.Head.SHA, message))
	}

	var currentRequests []requestRecord
	for _, request := range book.requests {
		if _, admitted := book.assignments[request.id]; request.request.Head == c.pr.Head.SHA && !admitted {
			currentRequests = append(currentRequests, request)
		}
	}
	var dispatched map[int64]runRecord
	if len(currentRequests) > 0 {
		dispatched, err = c.findDispatches(ctx, currentRequests[0].createdAt)
		if err != nil {
			return err
		}
	}

	seenReviews := map[int64]bool{}
	staleReviews := map[int64]bool{}
	seenFailures := map[string]bool{}
	var inputs []revisionInput
	for requestID, assignment := range book.assignments {
		for _, id := range assignment.ConsumedReviews() {
			if book.results[requestID] == "stale" && assignment.Head != c.pr.Head.SHA {
				staleReviews[id] = true
			} else {
				seenReviews[id] = true
			}
		}
		if created, err := time.Parse(time.RFC3339, assignment.CreatedAt); err == nil {
			inputs = append(inputs, revisionInput{head: assignment.Head, createdAt: created})
		}
		if assignment.Head == c.pr.Head.SHA {
			for _, key := range assignment.FailureKeys {
				seenFailures[key] = true
			}
		}
	}

	var pending []requestRecord
	for _, item := range currentRequests {
		key := fmt.Sprintf("delivery:%d", item.id)
		run, delivered := dispatched[item.id]
		if delivered && run.Status != "completed" {
			report.Status, report.RequestID, report.RunID = "active", item.id, run.ID
			return nil
		}
		uncertain := !book.markers[c.marker(key)].IsZero()
		redelivered := book.markers[c.marker(redeliveryKey(item.id))]
		if !delivered && !uncertain && redelivered.IsZero() {
			pending = append(pending, item)
			continue
		}
		if !delivered && !uncertain {
			if time.Since(redelivered) < deliveryGrace {
				report.Status, report.RequestID = "pending-delivery", item.id
				return nil
			}
			message := fmt.Sprintf("GitHub did not confirm delivery of [this automatic revision request](#issuecomment-%d). Check the Actions tab before using `/agent revise`.", item.id)
			if err := c.note(ctx, book, key, message); err != nil {
				return err
			}
			report.Human = append(report.Human, "GitHub did not confirm the automatic revision workflow.")
		}
		if delivered && requestReviewsCurrent(item.request.Reviews, c.pr.Head.SHA, reviews) {
			message := fmt.Sprintf("[Automatic revision run](https://github.com/%s/actions/runs/%d) ended before accepting [the request](#issuecomment-%d). Review the run and use `/agent revise` if the feedback still applies.", c.repo.FullName, run.ID, item.id)
			if err := c.note(ctx, book, key, message); err != nil {
				return err
			}
			report.Human = append(report.Human, "The automatic revision ended before admission.")
		}
		for _, review := range item.request.Reviews {
			seenReviews[review.ID] = true
		}
		for _, failure := range item.request.Failures {
			seenFailures[failure.Key] = true
		}
	}
	var oldestReview time.Time
	for _, review := range reviews {
		if seenReviews[review.ID] {
			continue
		}
		if review.Head == c.pr.Head.SHA {
			report.Reviews = append(report.Reviews, review.Review)
			if oldestReview.IsZero() || review.SubmittedAt.Before(oldestReview) {
				oldestReview = review.SubmittedAt
			}
		} else if staleReviews[review.ID] || lateReview(review, inputs, book.requests) {
			key := fmt.Sprintf("reconfirm:%d", review.ID)
			message := fmt.Sprintf("The head changed before the agent could address [this review](#pullrequestreview-%d). If the feedback still applies, request changes again on the current head or use `/agent revise` with the review link.", review.ID)
			if err := c.note(ctx, book, key, message); err != nil {
				return err
			}
			report.Human = append(report.Human, fmt.Sprintf("Reconfirm review %d on the current head.", review.ID))
		}
	}

	plan, batchPending, err := c.plan(ctx, book, seenFailures)
	if err != nil {
		return err
	}
	report.Human = append(report.Human, plan.Human...)
	report.Failures = plan.Failures
	for _, runID := range plan.RetryRuns {
		if err := c.retryInfrastructure(ctx, book, report, runID); err != nil {
			return err
		}
	}
	for _, item := range pending {
		if !canDeliver(item.request, report.Reviews, plan) {
			continue
		}
		report.RequestID = item.id
		report.Reviews, report.Failures = item.request.Reviews, item.request.Failures
		if c.options.Apply && time.Since(item.createdAt) >= deliveryGrace {
			message := fmt.Sprintf("The workflow is resuming delivery of [this automatic revision request](#issuecomment-%d).", item.id)
			if err := c.note(ctx, book, redeliveryKey(item.id), message); err != nil {
				return err
			}
			return c.dispatch(ctx, book, report, item.id)
		}
		report.Status = "pending-delivery"
		return nil
	}
	if len(report.Reviews)+len(report.Failures) == 0 {
		switch {
		case len(report.RetryRuns) > 0:
			report.Status = "infra-retry"
		case len(report.Human) > 0:
			report.Status = "needs-human"
		case !plan.Ready:
			report.Status = "waiting-ci"
		default:
			report.Status = "unchanged"
		}
		return nil
	}
	if batchPending && time.Since(oldestReview) < reviewBatchGrace {
		report.Status = "waiting-ci"
		return nil
	}
	request := autorequest.Request{RepositoryID: c.repo.ID, PullID: c.pr.ID, PullNumber: c.pr.Number,
		Head: c.pr.Head.SHA, Reviews: report.Reviews, Failures: report.Failures}
	body, err := requestBody(request)
	if err != nil {
		return err
	}
	if !c.options.Apply {
		report.Status = "planned"
		return nil
	}
	if err := c.current(ctx); err != nil {
		return err
	}
	id, err := c.post(ctx, body)
	if err != nil {
		return err
	}
	return c.dispatch(ctx, book, report, id)
}

func requestReviewsCurrent(selected []autorequest.Review, head string, current []review) bool {
	for _, selected := range selected {
		if !slices.ContainsFunc(current, func(review review) bool { return review.Head == head && review.Review == selected }) {
			return false
		}
	}
	return true
}

func canDeliver(request autorequest.Request, reviews []autorequest.Review, plan agentci.RepairPlan) bool {
	for _, review := range request.Reviews {
		if !slices.Contains(reviews, review) {
			return false
		}
	}
	for _, failure := range request.Failures {
		if _, found := plan.ResolveFailure(failure); !found {
			return false
		}
	}
	return true
}

func requestBody(request autorequest.Request) (string, error) {
	marker, err := autorequest.Encode(request)
	if err != nil {
		return "", err
	}
	var body strings.Builder
	fmt.Fprintf(&body, "%s\n\n### Automatic revision request\n\nPR head `%s`\n", marker, request.Head)
	for _, review := range request.Reviews {
		fmt.Fprintf(&body, "\n- [Changes requested by %s](#pullrequestreview-%d)", markdownText(review.Actor.Login), review.ID)
	}
	for _, failure := range request.Failures {
		fmt.Fprintf(&body, "\n- CI failure in %s. %s", markdownText(failure.Name), markdownText(failure.Excerpt))
	}
	fmt.Fprint(&body, "\n\n", botFooter)
	return body.String(), nil
}

func (c *controller) dispatch(ctx context.Context, book ledger, report *Report, id int64) error {
	report.RequestID = id
	if err := c.current(ctx); err != nil {
		return err
	}
	data, err := c.dispatchRequest(ctx, id)
	if err == nil {
		report.Status, report.RunID = "dispatched", data
		return nil
	}
	runs, readErr := c.findDispatches(ctx, time.Now().UTC().Add(-deliveryGrace))
	if run, found := runs[id]; found {
		report.Status, report.RunID = "dispatched", run.ID
		return nil
	}
	report.Status = "delivery-uncertain"
	key := fmt.Sprintf("delivery:%d", id)
	message := fmt.Sprintf("GitHub did not confirm the workflow for [this automatic revision request](#issuecomment-%d). Check the Actions tab before using `/agent revise`.", id)
	return errors.Join(err, readErr, c.note(ctx, book, key, message))
}

func (c *controller) dispatchRequest(ctx context.Context, id int64) (int64, error) {
	var response struct {
		RunID int64 `json:"workflow_run_id"`
	}
	err := c.client.Request(ctx, "POST", "/actions/workflows/"+workWorkflow+"/dispatches", map[string]any{
		"ref": c.repo.DefaultBranch,
		"inputs": map[string]string{
			"auto_pr": strconv.FormatInt(c.pr.Number, 10), "auto_request": strconv.FormatInt(id, 10),
		},
	}, &response)
	if err == nil && response.RunID <= 0 {
		err = fmt.Errorf("GitHub dispatch response had no workflow run ID")
	}
	return response.RunID, err
}
