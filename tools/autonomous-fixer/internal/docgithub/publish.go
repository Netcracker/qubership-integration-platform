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
	Actor      string           `json:"actor"`
	Apply      bool             `json:"apply"`
	SourcePath string           `json:"source_path,omitempty"`
	Attempt    ScheduledAttempt `json:"-"`
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
	BranchWrite             string `json:"branch_write,omitempty"`
	PRAttempted             bool   `json:"pr_attempted,omitempty"`
	PRNumber                int    `json:"pr_number,omitempty"`
	PRURL                   string `json:"pr_url,omitempty"`
	CommentID               int64  `json:"comment_id,omitempty"`
	IssueComment            string `json:"issue_comment,omitempty"`
	RunSignature            string `json:"run_signature,omitempty"`
	WorkflowAttempt         int    `json:"workflow_attempt,omitempty"`
	Applied                 bool   `json:"applied"`
}
type publisher struct {
	client       *Client
	plan         Plan
	state        Publication
	author       int64
	runDir       string
	attempt      ScheduledAttempt
	claim        issueComment
	stateComment issueComment
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

func authorizePublisher(ctx context.Context, c *Client) (fixer.GitHubUser, error) {
	publisherLogin, err := c.API.RepositoryVariable(ctx, "AGENT_DOCS_PUBLISHER_LOGIN")
	if err != nil {
		return fixer.GitHubUser{}, err
	}
	identity, err := c.ActorIdentity(ctx)
	if err != nil {
		return fixer.GitHubUser{}, err
	}
	if publisherLogin == "" || !strings.EqualFold(identity.Login, publisherLogin) {
		return fixer.GitHubUser{}, fmt.Errorf("authenticated publisher does not match AGENT_DOCS_PUBLISHER_LOGIN")
	}
	return identity, nil
}

func marker(p Plan) string { return "<!-- qip-docs:" + p.ID + " -->" }

const (
	branchReady     = "ready"
	branchAttempted = "attempted"
	branchObserved  = "observed"
	legacyObserved  = "legacy-observed"
	staleAssessment = "The default branch changed after documentation review. Start a fresh assessment with **QIP documentation agent** for this issue, with `operation` set to `run` and `apply` enabled."
)

func (p *publisher) documentationBranch() string {
	return fmt.Sprintf("agent/docs/%d/%s", p.plan.IssueNumber, p.plan.ID[:12])
}

func (state Publication) terminal() bool {
	return state.Status == "completed" || state.Status == "superseded"
}

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
	p.claim = issueComment{}
	p.stateComment = issueComment{}
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
			if p.claim.ID != 0 {
				return false, fmt.Errorf("duplicate scheduled documentation claim")
			}
			p.claim = comment
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
		p.stateComment = comment
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
func (p *publisher) publicationBody(state Publication, current ScheduledAttempt) string {
	body := marker(p.plan) + "\n" + agentsignature.Strip(state.IssueComment)
	if state.Decision != "no_change" || state.Status != "completed" {
		body += "\n\nDocumentation status: **" + state.Status + "**."
	}
	if state.PRURL != "" {
		body += " [Draft documentation PR](" + state.PRURL + ")."
	}
	if attempt, ok := p.plan.workflowAttempt(); ok {
		attempt.Attempt = max(attempt.Attempt, state.WorkflowAttempt)
		if current.RunID == attempt.RunID {
			attempt.Attempt = max(attempt.Attempt, current.Attempt)
		}
		body += "\n\n[View workflow run](" + attempt.url(p.plan.Repository) + ")"
	}
	return body
}

func (p *publisher) saveState(ctx context.Context, state Publication) error {
	state.PlanID = p.plan.ID
	state.Plan = &p.plan
	state.Applied = true
	if first, ok := p.plan.workflowAttempt(); ok && p.attempt.RunID == first.RunID && p.attempt.Attempt > first.Attempt {
		state.WorkflowAttempt = max(state.WorkflowAttempt, p.attempt.Attempt)
	}
	encoded, _ := json.Marshal(state)
	body := p.publicationBody(state, ScheduledAttempt{})
	body += "\n\n<!-- qip-docs-state:" + base64.StdEncoding.EncodeToString(encoded) + " -->" + stateFooter(state)
	if len(body) > 65536 {
		return fmt.Errorf("documentation state exceeds GitHub comment size")
	}
	method, path := "POST", fmt.Sprintf("/issues/%d/comments", p.plan.IssueNumber)
	action := "create-state-comment"
	commentID := p.state.CommentID
	if commentID == 0 {
		commentID = p.claim.ID
	}
	if commentID > 0 {
		method = "PATCH"
		path = fmt.Sprintf("/issues/comments/%d", commentID)
		action = fmt.Sprintf("update-state-%s-%s", state.Status, Digest(state))
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
	p := &publisher{client: c, plan: plan, runDir: runDir, attempt: options.Attempt}
	p.state = Publication{PlanID: plan.ID, Status: "planned"}
	if c.Repository != plan.Repository {
		return p.state, fmt.Errorf("publication repository mismatch")
	}
	candidate, e := ValidateCandidate(ctx, plan, runDir, options.SourcePath)
	if e != nil {
		return p.state, publicationFailed(PublicationFailedCandidate, e)
	}
	p.state.CandidateDigest = candidate.Digest
	p.state.Decision = candidate.Report.Documentation.Decision
	p.state.IssueComment = agentsignature.Strip(candidate.Report.Documentation.IssueComment)
	if !options.Apply {
		_, e = Revalidate(ctx, c, plan)
		return p.state, e
	}
	if e = Authorize(ctx, c, options.Actor); e != nil {
		return p.state, publicationFailed(PublicationFailedAccess, e)
	}
	found, e := p.readState(ctx)
	if e != nil {
		return p.state, publicationFailed(PublicationFailedReadState, e)
	}
	if found && (p.state.CandidateDigest != candidate.Digest || p.state.Decision != candidate.Report.Documentation.Decision) {
		return p.state, fmt.Errorf("publication state refers to a different candidate")
	}
	if p.state.Status == "superseded" {
		return p.state, errStaleBase
	}
	current, e := p.revalidate(ctx, "")
	if found && errors.Is(e, errStaleBase) {
		return p.recoverStalePublication(ctx)
	}
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
			if active && !other.state.terminal() {
				if _, err := other.supersedeStale(ctx, ""); err != nil {
					return p.state, err
				}
				if !other.state.terminal() {
					return p.state, fmt.Errorf("another documentation plan is active; reconcile it first")
				}
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
			return p.state, publicationFailed(PublicationFailedSaveState, e)
		}
	}
	if candidate.Report.Status == "docs-no-change" {
		e = p.complete(ctx, "")
	} else {
		e = publicationFailed(PublicationFailedDraftPR, p.publishPR(ctx, candidate))
	}
	if errors.Is(e, errStaleBase) {
		return p.recoverStalePublication(ctx)
	}
	return p.state, e
}
func (p *publisher) publishPR(ctx context.Context, candidate Candidate) error {
	state := p.state
	state.Branch = p.documentationBranch()
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
		state.BranchWrite = branchReady
		if e := p.saveState(ctx, state); e != nil {
			return e
		}
		state = p.state
	}
	head, err := p.client.head(ctx, state.Branch)
	if notFound(err) {
		if state.BranchWrite != branchReady || state.PRAttempted {
			return fmt.Errorf("documentation branch %s is missing after a prior write; inspect its GitHub history", state.Branch)
		}
		if head, err = p.createDocumentationBranch(ctx); err != nil {
			return err
		}
		state = p.state
	} else if err != nil {
		return err
	}
	if head != state.CommitSHA {
		return fmt.Errorf("existing documentation branch has an unexpected head")
	}
	if state.BranchWrite == branchReady || state.BranchWrite == branchAttempted {
		state.BranchWrite = branchObserved
		if e := p.saveState(ctx, state); e != nil {
			return e
		}
		state = p.state
	}
	prNumber, e := p.findDocumentationPR(ctx)
	if e != nil {
		return e
	}
	var pr pullRequest
	if prNumber == 0 {
		if state.PRAttempted || state.BranchWrite != branchObserved {
			return fmt.Errorf("documentation PR creation is unresolved for branch %s", state.Branch)
		}
		body := agentsignature.Strip(candidate.Report.Documentation.PRBody) + fmt.Sprintf("\n\nRelated #%d\n\n%s", p.plan.IssueNumber, marker(p.plan))
		if state.RunSignature != "" {
			body += "\n\n" + state.RunSignature
		}
		if e = validateDocumentationPRBody(body); e != nil {
			return e
		}
		prNumber, e = p.createDocumentationPR(ctx, candidate.Report.Documentation.PRTitle, body)
		if e != nil {
			return e
		}
		state = p.state
	}
	if e = p.client.Request(ctx, "GET", fmt.Sprintf("/pulls/%d", prNumber), nil, &pr); e != nil {
		return e
	}
	if pr.Number != prNumber || pr.Head.SHA != state.CommitSHA || pr.Head.Ref != state.Branch || pr.Base.Ref != p.plan.DefaultBranch ||
		pr.Head.Repo.FullName != p.plan.Repository || pr.Base.Repo.FullName != p.plan.Repository || !strings.Contains(pr.Body, marker(p.plan)) {
		return fmt.Errorf("existing PR does not match publication intent")
	}
	if e = validateDocumentationPRBody(pr.Body); e != nil {
		return e
	}
	if pr.State != "open" {
		return fmt.Errorf("documentation PR is not open; reconcile its outcome")
	}
	return p.saveState(ctx, awaitingMerge(state, pr))
}

func awaitingMerge(state Publication, pr pullRequest) Publication {
	state.PRNumber, state.PRURL, state.Status = pr.Number, pr.HTMLURL, "awaiting-merge"
	if state.IssueComment == "" {
		state.IssueComment = "A documentation update is ready for review. The documentation task will complete after the pull request is merged."
	}
	return state
}

func (p *publisher) findDocumentationPR(ctx context.Context) (int, error) {
	owner, _, _ := strings.Cut(p.plan.Repository, "/")
	items, err := p.client.API.List(ctx, "/pulls?state=all&head="+url.QueryEscape(owner+":"+p.documentationBranch()), "")
	if err != nil || len(items) == 0 {
		return 0, err
	}
	if len(items) != 1 {
		return 0, fmt.Errorf("duplicate documentation pull requests")
	}
	var pr pullRequest
	if json.Unmarshal(items[0], &pr) != nil || pr.Number <= 0 {
		return 0, fmt.Errorf("invalid documentation PR response")
	}
	return pr.Number, nil
}

func (p *publisher) createDocumentationBranch(ctx context.Context) (string, error) {
	const action = "create-docs-branch"
	if err := p.begin(action); err != nil {
		return "", err
	}
	state := p.state
	before := state.BranchWrite
	state.BranchWrite = branchAttempted
	if err := p.saveState(ctx, state); err != nil {
		return "", err
	}
	if err := p.revalidateBeforePublicationWrite(ctx); err != nil {
		state.BranchWrite = before
		return "", errors.Join(err, p.saveState(ctx, state), p.journal(action, "skipped"))
	}
	requestErr := p.client.Request(ctx, "POST", "/git/refs", map[string]string{"ref": "refs/heads/" + state.Branch, "sha": state.CommitSHA}, nil)
	head, err := p.client.head(ctx, state.Branch)
	if notFound(err) && fixer.GitHubWriteRejected(requestErr) {
		state.BranchWrite = before
		return "", errors.Join(fmt.Errorf("documentation branch creation was rejected: %w", requestErr), p.saveState(ctx, state), p.journal(action, "rejected"))
	}
	if err != nil {
		return "", fmt.Errorf("branch creation result is uncertain: %v; readback: %w", requestErr, err)
	}
	if head != state.CommitSHA {
		return "", fmt.Errorf("branch readback differs")
	}
	return head, p.journal(action, "observed")
}

func (p *publisher) createDocumentationPR(ctx context.Context, title, body string) (int, error) {
	const action = "create-docs-pr"
	if err := p.begin(action); err != nil {
		return 0, err
	}
	state := p.state
	state.PRAttempted = true
	if err := p.saveState(ctx, state); err != nil {
		return 0, err
	}
	if err := p.revalidateBeforePublicationWrite(ctx); err != nil {
		state.PRAttempted = false
		return 0, errors.Join(err, p.saveState(ctx, state), p.journal(action, "skipped"))
	}
	requestErr := p.client.Request(ctx, "POST", "/pulls", map[string]any{"title": title, "body": body, "head": state.Branch, "base": p.plan.DefaultBranch, "draft": true}, nil)
	number, err := p.findDocumentationPR(ctx)
	if err != nil {
		return 0, err
	}
	if number == 0 {
		if fixer.GitHubWriteRejected(requestErr) {
			state.PRAttempted = false
			return 0, errors.Join(fmt.Errorf("documentation PR creation was rejected: %w", requestErr), p.saveState(ctx, state), p.journal(action, "rejected"))
		}
		return 0, fmt.Errorf("PR creation result is uncertain: %v", requestErr)
	}
	return number, p.journal(action, "observed")
}

func (p *publisher) revalidateBeforePublicationWrite(ctx context.Context) error {
	current, err := Revalidate(ctx, p.client, p.plan)
	if err != nil {
		return err
	}
	if current.ProjectStatus != p.plan.Project.ReviewOptionID || current.IssueState != p.state.InitialIssueState {
		return fmt.Errorf("issue or project status changed during documentation publication")
	}
	return nil
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
