package docgithub

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

type pullRequest struct {
	ChangedFiles int `json:"changed_files"`
	User         struct {
		Login string `json:"login"`
	} `json:"user"`
	Number  int    `json:"number"`
	State   string `json:"state"`
	Body    string `json:"body"`
	HTMLURL string `json:"html_url"`
	Merged  bool   `json:"merged"`
	Head    struct {
		SHA  string `json:"sha"`
		Ref  string `json:"ref"`
		Repo struct {
			FullName string `json:"full_name"`
		} `json:"repo"`
	} `json:"head"`
	Base struct {
		SHA  string `json:"sha"`
		Ref  string `json:"ref"`
		Repo struct {
			FullName string `json:"full_name"`
		} `json:"repo"`
	} `json:"base"`
}

func (c *Client) documentationMergeCommit(ctx context.Context, pr pullRequest) (string, error) {
	const query = `query($owner:String!,$repo:String!,$number:Int!){repository(owner:$owner,name:$repo){pullRequest(number:$number){number merged headRefOid baseRefName mergeCommit{oid}}}}`
	var data struct {
		Repository struct {
			PullRequest struct {
				Number      int
				Merged      bool
				HeadRefOID  string
				BaseRefName string
				MergeCommit struct{ OID string }
			}
		}
	}
	owner, repo, _ := strings.Cut(c.Repository, "/")
	if err := c.GraphQL(ctx, query, map[string]any{"owner": owner, "repo": repo, "number": pr.Number}, &data); err != nil {
		return "", err
	}
	merged := data.Repository.PullRequest
	if !merged.Merged || merged.Number != pr.Number || merged.HeadRefOID != pr.Head.SHA || merged.BaseRefName != pr.Base.Ref {
		return "", fmt.Errorf("documentation merge differs from the verified pull request")
	}
	if !validCommitSHA.MatchString(merged.MergeCommit.OID) {
		return "", fmt.Errorf("merged documentation PR has no valid merge identity")
	}
	return merged.MergeCommit.OID, nil
}

func (p *publisher) revalidate(ctx context.Context, mergeSHA string) (Current, error) {
	if mergeSHA != "" {
		return RevalidateMerged(ctx, p.client, p.plan, mergeSHA)
	}
	state := p.state
	if state.Decision == "no_change" && (state.Status == "completed" || state.CloseAttempted || state.ProjectDoneAttempted ||
		(state.Status == "completing" && state.InitialIssueState == "closed")) {
		current, err := revalidate(ctx, p.client, p.plan, p.plan.BaseSHA)
		if err == nil && current.IssueState != "closed" {
			return current, fmt.Errorf("issue was reopened or a prior close is unresolved")
		}
		return current, err
	}
	return Revalidate(ctx, p.client, p.plan)
}
func (p *publisher) complete(ctx context.Context, mergeSHA string) error {
	current, e := p.revalidate(ctx, mergeSHA)
	if e != nil {
		return e
	}
	if current.IssueState != "closed" {
		if p.state.InitialIssueState == "closed" || p.state.CloseAttempted {
			return fmt.Errorf("issue was reopened or a prior close is unresolved")
		}
		state := p.state
		state.CloseAttempted = true
		state.Status = "completing"
		if e = p.saveState(ctx, state); e != nil {
			return e
		}
		requestErr := p.client.Request(ctx, "PATCH", fmt.Sprintf("/issues/%d", p.plan.IssueNumber), map[string]string{"state": "closed", "state_reason": "completed"}, nil)
		current, e = p.revalidate(ctx, mergeSHA)
		if current.IssueState != "closed" && fixer.GitHubWriteRejected(requestErr) {
			state.CloseAttempted = false
			return errors.Join(fmt.Errorf("issue close was rejected: %w", requestErr), e, p.saveState(ctx, state))
		}
		if e != nil {
			return e
		}
		if current.IssueState != "closed" {
			return fmt.Errorf("issue close result is unresolved: %v", requestErr)
		}
	}
	if current.ProjectStatus != p.plan.Project.DoneOptionID {
		if p.state.ProjectDoneAttempted {
			return fmt.Errorf("prior project completion is unresolved or was moved back")
		}
		state := p.state
		state.ProjectDoneAttempted = true
		state.Status = "completing"
		if e = p.saveState(ctx, state); e != nil {
			return e
		}
		query := `mutation($project:ID!,$item:ID!,$field:ID!,$option:String!){updateProjectV2ItemFieldValue(input:{projectId:$project,itemId:$item,fieldId:$field,value:{singleSelectOptionId:$option}}){projectV2Item{id}}}`
		requestErr := p.client.GraphQL(ctx, query, map[string]any{"project": p.plan.Project.ID, "item": p.plan.Project.ItemID, "field": p.plan.Project.StatusFieldID, "option": p.plan.Project.DoneOptionID}, nil)
		current, e = p.revalidate(ctx, mergeSHA)
		if current.ProjectStatus != p.plan.Project.DoneOptionID && fixer.GitHubWriteRejected(requestErr) {
			state.ProjectDoneAttempted = false
			return errors.Join(fmt.Errorf("project completion was rejected: %w", requestErr), e, p.saveState(ctx, state))
		}
		if e != nil {
			return e
		}
		if current.ProjectStatus != p.plan.Project.DoneOptionID {
			return fmt.Errorf("project completion result is unresolved: %v", requestErr)
		}
	} else if !p.state.ProjectDoneAttempted && !p.state.CloseAttempted && p.state.Status != "completed" {
		return fmt.Errorf("project completion was not requested by this task")
	}
	if current.IssueState != "closed" {
		return fmt.Errorf("issue was reopened during project completion")
	}
	if p.state.Status == "completed" {
		return nil
	}
	state := p.state
	state.Status = "completed"
	return p.saveState(ctx, state)
}
func Reconcile(ctx context.Context, c *Client, plan Plan, options PublishOptions) (Publication, error) {
	if options.Apply {
		if err := Authorize(ctx, c, options.Actor); err != nil {
			return Publication{PlanID: plan.ID, Status: "planned"}, err
		}
	}
	return reconcile(ctx, c, plan, options.Apply, nil)
}

func reconcile(ctx context.Context, c *Client, plan Plan, apply bool, merge *documentationMerge) (Publication, error) {
	p := &publisher{client: c, plan: plan, state: Publication{PlanID: plan.ID, Status: "planned"}}
	if e := validatePublicationPlan(plan); e != nil {
		return p.state, e
	}
	if c.Repository != plan.Repository {
		return p.state, fmt.Errorf("repository mismatch")
	}
	found, e := p.readState(ctx)
	if e != nil {
		return p.state, e
	}
	if !found {
		return p.state, fmt.Errorf("no documentation publication exists for this plan")
	}
	if merge != nil && (p.state.PRNumber != merge.pr.Number || !p.state.Applied ||
		p.state.Decision != "needed" || p.state.CommitSHA != merge.publication.CommitSHA ||
		p.state.CandidateDigest != merge.publication.CandidateDigest) {
		return p.state, fmt.Errorf("documentation publication changed after merge admission")
	}
	if p.state.PRNumber == 0 {
		if p.state.Decision != "no_change" {
			return p.state, fmt.Errorf("documentation PR publication is incomplete; recover the validated work artifacts")
		}
		if p.state.Status == "completed" {
			current, err := p.revalidate(ctx, "")
			if err != nil {
				return p.state, err
			}
			if current.ProjectStatus != plan.Project.DoneOptionID {
				return p.state, fmt.Errorf("completed documentation task was reopened or moved")
			}
			return p.state, nil
		}
		if !apply {
			return p.state, nil
		}
		e = p.complete(ctx, "")
		return p.state, e
	}
	var pr pullRequest
	if e = c.Request(ctx, "GET", fmt.Sprintf("/pulls/%d", p.state.PRNumber), nil, &pr); e != nil {
		return p.state, e
	}
	if pr.Number != p.state.PRNumber || pr.Head.Ref != p.state.Branch || pr.Base.Ref != plan.DefaultBranch || pr.Head.Repo.FullName != plan.Repository || pr.Base.Repo.FullName != plan.Repository || !strings.Contains(pr.Body, marker(plan)) {
		return p.state, fmt.Errorf("documentation PR no longer matches publication state")
	}
	if merge != nil && (!pr.Merged || pr.State != "closed" || pr.Head.SHA != merge.pr.Head.SHA ||
		!strings.EqualFold(pr.User.Login, merge.pr.User.Login)) {
		return p.state, fmt.Errorf("documentation merge changed after admission")
	}
	if e = verifyDocumentationHead(ctx, c, pr, p.state.CommitSHA); e != nil {
		return p.state, e
	}
	adjusted := pr.Head.SHA != p.state.CommitSHA
	if p.state.MergedHeadSHA != "" && p.state.MergedHeadSHA != pr.Head.SHA {
		return p.state, fmt.Errorf("documentation PR head differs from the recorded merged head")
	}
	if !pr.Merged {
		if _, e = Revalidate(ctx, c, plan); e != nil {
			return p.state, e
		}
		if pr.State == "closed" {
			state := p.state
			state.Status = "needs-human"
			state.UpdatedAfterPublication = adjusted
			if apply {
				e = p.saveState(ctx, state)
			} else {
				p.state = state
			}
			return p.state, e
		}
		if p.state.UpdatedAfterPublication != adjusted {
			state := p.state
			state.UpdatedAfterPublication = adjusted
			if apply {
				e = p.saveState(ctx, state)
			} else {
				p.state = state
			}
			return p.state, e
		}
		return p.state, nil
	}
	mergeSHA, e := c.documentationMergeCommit(ctx, pr)
	if e != nil {
		return p.state, e
	}
	current, e := RevalidateMerged(ctx, c, plan, mergeSHA)
	if e != nil {
		return p.state, e
	}
	if p.state.Status == "completed" {
		if current.IssueState != "closed" || current.ProjectStatus != plan.Project.DoneOptionID {
			return p.state, fmt.Errorf("completed documentation task was reopened or moved")
		}
		return p.state, nil
	}
	state := p.state
	state.MergedHeadSHA = pr.Head.SHA
	state.UpdatedAfterPublication = adjusted
	if !apply {
		return state, nil
	}
	if e = p.saveState(ctx, state); e != nil {
		return p.state, e
	}
	e = p.complete(ctx, mergeSHA)
	return p.state, e
}

var ErrNoPublication = errors.New("no documentation publication found")

func RecoverPlan(ctx context.Context, c *Client, issue int) (Plan, error) {
	author, err := c.ActorIdentity(ctx)
	if err != nil {
		return Plan{}, err
	}
	comments, err := c.API.List(ctx, fmt.Sprintf("/issues/%d/comments", issue), "")
	if err != nil {
		return Plan{}, err
	}
	var active []Plan
	var latest Plan
	var latestID int64
	for _, raw := range comments {
		var comment issueComment
		if json.Unmarshal(raw, &comment) != nil {
			return Plan{}, fmt.Errorf("invalid issue comment")
		}
		if comment.User.ID != author.ID || !strings.HasPrefix(comment.Body, "<!-- qip-docs:") {
			continue
		}
		state, err := decodePublicationState(comment.Body)
		if err != nil {
			return Plan{}, err
		}
		if state.Plan == nil {
			return Plan{}, fmt.Errorf("owned documentation state has no recoverable plan")
		}
		plan := *state.Plan
		if err = validatePublicationPlan(plan); err != nil {
			return Plan{}, err
		}
		if plan.Repository != c.Repository || plan.IssueNumber != issue || state.PlanID != plan.ID || !strings.HasPrefix(comment.Body, marker(plan)+"\n") {
			return Plan{}, fmt.Errorf("owned documentation state has another identity")
		}
		if state.Status != "completed" {
			active = append(active, plan)
		}
		if comment.ID > latestID {
			latestID = comment.ID
			latest = plan
		}
	}
	if len(active) > 1 {
		return Plan{}, fmt.Errorf("multiple documentation plans are active")
	}
	if len(active) == 1 {
		return active[0], nil
	}
	if latestID > 0 {
		return latest, nil
	}
	return Plan{}, ErrNoPublication
}
