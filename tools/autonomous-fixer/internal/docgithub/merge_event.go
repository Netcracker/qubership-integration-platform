package docgithub

import (
	"context"
	"fmt"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

type DocumentationMergeEvent struct {
	Action      string           `json:"action"`
	Repository  fixer.Repository `json:"repository"`
	PullRequest pullRequest      `json:"pull_request"`
}

type DocumentationMergeRoute struct {
	Issue  int    `json:"issue"`
	PlanID string `json:"plan_id"`
}

type documentationMerge struct {
	pr          pullRequest
	publication Publication
}

func readDocumentationMerge(ctx context.Context, c *Client, event DocumentationMergeEvent, publisherLogin string) (documentationMerge, error) {
	hook := event.PullRequest
	branch := event.Repository.DefaultBranch
	if event.Action != "closed" || !hook.Merged || hook.State != "closed" || hook.Number <= 0 ||
		event.Repository.FullName != c.Repository || branch == "" || hook.Base.Ref != branch ||
		hook.Head.Repo.FullName != c.Repository || hook.Base.Repo.FullName != c.Repository ||
		!documentationBranch.MatchString(hook.Head.Ref) || !validCommitSHA.MatchString(hook.Head.SHA) ||
		publisherLogin == "" || !strings.EqualFold(hook.User.Login, publisherLogin) {
		return documentationMerge{}, fmt.Errorf("event is not a merged default-branch documentation pull request")
	}
	var repo fixer.Repository
	if err := c.Request(ctx, "GET", "", nil, &repo); err != nil {
		return documentationMerge{}, err
	}
	if repo.FullName != c.Repository || repo.DefaultBranch != branch {
		return documentationMerge{}, fmt.Errorf("documentation merge does not target the current default branch")
	}
	pr, publication, err := readDocumentationPR(ctx, c, hook.Number, publisherLogin)
	if err != nil {
		return documentationMerge{}, err
	}
	if !pr.Merged || pr.State != "closed" || pr.Head.SHA != hook.Head.SHA ||
		pr.Head.Ref != hook.Head.Ref || pr.Base.Ref != branch {
		return documentationMerge{}, fmt.Errorf("GitHub pull request differs from the documentation merge event")
	}
	if !publication.Applied || publication.Decision != "needed" || publication.PRNumber != pr.Number {
		return documentationMerge{}, fmt.Errorf("merged documentation pull request has no confirmed publication")
	}
	return documentationMerge{pr: pr, publication: publication}, nil
}

func RouteDocumentationMerge(ctx context.Context, c *Client, event DocumentationMergeEvent, publisherLogin string) (DocumentationMergeRoute, error) {
	merge, err := readDocumentationMerge(ctx, c, event, publisherLogin)
	if err != nil {
		return DocumentationMergeRoute{}, err
	}
	if err = verifyDocumentationHead(ctx, c, merge.pr, merge.publication.CommitSHA); err != nil {
		return DocumentationMergeRoute{}, err
	}
	if _, err = c.documentationMergeCommit(ctx, merge.pr); err != nil {
		return DocumentationMergeRoute{}, err
	}
	return DocumentationMergeRoute{Issue: merge.publication.Plan.IssueNumber, PlanID: merge.publication.PlanID}, nil
}

func ReconcileDocumentationMerge(ctx context.Context, c *Client, event DocumentationMergeEvent, route DocumentationMergeRoute) (Publication, error) {
	if err := authorizeDocsEnabled(ctx, c); err != nil {
		return Publication{}, err
	}
	identity, err := authorizePublisher(ctx, c)
	if err != nil {
		return Publication{}, err
	}
	merge, err := readDocumentationMerge(ctx, c, event, identity.Login)
	if err != nil {
		return Publication{}, err
	}
	plan := *merge.publication.Plan
	if plan.IssueNumber != route.Issue || plan.ID != route.PlanID {
		return Publication{}, fmt.Errorf("documentation merge differs from the admitted issue and plan")
	}
	return reconcile(ctx, c, plan, true, &merge)
}
