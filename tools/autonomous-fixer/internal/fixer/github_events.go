package fixer

import (
	"encoding/json"
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"time"
)

type GitHubUser struct {
	ID    int64  `json:"id"`
	Login string `json:"login"`
	Type  string `json:"type"`
}
type Label struct {
	Name string `json:"name"`
}
type Repository struct {
	ID            int64  `json:"id"`
	FullName      string `json:"full_name"`
	DefaultBranch string `json:"default_branch"`
}
type Issue struct {
	ID          int64           `json:"id"`
	Number      int64           `json:"number"`
	Title       string          `json:"title"`
	Body        *string         `json:"body"`
	Labels      []Label         `json:"labels"`
	State       string          `json:"state"`
	UpdatedAt   string          `json:"updated_at"`
	PullRequest json.RawMessage `json:"pull_request"`
	User        GitHubUser      `json:"user"`
}
type Comment struct {
	ID        int64      `json:"id"`
	Body      string     `json:"body"`
	User      GitHubUser `json:"user"`
	CreatedAt string     `json:"created_at"`
	UpdatedAt string     `json:"updated_at"`
}
type WorkflowRun struct {
	ID             int64      `json:"id"`
	RunAttempt     int        `json:"run_attempt"`
	WorkflowID     int64      `json:"workflow_id"`
	HeadRepository Repository `json:"head_repository"`
	Event          string     `json:"event"`
	Path           string     `json:"path"`
	HeadSHA        string     `json:"head_sha"`
	HeadBranch     string     `json:"head_branch"`
	DisplayTitle   string     `json:"display_title"`
	Status         string     `json:"status"`
	Conclusion     string     `json:"conclusion"`
	CreatedAt      string     `json:"created_at"`
}
type GitHubPayload struct {
	Repository  Repository                 `json:"repository"`
	Issue       Issue                      `json:"issue"`
	Comment     Comment                    `json:"comment"`
	Sender      GitHubUser                 `json:"sender"`
	Action      string                     `json:"action"`
	Label       Label                      `json:"label"`
	Changes     map[string]json.RawMessage `json:"changes"`
	WorkflowRun WorkflowRun                `json:"workflow_run"`
	Inputs      map[string]string          `json:"inputs"`
}

var safeRunID = regexp.MustCompile(`^[A-Za-z0-9/_-]+$`)

func human(user GitHubUser) bool {
	return user.ID > 0 && user.Type == "User" && validLogin(user.Login)
}
func (issue Issue) ReadyForAgent() bool {
	for _, label := range issue.Labels {
		if label.Name == "ready-for-agent" {
			return true
		}
	}
	return false
}

func (issue Issue) Requirements() string {
	requirements := issue.Title + "\n\n"
	if issue.Body != nil {
		requirements += *issue.Body
	}
	return requirements
}
func DecodeGitHubEvent(name string, payload GitHubPayload, repo string, state *State, labelEventID int64) *Event {
	issue := payload.Issue
	if repo == "" || payload.Repository.FullName != repo || issue.Number <= 0 || (len(issue.PullRequest) > 0 && string(issue.PullRequest) != "null") {
		return nil
	}
	if name == "issue_comment" {
		c := payload.Comment
		if payload.Action != "created" || !human(c.User) || !human(payload.Sender) || c.User.ID != payload.Sender.ID || c.User.Login != payload.Sender.Login || c.ID <= 0 || strings.ContainsAny(c.Body, "\r\n") {
			return nil
		}
		event := &Event{Actor: c.User.Login, Source: Source{Kind: "comment", ID: c.ID, Action: "created"}}
		command := regexp.MustCompile(`^/agent (fix|status|stop|resume|retry)$`).FindStringSubmatch(c.Body)
		if command != nil {
			event.Type = command[1]
			if event.Type == "fix" && state == nil && !issue.ReadyForAgent() {
				return nil
			}
			return event
		}
		answer := regexp.MustCompile(`^/agent answer ([A-Za-z0-9/_-]+) ([1-9][0-9]*) ([1-9][0-9]*) ([^\r\n]+)$`).FindStringSubmatch(c.Body)
		if answer == nil || strings.TrimSpace(answer[4]) == "" {
			return nil
		}
		version, e := strconv.Atoi(answer[2])
		if e != nil || version <= 0 || int64(version) > 9007199254740991 {
			return nil
		}
		contract, e := strconv.Atoi(answer[3])
		if e != nil || contract <= 0 || int64(contract) > 9007199254740991 {
			return nil
		}
		event.Type = "answer"
		event.QuestionID = answer[1]
		event.QuestionVersion = version
		event.ContractVersion = contract
		event.Answer = answer[4]
		return event
	}
	if name != "issues" || !human(payload.Sender) {
		return nil
	}
	event := &Event{Actor: payload.Sender.Login}
	if (payload.Action == "labeled" || payload.Action == "unlabeled") && payload.Label.Name == "ready-for-agent" {
		if labelEventID <= 0 {
			return nil
		}
		event.Type = "label-added"
		event.Source = Source{Kind: "label", ID: labelEventID, Action: "added"}
		if payload.Action == "unlabeled" {
			event.Type = "label-removed"
			event.Source.Action = "removed"
		}
		return event
	}
	_, title := payload.Changes["title"]
	_, body := payload.Changes["body"]
	if payload.Action != "edited" || (!title && !body) || issue.ID <= 0 {
		return nil
	}
	updated, e := time.Parse(time.RFC3339Nano, issue.UpdatedAt)
	if e != nil || updated.UnixMilli() <= 0 {
		return nil
	}
	event.Type = "requirements"
	event.Requirements = issue.Requirements()
	event.Source = Source{Kind: "issue", ID: issue.ID, Revision: updated.UnixMilli()}
	return event
}

type RevisionRunIdentity struct {
	PullNumber int64
	RequestID  int64
	Automatic  bool
}

// RevisionIdentity reads the PR and request IDs from the agent-work workflow title.
// A malformed request ID leaves the PR number available to its coordinator.
func (run WorkflowRun) RevisionIdentity() (RevisionRunIdentity, error) {
	var identity RevisionRunIdentity
	kind, rest, _ := strings.Cut(run.DisplayTitle, "|")
	switch {
	case kind == "qip-agent-auto-revise" && run.Event == "workflow_dispatch":
		identity.Automatic = true
	case kind == "qip-agent-revise" && run.Event == "issue_comment":
	default:
		return identity, fmt.Errorf("unexpected revision workflow title or event")
	}
	pull, request, _ := strings.Cut(rest, "|")
	var err error
	identity.PullNumber, err = PositiveID(pull)
	if err != nil {
		return identity, err
	}
	identity.RequestID, err = PositiveID(request)
	return identity, err
}

type WorkRunIdentity struct {
	IssueNumber                 int64
	ExecutionID                 string
	Generation, ContractVersion int
}

func WorkRunName(issue int64, execution string, generation, contractVersion int) string {
	if issue <= 0 || issue > 9007199254740991 || generation <= 0 || int64(generation) > 9007199254740991 || contractVersion <= 0 || int64(contractVersion) > 9007199254740991 || !safeRunID.MatchString(execution) {
		return ""
	}
	return fmt.Sprintf("qip-agent-work|%d|%s|%d|%d", issue, execution, generation, contractVersion)
}
func ParseWorkRunName(value string) (WorkRunIdentity, error) {
	var result WorkRunIdentity
	m := regexp.MustCompile(`^qip-agent-work\|([1-9][0-9]*)\|([A-Za-z0-9/_-]+)\|([1-9][0-9]*)\|([1-9][0-9]*)$`).FindStringSubmatch(value)
	if m == nil {
		return result, fmt.Errorf("invalid worker run identity")
	}
	var err error
	result.IssueNumber, err = strconv.ParseInt(m[1], 10, 64)
	if err != nil {
		return result, err
	}
	result.ExecutionID = m[2]
	result.Generation, err = strconv.Atoi(m[3])
	if err != nil {
		return result, err
	}
	result.ContractVersion, err = strconv.Atoi(m[4])
	if result.IssueNumber > 9007199254740991 || int64(result.Generation) > 9007199254740991 || int64(result.ContractVersion) > 9007199254740991 {
		return result, fmt.Errorf("invalid worker run identity")
	}
	return result, err
}

type WorkerRunExpected struct {
	Repository                         string
	WorkflowID                         int64
	TrustedSHA, DefaultBranch, RunName string
	RunID                              int64
	RunAttempt                         int
}

func MatchWorkerRun(run WorkflowRun, e WorkerRunExpected) bool {
	if e.Repository == "" || e.WorkflowID <= 0 || !regexp.MustCompile(`^[a-fA-F0-9]{40}$`).MatchString(e.TrustedSHA) || e.DefaultBranch == "" || run.ID <= 0 || run.RunAttempt <= 0 || e.RunID < 0 || e.RunAttempt < 0 {
		return false
	}
	if _, err := ParseWorkRunName(e.RunName); err != nil {
		return false
	}
	attempt := e.RunAttempt
	if attempt == 0 {
		attempt = 1
	}
	const file = ".github/workflows/agent-work.yaml"
	pathMatches := run.Path == file || run.Path == file+"@"+e.DefaultBranch || run.Path == file+"@refs/heads/"+e.DefaultBranch
	return (e.RunID == 0 || run.ID == e.RunID) && run.RunAttempt == attempt && run.HeadRepository.FullName == e.Repository && run.WorkflowID == e.WorkflowID && run.Event == "workflow_dispatch" && pathMatches && run.HeadSHA == e.TrustedSHA && run.HeadBranch == e.DefaultBranch && run.DisplayTitle == e.RunName
}
