package docgithub

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"sort"
	"strconv"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docpacket"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

const (
	scheduleWorkflow = ".github/workflows/agent-docs-schedule.yaml"
	schedulePrefix   = "<!-- qip-docs-schedule:v1 "
)

type ScheduleCandidate struct {
	Issue int    `json:"issue"`
	Basis string `json:"basis"`
}

type ScheduleSkip struct {
	Issue  int    `json:"issue,omitempty"`
	Reason string `json:"reason"`
}

type ScheduleReport struct {
	Eligible []ScheduleCandidate `json:"eligible"`
	Skipped  []ScheduleSkip      `json:"skipped"`
}

type ScheduledAttempt struct {
	RunID   int64
	Attempt int
}

func (p Plan) workflowAttempt() (ScheduledAttempt, bool) {
	run, attempt, ok := strings.Cut(p.RequestID, ":")
	id, runErr := fixer.PositiveID(run)
	number, attemptErr := fixer.PositiveID(attempt)
	return ScheduledAttempt{RunID: id, Attempt: int(number)}, ok && runErr == nil && attemptErr == nil
}

func (a ScheduledAttempt) url(repo string) string {
	return fmt.Sprintf("https://github.com/%s/actions/runs/%d/attempts/%d", repo, a.RunID, a.Attempt)
}

func scheduleBasis(plan Plan) string {
	return Digest(struct {
		Repository, Item, Issue, Title, BodyHash string
		ImplementingPRs                          []docpacket.PR
	}{plan.Repository, plan.Project.ItemID, plan.IssueNodeID, plan.IssueTitle, plan.IssueBodyHash, plan.ImplementingPRs})
}

func projectReviewIssues(ctx context.Context, c *Client, number int) ([]int, []ScheduleSkip, error) {
	query := `query($owner:String!,$repo:String!,$project:Int!,$cursor:String){repository(owner:$owner,name:$repo){owner{... on ProjectV2Owner{projectV2(number:$project){id field(name:"Status"){... on ProjectV2SingleSelectField{id options{id name}}} items(first:100,after:$cursor){pageInfo{hasNextPage endCursor} nodes{isArchived fieldValueByName(name:"Status"){... on ProjectV2ItemFieldSingleSelectValue{optionId field{... on ProjectV2SingleSelectField{id}}}} content{__typename ... on Issue{number repository{nameWithOwner}}}}}}}}}}`
	var cursor any
	var projectID, fieldID, reviewID string
	issues := map[int]bool{}
	skipped := []ScheduleSkip{}
	owner, repo, _ := strings.Cut(c.Repository, "/")
	for {
		var data struct {
			Repository struct {
				Owner struct {
					Project struct {
						ID    string
						Field struct {
							ID      string
							Options []struct{ ID, Name string }
						}
						Items struct {
							PageInfo struct {
								HasNextPage bool
								EndCursor   string
							}
							Nodes []struct {
								IsArchived bool
								Status     struct {
									OptionID string
									Field    struct{ ID string }
								} `json:"fieldValueByName"`
								Content struct {
									Type       string `json:"__typename"`
									Number     int
									Repository struct{ NameWithOwner string }
								}
							}
						}
					} `json:"projectV2"`
				}
			}
		}
		if err := c.projectGraphQL(ctx, query, map[string]any{"owner": owner, "repo": repo, "project": number, "cursor": cursor}, &data); err != nil {
			return nil, nil, err
		}
		project := data.Repository.Owner.Project
		optionID := ""
		for _, option := range project.Field.Options {
			if option.Name == "In doc review" {
				optionID = option.ID
			}
		}
		if project.ID == "" || project.Field.ID == "" || optionID == "" {
			return nil, nil, fmt.Errorf("configured project has no In doc review status")
		}
		if projectID != "" && (project.ID != projectID || project.Field.ID != fieldID || optionID != reviewID) {
			return nil, nil, fmt.Errorf("project configuration changed during discovery")
		}
		projectID, fieldID, reviewID = project.ID, project.Field.ID, optionID
		for _, item := range project.Items.Nodes {
			content := item.Content
			if content.Type != "Issue" {
				skipped = append(skipped, ScheduleSkip{Reason: "not-a-repository-issue"})
			} else if !strings.EqualFold(content.Repository.NameWithOwner, c.Repository) || content.Number <= 0 {
				skipped = append(skipped, ScheduleSkip{Reason: "outside-repository"})
			} else if item.IsArchived {
				skipped = append(skipped, ScheduleSkip{Issue: content.Number, Reason: "archived"})
			} else if item.Status.Field.ID != fieldID || item.Status.OptionID != reviewID {
				skipped = append(skipped, ScheduleSkip{Issue: content.Number, Reason: "outside-doc-review"})
			} else {
				issues[content.Number] = true
			}
		}
		if !project.Items.PageInfo.HasNextPage {
			break
		}
		next := project.Items.PageInfo.EndCursor
		if next == "" || next == cursor {
			return nil, nil, fmt.Errorf("project item pagination did not advance")
		}
		cursor = next
	}
	numbers := make([]int, 0, len(issues))
	for issue := range issues {
		numbers = append(numbers, issue)
	}
	sort.Ints(numbers)
	return numbers, skipped, nil
}

func manualDocsRuns(ctx context.Context, actions *fixer.GitHubClient) (map[int]bool, bool, error) {
	rows, err := actions.List(ctx, "/actions/workflows/agent-docs.yaml/runs?event=workflow_dispatch&branch=main", "workflow_runs")
	if err != nil {
		return nil, false, err
	}
	active := map[int]bool{}
	unknown := false
	for _, raw := range rows {
		var run fixer.WorkflowRun
		if err := json.Unmarshal(raw, &run); err != nil {
			return nil, false, err
		}
		if run.Status == "completed" {
			continue
		}
		parts := strings.Split(run.DisplayTitle, "|")
		if len(parts) != 3 || parts[0] != "qip-docs" {
			unknown = true
			continue
		}
		issue, err := strconv.Atoi(parts[1])
		if err != nil || issue <= 0 {
			unknown = true
			continue
		}
		active[issue] = true
	}
	return active, unknown, nil
}

func linkedDocumentationPR(ctx context.Context, c *Client, issue int) (bool, error) {
	refs, strong, err := c.linkedReferences(ctx, issue)
	if err != nil {
		return false, err
	}
	for number, pr := range refs {
		if pr.BaseRefName != "main" || pr.State != "OPEN" || !strong[number] {
			continue
		}
		files, err := c.API.List(ctx, fmt.Sprintf("/pulls/%d/files", number), "")
		if err != nil {
			return false, err
		}
		helpOnly := len(files) > 0
		for _, raw := range files {
			var file struct {
				Filename         string `json:"filename"`
				PreviousFilename string `json:"previous_filename"`
			}
			if err := json.Unmarshal(raw, &file); err != nil {
				return false, err
			}
			helpOnly = helpOnly && strings.HasPrefix(file.Filename, "help/docs/") &&
				(file.PreviousFilename == "" || strings.HasPrefix(file.PreviousFilename, "help/docs/"))
		}
		if helpOnly {
			return true, nil
		}
	}
	return false, nil
}

func previousScheduleWork(ctx context.Context, actions *fixer.GitHubClient, repo string, issue int, attempt ScheduledAttempt) (string, error) {
	path := fmt.Sprintf("/actions/runs/%d/attempts/%d", attempt.RunID, attempt.Attempt)
	var run fixer.WorkflowRun
	if err := actions.Request(ctx, "GET", path, nil, &run); err != nil {
		return "", err
	}
	workflow, _, _ := strings.Cut(run.Path, "@")
	if run.ID != attempt.RunID || run.RunAttempt != attempt.Attempt || run.HeadRepository.FullName != repo ||
		run.HeadBranch != "main" || workflow != scheduleWorkflow ||
		(run.Event != "schedule" && run.Event != "workflow_dispatch" && run.Event != "issue_comment") {
		return "", fmt.Errorf("scheduled run identity differs from its claim")
	}
	if run.Status != "completed" {
		return "active-docs-run", nil
	}
	rows, err := actions.List(ctx, path+"/jobs", "jobs")
	if err != nil {
		return "", err
	}
	name := fmt.Sprintf("Work on documentation issue #%d", issue)
	for _, raw := range rows {
		var job struct {
			Name, Status, Conclusion string
			RunnerID                 *int64 `json:"runner_id"`
			Steps                    []struct{ Name, Status, Conclusion string }
		}
		if err := json.Unmarshal(raw, &job); err != nil {
			return "", err
		}
		if job.Name != name && !strings.HasSuffix(job.Name, " / "+name) {
			continue
		}
		unassigned := job.Status == "completed" && job.Conclusion == "cancelled" && job.RunnerID != nil && *job.RunnerID == 0 && job.Steps != nil
		skipped := len(job.Steps) == 0 && (job.Conclusion == "skipped" || unassigned)
		for _, step := range job.Steps {
			if step.Name == "Run documentation work" {
				skipped = step.Status == "completed" && step.Conclusion == "skipped"
				break
			}
		}
		if !skipped {
			return "basis-already-attempted", nil
		}
	}
	return "", nil
}

var scheduleReceiptPattern = regexp.MustCompile(`^<!-- qip-docs-schedule:v1 ([a-f0-9]{64}) ([1-9][0-9]*) ([1-9][0-9]*) -->\n`)

func scheduleReceipt(basis string, attempt ScheduledAttempt) string {
	return fmt.Sprintf("%s%s %d %d -->", schedulePrefix, basis, attempt.RunID, attempt.Attempt)
}

func scheduleIssueBlock(ctx context.Context, c *Client, actions *fixer.GitHubClient, plan Plan, publisherID int64, current ScheduledAttempt) (string, bool, error) {
	rows, err := c.API.List(ctx, fmt.Sprintf("/issues/%d/comments", plan.IssueNumber), "")
	if err != nil {
		return "", false, err
	}
	basis := scheduleBasis(plan)
	var prior []ScheduledAttempt
	alreadyClaimed := false
	for _, raw := range rows {
		var comment issueComment
		if err := json.Unmarshal(raw, &comment); err != nil {
			return "", false, err
		}
		if comment.User.ID != publisherID {
			continue
		}
		if strings.HasPrefix(comment.Body, "<!-- qip-docs:") {
			publication, err := readPublicationRecord(comment.Body, c.Repository, plan.IssueNumber)
			if err != nil {
				return "", false, fmt.Errorf("invalid owned documentation publication state")
			}
			if publication.Status == "superseded" {
				if publication.Plan.BaseSHA == plan.BaseSHA && scheduleBasis(*publication.Plan) == basis {
					return "basis-already-attempted", false, nil
				}
				continue
			}
			if publication.PRNumber > 0 && (publication.Status != "completed" || scheduleBasis(*publication.Plan) == basis) {
				return "existing-docs-pr", false, nil
			}
			if publication.Status != "completed" {
				return "publication-in-progress", false, nil
			}
			if scheduleBasis(*publication.Plan) == basis {
				return "basis-already-published", false, nil
			}
		}
		if strings.HasPrefix(comment.Body, schedulePrefix) {
			match := scheduleReceiptPattern.FindStringSubmatch(comment.Body)
			if match == nil {
				return "", false, fmt.Errorf("invalid scheduled documentation claim")
			}
			run, runErr := strconv.ParseInt(match[2], 10, 64)
			attempt, attemptErr := strconv.Atoi(match[3])
			if runErr != nil || attemptErr != nil || run <= 0 || attempt <= 0 {
				return "", false, fmt.Errorf("invalid scheduled documentation run identity")
			}
			if match[1] == basis {
				identity := ScheduledAttempt{RunID: run, Attempt: attempt}
				if identity == current {
					if alreadyClaimed {
						return "", false, fmt.Errorf("duplicate scheduled documentation claim")
					}
					alreadyClaimed = true
				} else {
					prior = append(prior, identity)
				}
			}
		}
	}
	hasPR, err := linkedDocumentationPR(ctx, c, plan.IssueNumber)
	if err != nil {
		return "", false, err
	}
	if hasPR {
		return "existing-docs-pr", false, nil
	}
	for _, attempt := range prior {
		reason, err := previousScheduleWork(ctx, actions, c.Repository, plan.IssueNumber, attempt)
		if err != nil || reason != "" {
			return reason, false, err
		}
	}
	return "", alreadyClaimed, nil
}

func DiscoverySkipReason(err error) string {
	switch {
	case errors.Is(err, errNoImplementation):
		return "missing-implementation"
	case errors.Is(err, errNotInDocReview):
		return "outside-doc-review"
	case errors.Is(err, errOutsideProject):
		return "not-in-configured-project"
	case errors.Is(err, errArchivedItem):
		return "archived"
	default:
		return ""
	}
}

func Discover(ctx context.Context, c *Client, actions *fixer.GitHubClient, projectNumber, issueNumber int) (ScheduleReport, error) {
	report := ScheduleReport{Eligible: []ScheduleCandidate{}, Skipped: []ScheduleSkip{}}
	issues := []int{issueNumber}
	if issueNumber == 0 {
		var err error
		issues, report.Skipped, err = projectReviewIssues(ctx, c, projectNumber)
		if err != nil {
			return report, err
		}
	}
	login, _, err := c.configuredPublisherLogin(ctx)
	if err != nil {
		return report, err
	}
	publisher, err := c.API.User(ctx, login)
	if err != nil || publisher.ID <= 0 || !strings.EqualFold(login, publisher.Login) {
		return report, fmt.Errorf("configured documentation publisher identity is unavailable")
	}
	active, unknown, err := manualDocsRuns(ctx, actions)
	if err != nil {
		return report, err
	}
	for _, issue := range issues {
		var reason string
		switch {
		case unknown:
			reason = "unidentified-active-docs-run"
		case active[issue]:
			reason = "active-docs-run"
		default:
			prepared, err := Prepare(ctx, c, issue, projectNumber)
			reason = DiscoverySkipReason(err)
			switch {
			case reason != "":
			case err != nil:
				reason = "intake-unavailable"
			case len(prepared.Ambiguous) > 0:
				reason = "ambiguous-implementations"
			default:
				reason, _, err = scheduleIssueBlock(ctx, c, actions, prepared.Plan, publisher.ID, ScheduledAttempt{})
				if err != nil {
					reason = "prior-work-unavailable"
				}
				if reason == "" {
					report.Eligible = append(report.Eligible, ScheduleCandidate{Issue: issue, Basis: scheduleBasis(prepared.Plan)})
					continue
				}
			}
		}
		report.Skipped = append(report.Skipped, ScheduleSkip{Issue: issue, Reason: reason})
	}
	sort.SliceStable(report.Skipped, func(i, j int) bool {
		a, b := report.Skipped[i], report.Skipped[j]
		if a.Issue != b.Issue {
			return a.Issue < b.Issue
		}
		return a.Reason < b.Reason
	})
	return report, nil
}

func ClaimSchedule(ctx context.Context, c *Client, actions *fixer.GitHubClient, plan Plan, basis, actor string, attempt ScheduledAttempt, background bool) (bool, string, error) {
	if err := validatePublicationPlan(plan); err != nil {
		return false, "", err
	}
	if basis != scheduleBasis(plan) || attempt.RunID <= 0 || attempt.Attempt <= 0 {
		return false, "basis-changed", nil
	}
	if err := Authorize(ctx, c, actor); err != nil {
		return false, "", err
	}
	if background {
		for _, name := range []string{"AGENT_DOCS_SCHEDULE_ENABLED", "AGENT_DOCS_SCHEDULE_APPLY"} {
			value, err := c.API.RepositoryVariable(ctx, name)
			if err != nil {
				return false, "", err
			}
			if value != "true" {
				return false, "schedule-apply-disabled", nil
			}
		}
	}
	current, err := Revalidate(ctx, c, plan)
	if err != nil {
		if reason := DiscoverySkipReason(err); reason != "" {
			return false, reason, nil
		}
		return false, "", err
	}
	if current.ProjectStatus != plan.Project.ReviewOptionID {
		return false, "outside-doc-review", nil
	}
	publisher, err := c.ActorIdentity(ctx)
	if err != nil {
		return false, "", err
	}
	reason, ownClaim, err := scheduleIssueBlock(ctx, c, actions, plan, publisher.ID, attempt)
	if err != nil || reason != "" || ownClaim {
		return ownClaim, reason, err
	}
	path := fmt.Sprintf("/issues/%d/comments", plan.IssueNumber)
	marker := scheduleReceipt(basis, attempt)
	body := fmt.Sprintf("%s\n\nDocumentation review started. [View workflow run](%s)", marker, attempt.url(plan.Repository))
	requestErr := c.Request(ctx, "POST", path, map[string]string{"body": body}, nil)
	reason, ownClaim, err = scheduleIssueBlock(ctx, c, actions, plan, publisher.ID, attempt)
	if err == nil && reason == "" && ownClaim {
		return true, "", nil
	}
	if requestErr != nil {
		return false, "", fmt.Errorf("scheduled documentation claim was not confirmed: %w", requestErr)
	}
	return false, "", fmt.Errorf("scheduled documentation claim was not confirmed")
}
