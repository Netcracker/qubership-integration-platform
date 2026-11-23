package docgithub

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentsignature"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

type PublishOptions struct {
	Actor      string `json:"actor"`
	Apply      bool   `json:"apply"`
	SourcePath string `json:"source_path,omitempty"`
}
type Publication struct {
	MergedHeadSHA           string `json:"merged_head_sha,omitempty"`
	UpdatedAfterPublication bool   `json:"updated_after_publication"`
	Decision                string `json:"decision"`
	Plan                    *Plan  `json:"plan,omitempty"`
	InitialIssueState       string `json:"initial_issue_state,omitempty"`
	CloseAttempted          bool   `json:"close_attempted,omitempty"`
	ProjectDoneAttempted    bool   `json:"project_done_attempted,omitempty"`
	PlanID                  string `json:"plan_id"`
	CandidateDigest         string `json:"candidate_digest,omitempty"`
	Status                  string `json:"status"`
	Branch                  string `json:"branch,omitempty"`
	CommitSHA               string `json:"commit_sha,omitempty"`
	PRNumber                int    `json:"pr_number,omitempty"`
	PRURL                   string `json:"pr_url,omitempty"`
	CommentID               int64  `json:"comment_id,omitempty"`
	IssueComment            string `json:"issue_comment,omitempty"`
	RunSignature            string `json:"run_signature,omitempty"`
	Applied                 bool   `json:"applied"`
}
type publisher struct {
	client  *Client
	plan    Plan
	state   Publication
	author  int64
	runDir  string
	claimID int64
}
type issueComment struct {
	ID   int64            `json:"id"`
	Body string           `json:"body"`
	User fixer.GitHubUser `json:"user"`
}

func AuthorizeActor(ctx context.Context, c *Client, actors ...string) error {
	if err := authorizeDocsEnabled(ctx, c); err != nil {
		return err
	}
	allowed, err := c.API.RepositoryVariable(ctx, "AGENT_ALLOWED_ACTORS")
	if err != nil {
		return err
	}
	if !fixer.Allowed(allowed, actors...) {
		return fmt.Errorf("documentation operator is outside AGENT_ALLOWED_ACTORS")
	}
	return nil
}

func authorizeDocsEnabled(ctx context.Context, c *Client) error {
	enabled, err := c.API.RepositoryVariable(ctx, "AGENT_DOCS_ENABLED")
	if err != nil {
		return err
	}
	if enabled != "true" {
		return fmt.Errorf("AGENT_DOCS_ENABLED must be true")
	}
	return nil
}

func Authorize(ctx context.Context, c *Client, actor string) error {
	if err := AuthorizeActor(ctx, c, actor); err != nil {
		return err
	}
	_, err := authorizePublisher(ctx, c)
	return err
}

func authorizePublisher(ctx context.Context, c *Client) (string, error) {
	publisherLogin, err := c.API.RepositoryVariable(ctx, "AGENT_DOCS_PUBLISHER_LOGIN")
	if err != nil {
		return "", err
	}
	identity, err := c.ActorIdentity(ctx)
	if err != nil {
		return "", err
	}
	if publisherLogin == "" || !strings.EqualFold(identity.Login, publisherLogin) {
		return "", fmt.Errorf("authenticated publisher does not match AGENT_DOCS_PUBLISHER_LOGIN")
	}
	return publisherLogin, nil
}

func marker(p Plan) string { return "<!-- qip-docs:" + p.ID + " -->" }

func stateFooter(state Publication) string {
	if state.Decision == "no_change" && state.RunSignature != "" {
		return "\n\n" + state.RunSignature
	}
	return ""
}

func decodePublicationState(body string) (Publication, error) {
	var state Publication
	const prefix = "<!-- qip-docs-state:"
	start := strings.LastIndex(body, prefix)
	if start < 0 {
		return state, fmt.Errorf("documentation state comment is missing its state marker")
	}
	encoded, tail, ended := strings.Cut(body[start+len(prefix):], "-->")
	if !ended {
		return state, fmt.Errorf("documentation state marker is incomplete")
	}
	data, err := base64.StdEncoding.DecodeString(strings.TrimSpace(encoded))
	if err != nil || json.Unmarshal(data, &state) != nil {
		return state, fmt.Errorf("invalid documentation state")
	}
	if strings.TrimRight(tail, " \t\r\n") != stateFooter(state) {
		return state, fmt.Errorf("documentation state footer differs from the recorded signature")
	}
	return state, nil
}

func (p *publisher) readState(ctx context.Context) (bool, error) {
	var viewer struct {
		Viewer struct {
			DatabaseID int64  `json:"databaseId"`
			Login      string `json:"login"`
		} `json:"viewer"`
	}
	if e := p.client.GraphQL(ctx, "query { viewer { databaseId login } }", nil, &viewer); e != nil {
		return false, e
	}
	if viewer.Viewer.DatabaseID <= 0 {
		return false, fmt.Errorf("cannot identify publication account")
	}
	p.author = viewer.Viewer.DatabaseID
	comments, e := p.client.API.List(ctx, fmt.Sprintf("/issues/%d/comments", p.plan.IssueNumber), "")
	if e != nil {
		return false, e
	}
	found := false
	p.claimID = 0
	claim := ""
	if attempt, ok := p.plan.workflowAttempt(); ok {
		claim = scheduleReceipt(scheduleBasis(p.plan), attempt) + "\n"
	}
	for _, raw := range comments {
		var comment issueComment
		if e = json.Unmarshal(raw, &comment); e != nil {
			return false, e
		}
		if claim != "" && comment.User.ID == p.author && strings.HasPrefix(comment.Body, claim) {
			if p.claimID != 0 {
				return false, fmt.Errorf("duplicate scheduled documentation claim")
			}
			p.claimID = comment.ID
			continue
		}
		if !strings.HasPrefix(comment.Body, marker(p.plan)+"\n") {
			continue
		}
		if comment.User.ID != p.author {
			return false, fmt.Errorf("foreign comment uses the documentation state marker")
		}
		if found {
			return false, fmt.Errorf("duplicate documentation state comments")
		}
		state, e := decodePublicationState(comment.Body)
		if e != nil {
			return false, e
		}
		if state.PlanID != p.plan.ID || state.Plan == nil || state.Plan.Validate() != nil || Digest(*state.Plan) != Digest(p.plan) {
			return false, fmt.Errorf("documentation state belongs to another plan")
		}
		state.CommentID = comment.ID
		p.state = state
		found = true
	}
	return found, nil
}
func (p *publisher) journal(action, status string) error {
	if p.runDir == "" {
		return nil
	}
	f, e := os.OpenFile(filepath.Join(p.runDir, "publication-journal.jsonl"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
	if e != nil {
		return e
	}
	defer f.Close()
	return json.NewEncoder(f).Encode(map[string]string{"action": action, "status": status, "at": time.Now().UTC().Format(time.RFC3339Nano)})
}
func (p *publisher) begin(action string) error {
	if p.runDir != "" {
		b, e := os.ReadFile(filepath.Join(p.runDir, "publication-journal.jsonl"))
		if e != nil && !os.IsNotExist(e) {
			return e
		}
		pending := false
		for _, line := range strings.Split(string(b), "\n") {
			if line == "" {
				continue
			}
			var row map[string]string
			if json.Unmarshal([]byte(line), &row) != nil {
				return fmt.Errorf("invalid publication journal")
			}
			if row["action"] == action {
				pending = row["status"] == "pending"
			}
		}
		if pending {
			return fmt.Errorf("publication action %s has an uncertain prior result; recover by readback", action)
		}
	}
	return p.journal(action, "pending")
}
func (p *publisher) saveState(ctx context.Context, state Publication) error {
	state.PlanID = p.plan.ID
	state.Plan = &p.plan
	state.Applied = true
	encoded, _ := json.Marshal(state)
	body := marker(p.plan) + "\n" + agentsignature.Strip(state.IssueComment)
	if state.Decision != "no_change" || state.Status != "completed" {
		body += "\n\nDocumentation status: **" + state.Status + "**."
	}
	if state.PRURL != "" {
		body += " [Draft documentation PR](" + state.PRURL + ")."
	}
	if attempt, ok := p.plan.workflowAttempt(); ok {
		body += "\n\n[View workflow run](" + attempt.url(p.plan.Repository) + ")"
	}
	body += "\n\n<!-- qip-docs-state:" + base64.StdEncoding.EncodeToString(encoded) + " -->" + stateFooter(state)
	if len(body) > 65536 {
		return fmt.Errorf("documentation state exceeds GitHub comment size")
	}
	method, path := "POST", fmt.Sprintf("/issues/%d/comments", p.plan.IssueNumber)
	action := "create-state-comment"
	commentID := p.state.CommentID
	if commentID == 0 {
		commentID = p.claimID
	}
	if commentID > 0 {
		method = "PATCH"
		path = fmt.Sprintf("/issues/comments/%d", commentID)
		action = fmt.Sprintf("update-state-%s-%s-%d", state.Status, state.CommitSHA, state.PRNumber)
	}
	if p.state.CommentID > 0 && samePublicationState(p.state, state) {
		return p.journal(action, "observed")
	}
	if e := p.begin(action); e != nil {
		return e
	}
	var response issueComment
	requestErr := p.client.Request(ctx, method, path, map[string]string{"body": body}, &response)
	found, e := p.readState(ctx)
	if e != nil {
		return e
	}
	if !found || !samePublicationState(p.state, state) {
		if requestErr != nil {
			return fmt.Errorf("state update result is uncertain: %w", requestErr)
		}
		return fmt.Errorf("documentation state readback differs")
	}
	return p.journal(action, "observed")
}

func samePublicationState(a, b Publication) bool {
	// readState validates the plan and takes the comment ID from GitHub.
	a.Plan, b.Plan = nil, nil
	a.CommentID, b.CommentID = 0, 0
	return a == b
}

func notFound(err error) bool { var h *fixer.HTTPError; return errors.As(err, &h) && h.Status == 404 }

func documentationSignature(runDir string, candidate Candidate) (agentsignature.Signature, error) {
	if candidate.Report.Request.DocumentationEntry.Route == "docs-no-change" && candidate.jev != nil {
		return agentsignature.FromJev(*candidate.jev), nil
	}
	return agentsignature.ReadRun(runDir)
}

func Publish(ctx context.Context, c *Client, plan Plan, runDir string, options PublishOptions) (Publication, error) {
	p := &publisher{client: c, plan: plan, runDir: runDir}
	p.state = Publication{PlanID: plan.ID, Status: "planned"}
	if c.Repository != plan.Repository {
		return p.state, fmt.Errorf("publication repository mismatch")
	}
	candidate, e := ValidateCandidate(ctx, plan, runDir, options.SourcePath)
	if e != nil {
		return p.state, e
	}
	p.state.CandidateDigest = candidate.Digest
	p.state.Decision = candidate.Report.Documentation.Decision
	p.state.IssueComment = agentsignature.Strip(candidate.Report.Documentation.IssueComment)
	if !options.Apply {
		_, e = Revalidate(ctx, c, plan)
		return p.state, e
	}
	if e = Authorize(ctx, c, options.Actor); e != nil {
		return p.state, e
	}
	found, e := p.readState(ctx)
	if e != nil {
		return p.state, e
	}
	if found && (p.state.CandidateDigest != candidate.Digest || p.state.Decision != candidate.Report.Documentation.Decision) {
		return p.state, fmt.Errorf("publication state refers to a different candidate")
	}
	current, e := p.revalidate(ctx, "")
	if e != nil {
		return p.state, e
	}
	if current.ProjectStatus == plan.Project.DoneOptionID {
		if found && p.state.Status == "completed" && current.IssueState == "closed" {
			return p.state, nil
		}
		if !found || !p.state.ProjectDoneAttempted && !(p.state.CloseAttempted && current.IssueState == "closed") {
			return p.state, fmt.Errorf("project already left documentation review without this completion")
		}
	}
	if found && p.state.Status == "completed" {
		return p.state, fmt.Errorf("completed documentation task was reopened or moved back; prepare a new plan")
	}
	if !found {
		existing, err := RecoverPlan(ctx, c, plan.IssueNumber)
		if err != nil && !errors.Is(err, ErrNoPublication) {
			return p.state, err
		}
		if err == nil && existing.ID != plan.ID {
			other := &publisher{client: c, plan: existing}
			active, e := other.readState(ctx)
			if e != nil {
				return p.state, e
			}
			if active && other.state.Status != "completed" {
				return p.state, fmt.Errorf("another documentation plan is active; reconcile it first")
			}
		}
		signature, err := documentationSignature(runDir, candidate)
		if err != nil {
			return p.state, fmt.Errorf("read documentation run usage: %w", err)
		}
		p.state.InitialIssueState = current.IssueState
		p.state.Status = "publishing"
		p.state.RunSignature = signature.Line()
		if e = p.saveState(ctx, p.state); e != nil {
			return p.state, e
		}
	}
	if candidate.Report.Status == "docs-no-change" {
		e = p.complete(ctx, "")
		return p.state, e
	}
	e = p.publishPR(ctx, candidate)
	return p.state, e
}
func (p *publisher) publishPR(ctx context.Context, candidate Candidate) error {
	state := p.state
	state.Branch = fmt.Sprintf("agent/docs/%d/%s", p.plan.IssueNumber, p.plan.ID[:12])
	if state.CommitSHA == "" {
		var base struct {
			Tree struct {
				SHA string `json:"sha"`
			} `json:"tree"`
		}
		if e := p.client.Request(ctx, "GET", "/git/commits/"+p.plan.BaseSHA, nil, &base); e != nil {
			return e
		}
		if len(base.Tree.SHA) != 40 {
			return fmt.Errorf("GitHub returned no base tree identity")
		}
		entries := []map[string]any{}
		for _, file := range candidate.Files {
			entry := map[string]any{"path": file.Path, "mode": "100644", "type": "blob"}
			if file.Deleted {
				entry["sha"] = nil
			} else {
				var blob struct {
					SHA string `json:"sha"`
				}
				if e := p.client.Request(ctx, "POST", "/git/blobs", map[string]string{"content": base64.StdEncoding.EncodeToString(file.Content), "encoding": "base64"}, &blob); e != nil {
					return e
				}
				if blob.SHA == "" {
					return fmt.Errorf("GitHub returned no blob identity")
				}
				entry["sha"] = blob.SHA
			}
			entries = append(entries, entry)
		}
		var tree struct {
			SHA string `json:"sha"`
		}
		if e := p.client.Request(ctx, "POST", "/git/trees", map[string]any{"base_tree": base.Tree.SHA, "tree": entries}, &tree); e != nil {
			return e
		}
		if len(tree.SHA) != 40 {
			return fmt.Errorf("GitHub returned no tree identity")
		}
		var commit struct {
			SHA string `json:"sha"`
		}
		if e := p.client.Request(ctx, "POST", "/git/commits", map[string]any{"message": candidate.Report.Documentation.PRTitle, "tree": tree.SHA, "parents": []string{p.plan.BaseSHA}}, &commit); e != nil {
			return e
		}
		if commit.SHA == "" {
			return fmt.Errorf("GitHub returned no commit identity")
		}
		state.CommitSHA = commit.SHA
		if e := p.saveState(ctx, state); e != nil {
			return e
		}
		state = p.state
	}
	var ref struct {
		Object struct {
			SHA string `json:"sha"`
		} `json:"object"`
	}
	path := "/git/ref/heads/" + state.Branch
	err := p.client.Request(ctx, "GET", path, nil, &ref)
	if notFound(err) {
		action := "create-docs-branch"
		if e := p.begin(action); e != nil {
			return e
		}
		createErr := p.client.Request(ctx, "POST", "/git/refs", map[string]string{"ref": "refs/heads/" + state.Branch, "sha": state.CommitSHA}, nil)
		if e := p.client.Request(ctx, "GET", path, nil, &ref); e != nil {
			return fmt.Errorf("branch creation result is uncertain: %v; readback: %w", createErr, e)
		}
		if ref.Object.SHA != state.CommitSHA {
			return fmt.Errorf("branch readback differs")
		}
		if e := p.journal(action, "observed"); e != nil {
			return e
		}
	} else if err != nil {
		return err
	}
	if ref.Object.SHA != state.CommitSHA {
		return fmt.Errorf("existing documentation branch has an unexpected head")
	}
	owner := strings.Split(p.plan.Repository, "/")[0]
	items, e := p.client.API.List(ctx, "/pulls?state=all&head="+url.QueryEscape(owner+":"+state.Branch)+"&base="+url.QueryEscape(p.plan.DefaultBranch), "")
	if e != nil {
		return e
	}
	var pr pullRequest
	for _, raw := range items {
		var candidatePR pullRequest
		if json.Unmarshal(raw, &candidatePR) != nil {
			return fmt.Errorf("invalid PR response")
		}
		if pr.Number != 0 {
			return fmt.Errorf("duplicate documentation pull requests")
		}
		pr = candidatePR
	}
	if pr.Number == 0 {
		body := agentsignature.Strip(candidate.Report.Documentation.PRBody) + fmt.Sprintf("\n\nRelated #%d\n\n%s", p.plan.IssueNumber, marker(p.plan))
		if state.RunSignature != "" {
			body += "\n\n" + state.RunSignature
		}
		if e = validateDocumentationPRBody(body); e != nil {
			return e
		}
		action := "create-docs-pr"
		if e = p.begin(action); e != nil {
			return e
		}
		createErr := p.client.Request(ctx, "POST", "/pulls", map[string]any{"title": candidate.Report.Documentation.PRTitle, "body": body, "head": state.Branch, "base": p.plan.DefaultBranch, "draft": true}, &pr)
		items, e = p.client.API.List(ctx, "/pulls?state=all&head="+url.QueryEscape(owner+":"+state.Branch)+"&base="+url.QueryEscape(p.plan.DefaultBranch), "")
		if e != nil {
			return e
		}
		if len(items) != 1 || json.Unmarshal(items[0], &pr) != nil {
			return fmt.Errorf("PR creation result is uncertain: %v", createErr)
		}
		if e = p.journal(action, "observed"); e != nil {
			return e
		}
	}
	if pr.Head.SHA != state.CommitSHA || pr.Head.Ref != state.Branch || pr.Base.Ref != p.plan.DefaultBranch || !strings.Contains(pr.Body, marker(p.plan)) {
		return fmt.Errorf("existing PR does not match publication intent")
	}
	if e = validateDocumentationPRBody(pr.Body); e != nil {
		return e
	}
	if pr.State != "open" {
		return fmt.Errorf("documentation PR is not open; reconcile its outcome")
	}
	state.PRNumber = pr.Number
	state.PRURL = pr.HTMLURL
	state.Status = "awaiting-merge"
	if state.IssueComment == "" {
		state.IssueComment = "A documentation update is ready for review. The documentation task will complete after the pull request is merged."
	}
	return p.saveState(ctx, state)
}

var closingKeyword = regexp.MustCompile(`(?i)\b(?:close[sd]?|fix(?:es|ed)?|resolve[sd]?)(?:\s*:\s*|\s+)(?:#[0-9]+|[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+#[0-9]+|https://github\.com/[^\s]+/issues/[0-9]+)`)

func hasClosingKeyword(body string) bool {
	return closingKeyword.MatchString(body)
}

func validateDocumentationPRBody(body string) error {
	if hasClosingKeyword(body) {
		return fmt.Errorf("documentation PR contains an issue-closing keyword")
	}
	return nil
}
