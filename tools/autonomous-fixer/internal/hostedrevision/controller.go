package hostedrevision

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"strings"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentpr"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

var ErrClosed = errors.New("the pull request closed before publication")
var ErrStale = errors.New("the pull request head or target changed")
var ErrUnauthorized = errors.New("the revision is no longer authorized")
var ErrAlreadyActive = errors.New("another revision is already running for this pull request")

type Config struct {
	Client, Variables                           *fixer.GitHubClient
	Repository, Ref, Event, Actor, TriggerActor string
	WorkflowSHA                                 string
	Run                                         fixer.RunIdentity
	Payload                                     fixer.GitHubPayload
}

type Controller struct {
	cfg        Config
	repo       fixer.Repository
	bot        fixer.GitHubUser
	pullNumber int64
	commentID  int64
	automatic  bool
}

type Assignment struct {
	RepositoryID  int64             `json:"repositoryId"`
	PullID        int64             `json:"pullId"`
	PullNumber    int64             `json:"pullNumber"`
	Issue         int64             `json:"issue"`
	Branch        string            `json:"branch"`
	Head          string            `json:"head"`
	CommentID     int64             `json:"commentId"`
	Actor         fixer.GitHubUser  `json:"actor"`
	CreatedAt     string            `json:"createdAt"`
	CommandHash   string            `json:"commandHash"`
	WorkflowSHA   string            `json:"workflowSha"`
	Run           fixer.RunIdentity `json:"run"`
	OriginRun     fixer.RunIdentity `json:"originRun"`
	Feedback      []Feedback        `json:"feedback"`
	Instruction   string            `json:"instruction,omitempty"`
	Automatic     bool              `json:"automatic,omitempty"`
	ReviewIDs     []int64           `json:"reviewIds,omitempty"`
	FailureKeys   []string          `json:"failureKeys,omitempty"`
	checkpointSHA string
}

func Open(ctx context.Context, cfg Config) (*Controller, error) {
	p := cfg.Payload
	if cfg.Client == nil || cfg.Variables == nil || p.Repository.ID <= 0 || p.Repository.FullName != cfg.Repository ||
		cfg.Run.RunID <= 0 || cfg.Run.RunAttempt < 1 || !commitSHA.MatchString(cfg.WorkflowSHA) {
		return nil, ErrUnauthorized
	}
	c := &Controller{cfg: cfg}
	switch cfg.Event {
	case "issue_comment":
		if p.Action != "created" || p.Issue.Number <= 0 || len(p.Issue.PullRequest) == 0 || string(p.Issue.PullRequest) == "null" ||
			p.Comment.ID <= 0 || p.Comment.User.ID <= 0 || p.Comment.User.Type != "User" || p.Comment.User != p.Sender ||
			!strings.EqualFold(p.Comment.User.Login, cfg.Actor) || !strings.EqualFold(p.Comment.User.Login, cfg.TriggerActor) {
			return nil, ErrUnauthorized
		}
		c.pullNumber, c.commentID = p.Issue.Number, p.Comment.ID
	case "workflow_dispatch":
		pull, pullErr := fixer.PositiveID(p.Inputs["auto_pr"])
		comment, commentErr := fixer.PositiveID(p.Inputs["auto_request"])
		if pullErr != nil || commentErr != nil || cfg.Actor != "github-actions[bot]" ||
			(cfg.Run.RunAttempt == 1 && cfg.TriggerActor != cfg.Actor) {
			return nil, ErrUnauthorized
		}
		c.pullNumber, c.commentID, c.automatic = pull, comment, true
	default:
		return nil, ErrUnauthorized
	}
	if err := cfg.Client.Request(ctx, "GET", "", nil, &c.repo); err != nil {
		return nil, err
	}
	if !agentpr.SameRepository(c.repo, p.Repository) || c.repo.DefaultBranch == "" || cfg.Ref != "refs/heads/"+c.repo.DefaultBranch {
		return nil, ErrUnauthorized
	}
	var err error
	c.bot, err = agentpr.Bot(ctx, cfg.Client)
	if err != nil {
		return nil, err
	}
	if c.automatic && p.Sender != c.bot {
		return nil, ErrUnauthorized
	}
	if c.automatic && cfg.Run.RunAttempt > 1 && cfg.TriggerActor != c.bot.Login {
		allowed, err := c.allowedActors(ctx)
		if err != nil {
			return nil, err
		}
		if !fixer.Allowed(allowed, cfg.TriggerActor) {
			return nil, ErrUnauthorized
		}
	}
	return c, nil
}

func pullURL(repo string, pull int64) string {
	return fmt.Sprintf("https://github.com/%s/pull/%d", repo, pull)
}

func evidenceURL(repo string, run fixer.RunIdentity) string {
	return fmt.Sprintf("https://github.com/%s/actions/runs/%d/attempts/%d", repo, run.RunID, run.RunAttempt)
}

func (c *Controller) commandCurrent(ctx context.Context) (fixer.Comment, error) {
	var response struct {
		fixer.Comment
		IssueURL string `json:"issue_url"`
	}
	if err := c.cfg.Client.Request(ctx, "GET", fmt.Sprintf("/issues/comments/%d", c.commentID), nil, &response); err != nil {
		if notFound(err) {
			return fixer.Comment{}, ErrUnauthorized
		}
		return fixer.Comment{}, err
	}
	comment := response.Comment
	if comment.ID != c.commentID || comment.CreatedAt == "" || comment.CreatedAt != comment.UpdatedAt ||
		response.IssueURL != fmt.Sprintf("https://api.github.com/repos/%s/issues/%d", c.repo.FullName, c.pullNumber) ||
		(c.automatic && comment.User != c.bot) || (!c.automatic && comment != c.cfg.Payload.Comment) {
		return fixer.Comment{}, ErrUnauthorized
	}
	if _, err := time.Parse(time.RFC3339, comment.CreatedAt); err != nil {
		return fixer.Comment{}, ErrUnauthorized
	}
	return comment, nil
}

func (c *Controller) allowedActors(ctx context.Context) (string, error) {
	for _, pair := range [][2]string{{"AGENT_ENABLED", "true"}, {"AGENT_MODE", "hosted"}} {
		value, err := c.cfg.Variables.RepositoryVariable(ctx, pair[0])
		if err != nil {
			return "", err
		}
		if value != pair[1] {
			return "", ErrUnauthorized
		}
	}
	return c.cfg.Variables.RepositoryVariable(ctx, "AGENT_ALLOWED_ACTORS")
}

func (c *Controller) operatorCurrent(ctx context.Context, actors string, actor fixer.GitHubUser) error {
	allowed, err := ReviewerAllowed(ctx, c.cfg.Client, actors, actor)
	if err != nil {
		return err
	}
	if !allowed {
		return ErrUnauthorized
	}
	return nil
}

func (c *Controller) jobCurrent(ctx context.Context, a Assignment) (bool, error) {
	var actual fixer.WorkflowRun
	path := fmt.Sprintf("/actions/runs/%d/attempts/%d", a.Run.RunID, a.Run.RunAttempt)
	if err := c.cfg.Client.Request(ctx, "GET", path, nil, &actual); err != nil {
		return false, err
	}
	workflow := ".github/workflows/agent-work.yaml"
	branch := c.repo.DefaultBranch
	validPath := actual.Path == workflow || actual.Path == workflow+"@"+branch || actual.Path == workflow+"@refs/heads/"+branch
	identity, err := actual.RevisionIdentity()
	if a.Automatic {
		var expected struct {
			ID   int64  `json:"id"`
			Path string `json:"path"`
		}
		if err := c.cfg.Client.Request(ctx, "GET", "/actions/workflows/agent-work.yaml", nil, &expected); err != nil {
			return false, err
		}
		if expected.ID <= 0 || expected.Path != workflow || actual.WorkflowID != expected.ID {
			return false, ErrUnauthorized
		}
	}
	if actual.ID != a.Run.RunID || actual.RunAttempt != a.Run.RunAttempt || !agentpr.SameRepository(actual.HeadRepository, c.repo) ||
		err != nil || !validPath || actual.HeadBranch != branch || actual.HeadSHA != a.WorkflowSHA ||
		identity != (fixer.RevisionRunIdentity{PullNumber: a.PullNumber, RequestID: a.CommentID, Automatic: a.Automatic}) {
		return false, fmt.Errorf("the revision workflow differs from the recorded run")
	}
	return actual.Status != "completed", nil
}

func (c *Controller) pull(ctx context.Context) (agentpr.PullRequest, error) {
	var pr agentpr.PullRequest
	err := c.cfg.Client.ReadPullRequestWithMerge(ctx, c.pullNumber, &pr)
	return pr, err
}

func (c *Controller) completedHead(pr agentpr.PullRequest, a Assignment, publishedSHA string) (bool, error) {
	if pr.ID != a.PullID || pr.Number != a.PullNumber || !agentpr.SameRepository(pr.Base.Repo, c.repo) ||
		pr.Base.Ref != c.repo.DefaultBranch || pr.Head.Ref != a.Branch {
		return false, ErrStale
	}
	if publishedSHA != "" && pr.Head.SHA == publishedSHA && agentpr.SameRepository(pr.Head.Repo, c.repo) {
		return true, nil
	}
	if pr.State != "open" || pr.Merged {
		return false, ErrClosed
	}
	if pr.Head.SHA != a.Head || !agentpr.SameRepository(pr.Head.Repo, c.repo) {
		return false, ErrStale
	}
	return false, nil
}

func (c *Controller) CheckHead(ctx context.Context, a Assignment, publishedSHA string) (bool, error) {
	if publishedSHA != "" && !commitSHA.MatchString(publishedSHA) {
		return false, fmt.Errorf("invalid revision commit")
	}
	pr, err := c.pull(ctx)
	if err != nil {
		return false, err
	}
	already, err := c.completedHead(pr, a, publishedSHA)
	if publishedSHA == "" || !isHeadChange(err) || !commitSHA.MatchString(pr.Head.SHA) ||
		!agentpr.SameRepository(pr.Head.Repo, c.repo) || pr.Head.Ref != a.Branch {
		return already, err
	}
	var comparison struct {
		Status string `json:"status"`
	}
	if compareErr := c.cfg.Client.Request(ctx, "GET", "/compare/"+publishedSHA+"..."+pr.Head.SHA, nil, &comparison); compareErr != nil {
		var response *fixer.HTTPError
		if errors.As(compareErr, &response) && response.Status == 404 {
			return false, err
		}
		return false, compareErr
	}
	if comparison.Status == "ahead" || comparison.Status == "identical" {
		return true, nil
	}
	return false, err
}

func isHeadChange(err error) bool {
	return errors.Is(err, ErrStale) || errors.Is(err, ErrClosed)
}

func (c *Controller) OpenRevision(ctx context.Context) (bool, error) {
	if c.cfg.Run.RunAttempt != 1 {
		return false, nil
	}
	comments, err := c.botComments(ctx)
	if err != nil {
		return false, err
	}
	seen, err := Assignments(comments, c.bot, c.repo.ID, c.pullNumber)
	if err != nil {
		return false, err
	}
	if _, exists := seen[c.commentID]; exists {
		return false, nil
	}
	actors, err := c.allowedActors(ctx)
	if err != nil {
		return false, err
	}
	if !c.automatic {
		if err := c.operatorCurrent(ctx, actors, c.cfg.Payload.Comment.User); err != nil {
			return false, err
		}
	}
	command, err := c.commandCurrent(ctx)
	if err != nil {
		return false, err
	}
	var selected []selection
	var instruction string
	if !c.automatic {
		selected, instruction, err = parseCommand(command.Body, pullURL(c.repo.FullName, c.pullNumber))
		if err != nil {
			return false, err
		}
	}
	pr, state, err := agentpr.Read(ctx, c.cfg.Client, c.repo, c.pullNumber)
	if err != nil {
		return false, err
	}
	if pr.State != "open" || pr.Merged {
		return false, ErrClosed
	}
	if state.HostedCandidate == nil || (c.automatic && !fixer.Allowed(actors, state.Initiator)) {
		return false, ErrUnauthorized
	}
	if pr.Base.Ref != c.repo.DefaultBranch {
		return false, ErrStale
	}
	for _, existing := range seen {
		if RevisionResultRecorded(comments, c.bot, existing) {
			continue
		}
		active, err := c.jobCurrent(ctx, existing)
		if err != nil {
			return false, err
		}
		if active {
			return false, ErrAlreadyActive
		}
	}
	if pr.Head.SHA != state.HostedPublication.SHA {
		var comparison struct {
			Status string `json:"status"`
		}
		endpoint := "/compare/" + url.PathEscape(state.HostedPublication.SHA+"..."+pr.Head.SHA)
		if err := c.cfg.Client.Request(ctx, "GET", endpoint, nil, &comparison); err != nil {
			return false, err
		}
		if comparison.Status != "ahead" {
			return false, ErrStale
		}
	}
	a := Assignment{
		RepositoryID: c.repo.ID, PullID: pr.ID, PullNumber: pr.Number, Issue: state.IssueNumber,
		Branch: pr.Head.Ref, Head: pr.Head.SHA, CommentID: command.ID, Actor: command.User, CreatedAt: command.CreatedAt,
		CommandHash: commandDigest(command), WorkflowSHA: c.cfg.WorkflowSHA, Automatic: c.automatic,
		Run: c.cfg.Run, OriginRun: state.HostedCandidate.Run,
	}
	if c.automatic {
		if err := c.prepareAutomatic(ctx, actors, pr, command, seen, comments, &a); err != nil {
			return false, err
		}
	} else {
		a.Instruction = instruction
		a.Feedback, err = loadFeedback(ctx, c.cfg.Client, c.repo.FullName, pr.Number, selected)
		if err != nil {
			return false, err
		}
		for _, item := range selected {
			if item.kind == "pullrequestreview-" {
				a.ReviewIDs = append(a.ReviewIDs, item.id)
			}
		}
	}
	checkpoint, err := c.checkpoint(ctx, state, a)
	if err != nil {
		return false, err
	}
	a.checkpointSHA, err = checkpointDigest(checkpoint)
	if err != nil {
		return false, err
	}
	active, err := c.jobCurrent(ctx, a)
	if err != nil {
		return false, err
	}
	if !active {
		return false, ErrUnauthorized
	}
	if _, err := c.CheckHead(ctx, a, ""); err != nil {
		return false, err
	}
	return true, c.postReceipt(ctx, a)
}

func (c *Controller) checkpoint(ctx context.Context, state *fixer.State, a Assignment) (fixer.Checkpoint, error) {
	var issue fixer.Issue
	if err := c.cfg.Client.Request(ctx, "GET", fmt.Sprintf("/issues/%d", a.Issue), nil, &issue); err != nil {
		return fixer.Checkpoint{}, err
	}
	if state.IssueNumber != a.Issue || state.HostedCandidate == nil || state.HostedCandidate.Run != a.OriginRun {
		return fixer.Checkpoint{}, ErrUnauthorized
	}
	data, err := json.Marshal(a)
	if err != nil {
		return fixer.Checkpoint{}, err
	}
	checkpoint, err := fixer.RevisionCheckpoint(state, issue, a.Head, a.Run, a.CommentID, fmt.Sprintf("%x", sha256.Sum256(data)))
	if err != nil {
		return checkpoint, errors.Join(ErrUnauthorized, err)
	}
	return checkpoint, nil
}

func commandDigest(command fixer.Comment) string {
	return fmt.Sprintf("%x", sha256.Sum256([]byte(command.Body)))
}

func (c *Controller) Assignment(ctx context.Context) (Assignment, bool, error) {
	receipts, err := c.receipts(ctx)
	if err != nil {
		return Assignment{}, false, err
	}
	a, ok := receipts[c.commentID]
	if !ok || a.Run.RunID != c.cfg.Run.RunID {
		return Assignment{}, false, nil
	}
	if a.Automatic != c.automatic || a.WorkflowSHA != c.cfg.WorkflowSHA ||
		(c.automatic && a.Actor != c.bot) ||
		(!c.automatic && !a.matchesCommand(c.cfg.Payload.Comment)) {
		return Assignment{}, false, ErrUnauthorized
	}
	return a, true, nil
}

func (a Assignment) matchesCommand(command fixer.Comment) bool {
	return a.Actor == command.User && a.CreatedAt == command.CreatedAt && a.CommandHash == commandDigest(command)
}

func (c *Controller) Verify(ctx context.Context, a Assignment) (fixer.Checkpoint, error) {
	return c.verify(ctx, &a)
}

func (c *Controller) VerifyWork(ctx context.Context, a Assignment) (Assignment, fixer.Checkpoint, error) {
	checkpoint, err := c.verify(ctx, &a)
	return a, checkpoint, err
}

func (c *Controller) verify(ctx context.Context, a *Assignment) (fixer.Checkpoint, error) {
	if a.RepositoryID != c.repo.ID || a.PullNumber != c.pullNumber || a.CommentID != c.commentID ||
		a.Run.RunID != c.cfg.Run.RunID || a.WorkflowSHA != c.cfg.WorkflowSHA || a.Automatic != c.automatic {
		return fixer.Checkpoint{}, ErrUnauthorized
	}
	actors, err := c.allowedActors(ctx)
	if err != nil {
		return fixer.Checkpoint{}, err
	}
	if !c.automatic {
		if err := c.operatorCurrent(ctx, actors, c.cfg.Payload.Comment.User); err != nil {
			return fixer.Checkpoint{}, err
		}
	}
	command, err := c.commandCurrent(ctx)
	if err != nil {
		return fixer.Checkpoint{}, err
	}
	if !a.matchesCommand(command) {
		return fixer.Checkpoint{}, ErrUnauthorized
	}
	pr, state, err := agentpr.Read(ctx, c.cfg.Client, c.repo, a.PullNumber)
	if errors.Is(err, agentpr.ErrNotPublished) || errors.Is(err, agentpr.ErrPublicationPending) {
		return fixer.Checkpoint{}, ErrUnauthorized
	}
	if err != nil {
		return fixer.Checkpoint{}, err
	}
	if c.automatic {
		if !fixer.Allowed(actors, state.Initiator) {
			return fixer.Checkpoint{}, ErrUnauthorized
		}
		if _, err := c.jobCurrent(ctx, *a); err != nil {
			return fixer.Checkpoint{}, err
		}
		if _, err := c.completedHead(pr, *a, ""); err != nil {
			return fixer.Checkpoint{}, err
		}
		if err := c.verifyAutomatic(ctx, actors, pr, command, a); err != nil {
			return fixer.Checkpoint{}, err
		}
	}
	checkpoint, err := c.checkpoint(ctx, state, *a)
	if err != nil {
		return fixer.Checkpoint{}, err
	}
	if !a.MatchesCheckpoint(checkpoint) {
		return fixer.Checkpoint{}, ErrUnauthorized
	}
	return checkpoint, nil
}

func checkpointDigest(checkpoint fixer.Checkpoint) (string, error) {
	data, err := json.Marshal(checkpoint)
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("%x", sha256.Sum256(data)), nil
}

func (a Assignment) MatchesCheckpoint(checkpoint fixer.Checkpoint) bool {
	digest, err := checkpointDigest(checkpoint)
	return err == nil && digest == a.checkpointSHA
}
