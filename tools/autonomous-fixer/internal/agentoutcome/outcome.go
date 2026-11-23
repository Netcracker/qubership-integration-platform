package agentoutcome

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentci"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentpr"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

type CloseEvent struct {
	Action      string              `json:"action"`
	Repository  fixer.Repository    `json:"repository"`
	PullRequest agentpr.PullRequest `json:"pull_request"`
}

type Workflow struct {
	RunID      int64 `json:"run_id"`
	RunAttempt int64 `json:"run_attempt"`
}

type Config struct {
	Repository, Workspace, Token, RequiredChecks string
	Workflow                                     Workflow
}

type Report struct {
	Version          int               `json:"schema_version"`
	Repository       fixer.Repository  `json:"repository"`
	PullRequest      int64             `json:"pull_request"`
	PullRequestID    int64             `json:"pull_request_id"`
	Issue            int64             `json:"issue"`
	Attempt          string            `json:"attempt_id"`
	Worker           fixer.RunIdentity `json:"worker"`
	ClosureID        int64             `json:"closure_id"`
	ClosedAt         time.Time         `json:"closed_at"`
	Disposition      string            `json:"disposition"`
	MergeSHA         string            `json:"merge_sha,omitempty"`
	HeadBranch       string            `json:"head_branch"`
	HeadChange       string            `json:"head_change"`
	ContentChange    string            `json:"content_change"`
	Published        Snapshot          `json:"published"`
	Final            Snapshot          `json:"final"`
	CI               agentci.Snapshot  `json:"ci"`
	CIObservedAt     time.Time         `json:"ci_observed_at"`
	HumanInvolvement Provenance        `json:"human_involvement"`
	Workflow         Workflow          `json:"workflow"`
}

func ReadAndRecord(ctx context.Context, client *fixer.GitHubClient, config Config, event CloseEvent) (*Report, error) {
	hook := event.PullRequest
	if event.Action != "closed" || hook.State != "closed" || hook.ID <= 0 || hook.Number <= 0 || hook.ClosedAt.IsZero() ||
		event.Repository.FullName != config.Repository || !agentpr.SameRepository(hook.Head.Repo, event.Repository) ||
		!agentpr.SameRepository(hook.Base.Repo, event.Repository) {
		return nil, nil
	}
	var repository fixer.Repository
	if err := client.Request(ctx, "GET", "", nil, &repository); err != nil {
		return nil, err
	}
	if !agentpr.SameRepository(repository, event.Repository) {
		return nil, fmt.Errorf("pull request closure belongs to another repository")
	}
	pr, state, err := agentpr.Read(ctx, client, repository, hook.Number)
	if errors.Is(err, agentpr.ErrNotPublished) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if !matchesClosure(pr, hook) {
		return nil, nil
	}
	closure, err := latestClosure(ctx, client, pr)
	if err != nil {
		return nil, err
	}
	if closure.ID == 0 {
		return nil, fmt.Errorf("GitHub has not confirmed the pull request closure in its timeline")
	}
	bot, err := agentpr.Bot(ctx, client)
	if err != nil {
		return nil, err
	}
	comments, err := pullComments(ctx, client, pr.Number)
	if err != nil {
		return nil, err
	}
	previous, err := findOutcome(comments, bot.ID, pr.Number)
	if err != nil {
		return nil, err
	}
	artifactRetry := previous != nil && previous.sameRunRetry(closure, config.Workflow)
	if previous != nil && !previous.olderThan(closure) && !artifactRetry {
		return nil, nil
	}

	publication := state.HostedPublication
	execution, ok := agentpr.PublicationExecution(state)
	if !ok {
		return nil, fmt.Errorf("engineering publication has no matching worker execution")
	}
	report := &Report{
		Version: 1, Repository: repository, PullRequest: pr.Number, PullRequestID: pr.ID,
		Issue: state.IssueNumber, Attempt: execution.AttemptID, Worker: *execution.Run,
		ClosureID: closure.ID, ClosedAt: hook.ClosedAt, Disposition: "closed-unmerged",
		HeadBranch: publication.Branch, HeadChange: "unknown", Workflow: config.Workflow,
		Published: Snapshot{HeadSHA: publication.SHA, BaseRef: publication.BaseRef, BaseSHA: publication.BaseSHA},
		Final:     Snapshot{HeadSHA: validSHA(hook.Head.SHA), BaseRef: hook.Base.Ref, BaseSHA: validSHA(hook.Base.SHA)},
	}
	if hook.Merged {
		report.Disposition = "merged"
		report.MergeSHA = validSHA(pr.MergeSHA)
	}
	if report.Final.HeadSHA != "" {
		report.HeadChange = "unchanged"
		if report.Published.HeadSHA != report.Final.HeadSHA {
			report.HeadChange = "changed"
		}
	}
	git := gitRepository{workspace: config.Workspace, token: config.Token}
	if report.Published.BaseSHA == "" {
		report.Published.Diagnostic = "publication predates base capture"
	} else {
		git.capture(ctx, &report.Published, "")
	}
	if hook.Merged && report.MergeSHA == "" {
		report.Final.Diagnostic = "GitHub did not confirm the merge commit"
	} else {
		git.capture(ctx, &report.Final, report.MergeSHA)
	}
	report.ContentChange = git.contentChange(ctx, report.Published, report.Final)
	report.CIObservedAt = time.Now().UTC()
	report.CI = readCI(ctx, client, config, git, hook, report.Final.HeadSHA)
	report.HumanInvolvement = readProvenance(ctx, client, pr, comments, bot, git, *report)

	var current agentpr.PullRequest
	if err := client.ReadPullRequestWithMerge(ctx, pr.Number, &current); err != nil {
		return report, err
	}
	if !matchesClosure(current, hook) {
		return nil, nil
	}
	currentClosure, err := latestClosure(ctx, client, current)
	if err != nil {
		return report, err
	}
	if currentClosure.ID == 0 {
		return report, fmt.Errorf("GitHub could not reconfirm the pull request closure")
	}
	if currentClosure.ID != closure.ID {
		return nil, nil
	}
	if artifactRetry {
		return report, nil
	}
	return report, saveOutcome(ctx, client, bot.ID, previous, *report)
}

func validSHA(value string) string {
	if commitSHA.MatchString(value) {
		return value
	}
	return ""
}

func matchesClosure(current, hook agentpr.PullRequest) bool {
	return current.ID == hook.ID && current.Number == hook.Number && current.State == "closed" &&
		current.Merged == hook.Merged && current.ClosedAt.Equal(hook.ClosedAt) &&
		current.Head.Ref == hook.Head.Ref && current.Base.Ref == hook.Base.Ref &&
		agentpr.SameRepository(current.Head.Repo, hook.Head.Repo) && agentpr.SameRepository(current.Base.Repo, hook.Base.Repo) &&
		(validSHA(current.Head.SHA) == "" || validSHA(hook.Head.SHA) == "" || current.Head.SHA == hook.Head.SHA) &&
		(!current.Merged || ((hook.MergedAt.IsZero() || hook.MergedAt.Equal(current.MergedAt)) &&
			(validSHA(current.MergeSHA) == "" || validSHA(hook.MergeSHA) == "" || current.MergeSHA == hook.MergeSHA)))
}

type timelineEvent struct {
	ID        int64     `json:"id"`
	Event     string    `json:"event"`
	CreatedAt time.Time `json:"created_at"`
}

func latestClosure(ctx context.Context, client *fixer.GitHubClient, pr agentpr.PullRequest) (timelineEvent, error) {
	items, err := client.List(ctx, fmt.Sprintf("/issues/%d/events", pr.Number), "")
	if err != nil {
		return timelineEvent{}, err
	}
	var latest timelineEvent
	for _, item := range items {
		var event timelineEvent
		if err := json.Unmarshal(item, &event); err != nil {
			return timelineEvent{}, fmt.Errorf("invalid pull request timeline event")
		}
		if event.ID <= 0 {
			continue
		}
		if pr.Merged {
			if event.Event == "merged" && event.CreatedAt.Equal(pr.MergedAt) {
				return timelineEvent{ID: event.ID, Event: event.Event, CreatedAt: pr.ClosedAt}, nil
			}
			continue
		}
		if event.Event != "closed" && event.Event != "reopened" {
			continue
		}
		if latest.CreatedAt.Before(event.CreatedAt) || (latest.CreatedAt.Equal(event.CreatedAt) && latest.ID < event.ID) {
			latest = event
		}
	}
	if latest.Event != "closed" || latest.CreatedAt.Before(pr.ClosedAt) {
		return timelineEvent{}, nil
	}
	latest.CreatedAt = pr.ClosedAt
	return latest, nil
}

func readCI(ctx context.Context, client *fixer.GitHubClient, config Config, git gitRepository, hook agentpr.PullRequest, head string) agentci.Snapshot {
	if head == "" {
		return agentci.Snapshot{Status: "unknown", Diagnostics: []string{"The final pull request head is unavailable."}}
	}
	required, diagnostics := agentci.Required(ctx, client, hook.Base.Ref, config.RequiredChecks)
	merge := ""
	if !hook.Merged && git.isTestMerge(ctx, hook.MergeSHA, hook.Base.SHA, head) {
		merge = validSHA(hook.MergeSHA)
	}
	snapshot, err := agentci.Read(ctx, client, head, merge, required)
	snapshot.Diagnostics = append(diagnostics, snapshot.Diagnostics...)
	if err != nil {
		snapshot.HeadSHA = head
		snapshot.Status = "unknown"
		snapshot.Diagnostics = append(snapshot.Diagnostics, "GitHub could not return checks for the final pull request head.")
	} else if len(diagnostics) != 0 && snapshot.Status == "passed" {
		snapshot.Status = "unknown"
	}
	return snapshot
}
