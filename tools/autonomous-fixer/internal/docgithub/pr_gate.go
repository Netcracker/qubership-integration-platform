package docgithub

import (
	"context"
	"encoding/json"
	"fmt"
	"regexp"
	"strconv"
	"strings"
)

func VerifyDocumentationPR(ctx context.Context, c *Client, number int, publisherLogin string) error {
	pr, state, err := readDocumentationPR(ctx, c, number, publisherLogin)
	if err != nil {
		return err
	}
	return verifyDocumentationHead(ctx, c, pr, state.CommitSHA)
}

var documentationBranch = regexp.MustCompile(`^agent/docs/([1-9][0-9]*)/([a-f0-9]{12})$`)

func readDocumentationPR(ctx context.Context, c *Client, number int, publisherLogin string) (pullRequest, Publication, error) {
	var pr pullRequest
	var publication Publication
	if number <= 0 || publisherLogin == "" {
		return pr, publication, fmt.Errorf("PR and trusted publisher login are required")
	}
	if e := c.Request(ctx, "GET", fmt.Sprintf("/pulls/%d", number), nil, &pr); e != nil {
		return pr, publication, e
	}
	m := documentationBranch.FindStringSubmatch(pr.Head.Ref)
	if len(m) != 3 || pr.Number != number || !strings.EqualFold(pr.User.Login, publisherLogin) || pr.Head.Repo.FullName != c.Repository || pr.Base.Repo.FullName != c.Repository {
		return pr, publication, fmt.Errorf("PR is not a trusted documentation publication")
	}
	issue, err := strconv.Atoi(m[1])
	if err != nil {
		return pr, publication, fmt.Errorf("documentation branch issue number is invalid")
	}
	comments, e := c.API.List(ctx, fmt.Sprintf("/issues/%d/comments", issue), "")
	if e != nil {
		return pr, publication, e
	}
	for _, raw := range comments {
		var comment issueComment
		if e = json.Unmarshal(raw, &comment); e != nil {
			return pr, publication, e
		}
		if !strings.EqualFold(comment.User.Login, publisherLogin) || !strings.HasPrefix(comment.Body, "<!-- qip-docs:") {
			continue
		}
		state, err := decodePublicationState(comment.Body)
		if err != nil || state.Plan == nil || validatePublicationPlan(*state.Plan) != nil {
			continue
		}
		p := state.Plan
		if p.Repository != c.Repository || p.IssueNumber != issue || state.PlanID != p.ID || p.ID[:12] != m[2] {
			continue
		}
		if !strings.HasPrefix(comment.Body, marker(*p)+"\n") || !strings.Contains(pr.Body, marker(*p)) || !regexp.MustCompile(fmt.Sprintf(`(?m)^Related #%d\s*$`, issue)).MatchString(pr.Body) {
			continue
		}
		if state.Branch != pr.Head.Ref || state.CandidateDigest == "" || p.DefaultBranch != pr.Base.Ref || (state.PRNumber != number && !(state.PRNumber == 0 && state.Status == "publishing")) {
			return pr, publication, fmt.Errorf("PR differs from its recorded documentation publication")
		}
		if publication.Plan != nil {
			return pr, publication, fmt.Errorf("multiple matching documentation state comments")
		}
		publication = state
	}
	if publication.Plan == nil {
		return pr, publication, fmt.Errorf("no matching authenticated documentation state")
	}
	return pr, publication, nil
}
