package autofollow

import (
	"context"
	"slices"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/hostedrevision"
)

type review = hostedrevision.ChangeRequest

func (c *controller) reviews(ctx context.Context, allowed string) ([]review, error) {
	current, err := hostedrevision.LatestChangeRequests(ctx, c.client, c.pr.Number)
	if err != nil {
		return nil, err
	}
	selected := current[:0]
	for _, review := range current {
		allowed, err := hostedrevision.ReviewerAllowed(ctx, c.client, allowed, review.Actor)
		if err != nil {
			return nil, err
		}
		if allowed {
			selected = append(selected, review)
		}
	}
	return selected, nil
}

func lateReview(current review, assignments []revisionInput, requests []requestRecord) bool {
	for _, assignment := range assignments {
		if assignment.head == current.Head && !current.SubmittedAt.Before(assignment.createdAt) {
			return true
		}
	}
	for _, request := range requests {
		if request.request.Head == current.Head &&
			(slices.Contains(request.request.Reviews, current.Review) || !current.SubmittedAt.Before(request.createdAt)) {
			return true
		}
	}
	return false
}
