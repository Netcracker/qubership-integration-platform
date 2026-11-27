package hostedrevision

import (
	"cmp"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"slices"
	"strings"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentci"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentpr"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/autorequest"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

type ChangeRequest struct {
	autorequest.Review
	Head        string
	SubmittedAt time.Time
}

func LatestChangeRequests(ctx context.Context, client *fixer.GitHubClient, pull int64) ([]ChangeRequest, error) {
	items, err := client.List(ctx, fmt.Sprintf("/pulls/%d/reviews", pull), "")
	if err != nil {
		return nil, err
	}
	var reviews []discussion
	for _, item := range items {
		var review discussion
		if err := json.Unmarshal(item, &review); err != nil {
			return nil, err
		}
		if review.ID > 0 && review.User.ID > 0 && review.User.Type == "User" && !review.SubmittedAt.IsZero() {
			reviews = append(reviews, review)
		}
	}
	slices.SortFunc(reviews, func(a, b discussion) int {
		return cmp.Or(a.SubmittedAt.Compare(b.SubmittedAt), cmp.Compare(a.ID, b.ID))
	})
	lastApproval := map[int64]int{}
	for i, review := range reviews {
		if review.State == "APPROVED" {
			lastApproval[review.User.ID] = i + 1
		}
	}
	var result []ChangeRequest
	for i, review := range reviews {
		if review.State == "CHANGES_REQUESTED" && i >= lastApproval[review.User.ID] && commitSHA.MatchString(review.CommitID) {
			result = append(result, ChangeRequest{
				Review: autorequest.Review{ID: review.ID, Actor: review.User},
				Head:   review.CommitID, SubmittedAt: review.SubmittedAt,
			})
		}
	}
	return result, nil
}

func ReviewerAllowed(ctx context.Context, client *fixer.GitHubClient, actors string, actor fixer.GitHubUser) (bool, error) {
	if actor.ID <= 0 || actor.Type != "User" || !fixer.Allowed(actors, actor.Login) {
		return false, nil
	}
	var permission struct {
		Permission string           `json:"permission"`
		User       fixer.GitHubUser `json:"user"`
	}
	if err := client.Request(ctx, "GET", "/collaborators/"+url.PathEscape(actor.Login)+"/permission", nil, &permission); err != nil {
		if notFound(err) {
			return false, nil
		}
		return false, err
	}
	return permission.User == actor && (permission.Permission == "admin" || permission.Permission == "write"), nil
}

func notFound(err error) bool {
	var response *fixer.HTTPError
	return errors.As(err, &response) && response.Status == http.StatusNotFound
}

func (c *Controller) request(pr agentpr.PullRequest, command fixer.Comment) (autorequest.Request, error) {
	r, err := autorequest.Decode(command.Body)
	if err != nil || r.RepositoryID != c.repo.ID || r.PullID != pr.ID || r.PullNumber != pr.Number {
		return r, ErrUnauthorized
	}
	if r.Head != pr.Head.SHA {
		return r, ErrStale
	}
	return r, nil
}

func (c *Controller) currentReviews(ctx context.Context, actors string, pr agentpr.PullRequest, requested []autorequest.Review) ([]autorequest.Review, error) {
	if len(requested) == 0 {
		return nil, nil
	}
	live, err := LatestChangeRequests(ctx, c.cfg.Client, pr.Number)
	if err != nil {
		return nil, err
	}
	var reviews []autorequest.Review
	for _, candidate := range requested {
		if !slices.ContainsFunc(live, func(review ChangeRequest) bool {
			return review.Head == pr.Head.SHA && review.Review == candidate
		}) {
			continue
		}
		allowed, err := ReviewerAllowed(ctx, c.cfg.Client, actors, candidate.Actor)
		if err != nil {
			return nil, err
		}
		if allowed {
			reviews = append(reviews, candidate)
		}
	}
	return reviews, nil
}

func (c *Controller) currentFailures(ctx context.Context, pr agentpr.PullRequest, requested []autorequest.Failure, consumed ...autorequest.Failure) ([]autorequest.Failure, error) {
	if len(requested) == 0 {
		return nil, nil
	}
	configured, err := c.cfg.Variables.RepositoryVariable(ctx, "AGENT_CI_REQUIRED_CHECKS")
	if err != nil && !notFound(err) {
		return nil, err
	}
	snapshot, err := agentci.ReadPullRequestSnapshot(ctx, c.cfg.Client, pr.Base.Ref, pr.Head.SHA, pr.MergeSHA, configured)
	if err != nil {
		return nil, err
	}
	return agentci.RecheckFailures(ctx, c.cfg.Client, snapshot, requested, consumed)
}

func (c *Controller) prepareAutomatic(ctx context.Context, actors string, pr agentpr.PullRequest, command fixer.Comment, seen map[int64]Assignment, comments []fixer.Comment, a *Assignment) error {
	r, err := c.request(pr, command)
	if err != nil {
		return err
	}
	var consumedFailures []autorequest.Failure
	for _, existing := range seen {
		if existing.Head != r.Head {
			continue
		}
		consumed := existing.ConsumedReviews()
		r.Reviews = slices.DeleteFunc(r.Reviews, func(review autorequest.Review) bool {
			return slices.Contains(consumed, review.ID)
		})
		r.Failures = slices.DeleteFunc(r.Failures, func(failure autorequest.Failure) bool {
			return slices.Contains(existing.FailureKeys, failure.Key)
		})
		if !existing.Automatic || len(r.Failures) == 0 || len(existing.FailureKeys) == 0 {
			continue
		}
		for _, key := range existing.FailureKeys {
			consumedFailures = append(consumedFailures, autorequest.Failure{Key: key})
		}
		i := slices.IndexFunc(comments, func(comment fixer.Comment) bool {
			return comment.ID == existing.CommentID && comment.UpdatedAt == comment.CreatedAt && existing.matchesCommand(comment)
		})
		if i < 0 {
			continue
		}
		prior, err := c.request(pr, comments[i])
		if err != nil {
			return err
		}
		for _, failure := range prior.Failures {
			if slices.Contains(existing.FailureKeys, failure.Key) {
				consumedFailures = append(consumedFailures, failure)
			}
		}
	}
	reviews, err := c.currentReviews(ctx, actors, pr, r.Reviews)
	if err != nil {
		return err
	}
	failures, err := c.currentFailures(ctx, pr, r.Failures, consumedFailures...)
	if err != nil {
		return err
	}
	for _, review := range reviews {
		a.ReviewIDs = append(a.ReviewIDs, review.ID)
	}
	for _, failure := range failures {
		a.FailureKeys = append(a.FailureKeys, failure.Key)
	}
	if len(reviews)+len(failures) == 0 {
		return ErrUnauthorized
	}
	a.Feedback, err = c.automaticFeedback(ctx, pr, reviews, failures)
	return err
}

func (c *Controller) automaticFeedback(ctx context.Context, pr agentpr.PullRequest, reviews []autorequest.Review, failures []autorequest.Failure) ([]Feedback, error) {
	selected := make([]selection, 0, len(reviews))
	for _, review := range reviews {
		selected = append(selected, selection{
			anchor: fmt.Sprintf("#pullrequestreview-%d", review.ID), kind: "pullrequestreview-", id: review.ID,
			actor: review.Actor, head: pr.Head.SHA,
		})
	}
	feedback, err := loadFeedback(ctx, c.cfg.Client, c.repo.FullName, pr.Number, selected)
	if err != nil {
		return nil, err
	}
	for _, failure := range failures {
		feedback = append(feedback, Feedback{
			Kind: "ci", URL: failure.URL, Body: strings.TrimSpace(failure.Name + "\n\n" + failure.Excerpt),
		})
	}
	return feedback, nil
}

func (c *Controller) verifyAutomatic(ctx context.Context, actors string, pr agentpr.PullRequest, command fixer.Comment, a *Assignment) error {
	r, err := c.request(pr, command)
	if err != nil {
		return err
	}
	reviews, err := c.currentReviews(ctx, actors, pr, slices.DeleteFunc(r.Reviews, func(review autorequest.Review) bool {
		return !slices.Contains(a.ReviewIDs, review.ID)
	}))
	if err != nil {
		return err
	}
	failures, err := c.currentFailures(ctx, pr, slices.DeleteFunc(r.Failures, func(failure autorequest.Failure) bool {
		return !slices.Contains(a.FailureKeys, failure.Key)
	}))
	if err != nil {
		return err
	}
	if len(reviews) != len(a.ReviewIDs) || len(failures) != len(a.FailureKeys) {
		return ErrUnauthorized
	}
	if a.Feedback == nil {
		a.Feedback, err = c.automaticFeedback(ctx, pr, reviews, failures)
		return err
	}
	return nil
}
