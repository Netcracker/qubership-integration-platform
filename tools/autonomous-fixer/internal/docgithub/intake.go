package docgithub

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"sort"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docpacket"
)

var (
	errStaleBase        = errors.New("default branch changed; refresh documentation assessment")
	errNoImplementation = errors.New("no unambiguous merged implementing pull request found")
	errNotInDocReview   = errors.New("issue is not in In doc review")
	errOutsideProject   = errors.New("issue is outside the configured documentation project")
	errArchivedItem     = errors.New("documentation project item is archived")
)

type Prepared struct {
	Plan        Plan            `json:"plan"`
	Issue       docpacket.Issue `json:"issue"`
	Complete    bool            `json:"complete"`
	Limitations []string        `json:"limitations"`
	Excluded    []int           `json:"excluded_documentation_or_test_prs"`
	Ambiguous   []int           `json:"ambiguous_referenced_prs"`
}
type issueInfo struct {
	Number      int    `json:"number"`
	NodeID      string `json:"node_id"`
	Title       string `json:"title"`
	Body        string `json:"body"`
	HTMLURL     string `json:"html_url"`
	State       string `json:"state"`
	PullRequest any    `json:"pull_request"`
}

func (c *Client) head(ctx context.Context, branch string) (string, error) {
	var data struct {
		Object struct {
			SHA string `json:"sha"`
		} `json:"object"`
	}
	err := c.Request(ctx, "GET", "/git/ref/heads/"+url.PathEscape(branch), nil, &data)
	if err == nil && len(data.Object.SHA) != 40 {
		err = fmt.Errorf("GitHub returned no branch head")
	}
	return data.Object.SHA, err
}
func (c *Client) project(ctx context.Context, node, projectID string, number int) (Project, string, error) {
	var result struct {
		Repository struct {
			Owner struct {
				Project struct {
					ID     string `json:"id"`
					Fields struct {
						Nodes []struct {
							ID, Name string
							Options  []struct{ ID, Name string }
						}
						PageInfo struct{ HasNextPage bool }
					} `json:"fields"`
				} `json:"projectV2"`
			} `json:"owner"`
		} `json:"repository"`
		Node struct {
			ProjectItems struct {
				Nodes []struct {
					ID         string
					IsArchived bool
					Project    struct{ ID string }
					Status     struct {
						OptionID string `json:"optionId"`
						Field    struct{ ID string }
					} `json:"fieldValueByName"`
				}
				PageInfo struct{ HasNextPage bool }
			} `json:"projectItems"`
		} `json:"node"`
	}
	query := `query($owner:String!,$repo:String!,$number:Int!,$node:ID!){repository(owner:$owner,name:$repo){owner{... on ProjectV2Owner{projectV2(number:$number){id fields(first:100){pageInfo{hasNextPage} nodes{... on ProjectV2SingleSelectField{id name options{id name}}}}}}}} node(id:$node){... on Issue{projectItems(first:100){pageInfo{hasNextPage} nodes{id isArchived project{id} fieldValueByName(name:"Status"){... on ProjectV2ItemFieldSingleSelectValue{optionId field{... on ProjectV2SingleSelectField{id}}}}}}}}}`
	owner, repo, _ := strings.Cut(c.Repository, "/")
	if err := c.GraphQL(ctx, query, map[string]any{"owner": owner, "repo": repo, "number": number, "node": node}, &result); err != nil {
		return Project{}, "", err
	}
	p := Project{ID: result.Repository.Owner.Project.ID, ReviewStatus: "In doc review", DoneStatus: "Done"}
	if p.ID == "" || projectID != "" && p.ID != projectID || result.Repository.Owner.Project.Fields.PageInfo.HasNextPage || result.Node.ProjectItems.PageInfo.HasNextPage {
		return p, "", fmt.Errorf("project identity or membership retrieval is incomplete")
	}
	for _, f := range result.Repository.Owner.Project.Fields.Nodes {
		if f.Name == "Status" {
			p.StatusFieldID = f.ID
			for _, o := range f.Options {
				if o.Name == p.ReviewStatus {
					p.ReviewOptionID = o.ID
				}
				if o.Name == p.DoneStatus {
					p.DoneOptionID = o.ID
				}
			}
		}
	}
	status := ""
	for _, item := range result.Node.ProjectItems.Nodes {
		if item.Project.ID == p.ID {
			if item.IsArchived {
				return p, "", errArchivedItem
			}
			if p.ItemID != "" {
				return p, "", fmt.Errorf("duplicate project membership")
			}
			p.ItemID = item.ID
			status = item.Status.OptionID
			if item.Status.Field.ID != p.StatusFieldID {
				return p, "", fmt.Errorf("project status field differs")
			}
		}
	}
	if p.StatusFieldID == "" || p.ReviewOptionID == "" || p.DoneOptionID == "" {
		return p, "", fmt.Errorf("required project statuses are absent")
	}
	if p.ItemID == "" {
		return p, "", errOutsideProject
	}
	return p, status, nil
}

type linkedPR struct {
	Number                  int
	Merged                  bool
	State                   string
	BaseRefName             string
	MergeCommit             struct{ OID string }
	Repository              struct{ NameWithOwner string }
	ClosingIssuesReferences struct {
		Nodes    []struct{ ID string }
		PageInfo struct{ HasNextPage bool }
	}
}

func (c *Client) linkedReferences(ctx context.Context, number int) (map[int]linkedPR, map[int]bool, error) {
	cursor := any(nil)
	refs := map[int]linkedPR{}
	strong := map[int]bool{}
	fields := `number merged state baseRefName repository{nameWithOwner} mergeCommit{oid} closingIssuesReferences(first:100){nodes{id} pageInfo{hasNextPage}}`
	query := `query($owner:String!,$repo:String!,$number:Int!,$cursor:String){repository(owner:$owner,name:$repo){issue(number:$number){id timelineItems(first:100,after:$cursor,itemTypes:[CROSS_REFERENCED_EVENT,CLOSED_EVENT]){pageInfo{hasNextPage endCursor} nodes{... on CrossReferencedEvent{source{... on PullRequest{` + fields + `}}} ... on ClosedEvent{closer{... on PullRequest{` + fields + `}}}}}}}}`
	for {
		var data struct {
			Repository struct {
				Issue struct {
					ID            string
					TimelineItems struct {
						PageInfo struct {
							HasNextPage bool
							EndCursor   string
						}
						Nodes []struct {
							Source linkedPR
							Closer linkedPR
						}
					}
				}
			}
		}
		parts := strings.Split(c.Repository, "/")
		if err := c.GraphQL(ctx, query, map[string]any{"owner": parts[0], "repo": parts[1], "number": number, "cursor": cursor}, &data); err != nil {
			return nil, nil, err
		}
		items := data.Repository.Issue.TimelineItems
		for _, event := range items.Nodes {
			pr := event.Source
			isCloser := event.Closer.Number > 0
			if isCloser {
				pr = event.Closer
			}
			if pr.Number <= 0 || pr.Repository.NameWithOwner != c.Repository {
				continue
			}
			refs[pr.Number] = pr

			if isCloser {
				strong[pr.Number] = true
			}
			for _, i := range pr.ClosingIssuesReferences.Nodes {
				if i.ID != "" && i.ID == data.Repository.Issue.ID {
					strong[pr.Number] = true
				}
			}
			if !strong[pr.Number] && pr.ClosingIssuesReferences.PageInfo.HasNextPage {
				return nil, nil, fmt.Errorf("PR #%d closing references are incomplete", pr.Number)
			}
		}
		if !items.PageInfo.HasNextPage {
			break
		}
		if items.PageInfo.EndCursor == "" || items.PageInfo.EndCursor == cursor {
			return nil, nil, fmt.Errorf("issue timeline pagination did not advance")
		}
		cursor = items.PageInfo.EndCursor
	}
	return refs, strong, nil
}

func (c *Client) linked(ctx context.Context, number int) ([]docpacket.PR, []int, []int, error) {
	refs, strong, err := c.linkedReferences(ctx, number)
	if err != nil {
		return nil, nil, nil, err
	}
	numbers := []int{}
	for n := range refs {
		numbers = append(numbers, n)
	}
	sort.Ints(numbers)
	selected := []docpacket.PR{}
	excluded := []int{}
	ambiguous := []int{}
	for _, n := range numbers {
		pr := refs[n]
		if !pr.Merged || pr.BaseRefName != "main" {
			continue
		}
		files, err := c.API.List(ctx, fmt.Sprintf("/pulls/%d/files", n), "")
		if err != nil {
			return nil, nil, nil, err
		}
		production := false
		includesTests := false
		for _, raw := range files {
			var file struct {
				Filename string `json:"filename"`
			}
			if err := decodeRaw(raw, &file); err != nil {
				return nil, nil, nil, err
			}
			name := file.Filename
			isTest := strings.HasPrefix(name, "e2e/") || strings.Contains("/"+name, "/src/test/") || strings.Contains("/"+name, "/tests/")
			includesTests = includesTests || isTest
			nonProduct := strings.HasPrefix(name, "help/") || strings.HasSuffix(strings.ToLower(name), ".md") || isTest || strings.HasPrefix(name, ".apm/")
			if !nonProduct {
				production = true
			}
		}
		if !production && !(includesTests && strong[n]) {
			excluded = append(excluded, n)
			continue
		}
		if !strong[n] {
			ambiguous = append(ambiguous, n)
			continue
		}
		selected = append(selected, docpacket.PR{Number: n, MergeSHA: pr.MergeCommit.OID, Target: pr.BaseRefName})
	}
	return selected, excluded, ambiguous, nil
}
func Prepare(ctx context.Context, c *Client, number, projectNumber int) (Prepared, error) {
	out := Prepared{Complete: true, Limitations: []string{}}
	var issue issueInfo
	if err := c.Request(ctx, "GET", fmt.Sprintf("/issues/%d", number), nil, &issue); err != nil {
		return out, err
	}
	if issue.Number != number || issue.NodeID == "" || issue.PullRequest != nil {
		return out, fmt.Errorf("documentation intake requires an issue")
	}
	var repo struct {
		DefaultBranch string `json:"default_branch"`
	}
	if err := c.Request(ctx, "GET", "", nil, &repo); err != nil {
		return out, err
	}
	if repo.DefaultBranch != "main" {
		return out, fmt.Errorf("documentation packet builder currently supports main only")
	}
	sha, err := c.head(ctx, repo.DefaultBranch)
	if err != nil {
		return out, err
	}
	project, status, err := c.project(ctx, issue.NodeID, "", projectNumber)
	if err != nil {
		return out, err
	}
	if status != project.ReviewOptionID {
		return out, errNotInDocReview
	}
	prs, excluded, ambiguous, err := c.linked(ctx, number)
	if err != nil {
		return out, err
	}
	if len(prs) == 0 {
		return out, errNoImplementation
	}
	out.Excluded = excluded
	out.Ambiguous = ambiguous
	if len(ambiguous) > 0 {
		out.Complete = false
		out.Limitations = append(out.Limitations, fmt.Sprintf("Referenced production PRs require relationship review: %v.", ambiguous))
	}
	out.Issue = docpacket.Issue{Number: number, Title: issue.Title, Body: issue.Body, URL: issue.HTMLURL, PRs: prs}
	out.Plan = Plan{Version: "docs-plan-v2", IssueState: issue.State, Repository: c.Repository, IssueNumber: number, IssueNodeID: issue.NodeID, IssueTitle: issue.Title, IssueBodyHash: Digest(issue.Body), DefaultBranch: repo.DefaultBranch, BaseSHA: sha, Project: project, ImplementingPRs: prs}
	out.Plan.Project.Number = projectNumber
	out.Plan.ID = Digest(out.Plan)
	return out, out.Plan.Validate()
}
func Revalidate(ctx context.Context, c *Client, p Plan) (Current, error) {
	return revalidate(ctx, c, p, "")
}
func RevalidateMerged(ctx context.Context, c *Client, p Plan, mergeSHA string) (Current, error) {
	return revalidate(ctx, c, p, mergeSHA)
}
func revalidate(ctx context.Context, c *Client, p Plan, ancestorSHA string) (Current, error) {
	var current Current
	if err := p.Validate(); err != nil {
		return current, err
	}
	if p.Repository != c.Repository {
		return current, fmt.Errorf("repository changed")
	}
	var issue issueInfo
	if err := c.Request(ctx, "GET", fmt.Sprintf("/issues/%d", p.IssueNumber), nil, &issue); err != nil {
		return current, err
	}
	if issue.Number != p.IssueNumber || issue.NodeID != p.IssueNodeID || issue.PullRequest != nil {
		return current, fmt.Errorf("issue identity changed")
	}
	if issue.Title != p.IssueTitle || Digest(issue.Body) != p.IssueBodyHash {
		return current, fmt.Errorf("issue requirements changed")
	}
	current.IssueState = issue.State
	if p.IssueState == "closed" && issue.State != "closed" {
		return current, fmt.Errorf("issue reopened after documentation preparation")
	}
	project, status, err := c.project(ctx, p.IssueNodeID, p.Project.ID, p.Project.Number)
	if err != nil {
		return current, err
	}
	if project.ItemID != p.Project.ItemID || project.StatusFieldID != p.Project.StatusFieldID || project.ReviewOptionID != p.Project.ReviewOptionID || project.DoneOptionID != p.Project.DoneOptionID {
		return current, fmt.Errorf("project configuration changed")
	}
	current.ProjectStatus = status
	if status != p.Project.ReviewOptionID && status != p.Project.DoneOptionID {
		return current, fmt.Errorf("issue moved out of documentation review")
	}
	sha, err := c.head(ctx, p.DefaultBranch)
	if err != nil {
		return current, err
	}
	current.BaseSHA = sha
	if ancestorSHA == "" {
		if sha != p.BaseSHA {
			return current, errStaleBase
		}
	} else {
		var compare struct {
			Status string `json:"status"`
		}
		if err := c.Request(ctx, "GET", "/compare/"+url.PathEscape(ancestorSHA)+"..."+url.PathEscape(sha), nil, &compare); err != nil {
			return current, err
		}
		if compare.Status != "ahead" && compare.Status != "identical" {
			return current, fmt.Errorf("commit %s is not on the default branch", ancestorSHA)
		}
	}
	prs, _, ambiguous, err := c.linked(ctx, p.IssueNumber)
	if err != nil {
		return current, err
	}
	if len(ambiguous) > 0 || Digest(prs) != Digest(p.ImplementingPRs) {
		return current, fmt.Errorf("implementing pull requests changed or require review")
	}
	return current, nil
}
