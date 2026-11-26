package agentci

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"regexp"
	"slices"
	"strings"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentpr"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

const branchPrefix = "agent/fix/"

var testMergeRef = regexp.MustCompile(`^(?:refs/)?pull/([1-9][0-9]*)/merge$`)

type Event struct {
	Action      string              `json:"action"`
	Repository  fixer.Repository    `json:"repository"`
	PullRequest agentpr.PullRequest `json:"pull_request"`
	WorkflowRun struct {
		fixer.WorkflowRun
		PullRequests []struct {
			Number int64 `json:"number"`
		} `json:"pull_requests"`
	} `json:"workflow_run"`
}

type Options struct {
	EventName      string
	Event          Event
	Repository     string
	Ref            string
	PullRequest    int64
	RequiredChecks string
	Apply          bool
}

type Report struct {
	ObservedAt     time.Time         `json:"observed_at"`
	Repository     string            `json:"repository"`
	PullRequest    int64             `json:"pull_request"`
	Issue          int64             `json:"issue"`
	PublicationSHA string            `json:"publication_sha"`
	WorkerRun      fixer.RunIdentity `json:"worker_run"`
	CommentID      int64             `json:"comment_id,omitempty"`
	Disposition    string            `json:"disposition"`
	CI             Snapshot          `json:"ci"`
}

func Observe(ctx context.Context, client *fixer.GitHubClient, options Options) ([]Report, error) {
	var repo fixer.Repository
	if err := client.Request(ctx, "GET", "", nil, &repo); err != nil {
		return nil, err
	}
	if repo.FullName != options.Repository || !agentpr.SameRepository(repo, options.Event.Repository) ||
		options.Ref != "refs/heads/"+repo.DefaultBranch {
		return nil, fmt.Errorf("CI observation requires the repository default branch")
	}
	numbers, err := targets(ctx, client, repo, options)
	if err != nil {
		return nil, err
	}
	reports := make([]Report, 0, len(numbers))
	var failures []error
	for _, number := range numbers {
		report, err := observePullRequest(ctx, client, repo, number, options)
		if err != nil {
			failures = append(failures, fmt.Errorf("observe pull request #%d: %w", number, err))
		}
		if report != nil {
			reports = append(reports, *report)
		}
	}
	return reports, errors.Join(failures...)
}

// A completed revision can leave feedback that arrived while its worker was busy.
func completedRevision(repo fixer.Repository, event Event) int64 {
	run := event.WorkflowRun
	if event.Action != "completed" || run.Status != "completed" || run.ID <= 0 ||
		!agentpr.SameRepository(run.HeadRepository, repo) || run.HeadBranch != repo.DefaultBranch ||
		run.Path != ".github/workflows/agent-work.yaml" {
		return 0
	}
	identity, err := run.RevisionIdentity()
	if err != nil {
		return 0
	}
	return identity.PullNumber
}

func targets(ctx context.Context, client *fixer.GitHubClient, repo fixer.Repository, options Options) ([]int64, error) {
	switch options.EventName {
	case "workflow_dispatch":
		if options.PullRequest <= 0 {
			return nil, fmt.Errorf("a pull request number is required")
		}
		return []int64{options.PullRequest}, nil
	case "pull_request_target":
		pr := options.Event.PullRequest
		if !slices.Contains([]string{"opened", "reopened", "synchronize"}, options.Event.Action) || pr.Number <= 0 ||
			!agentpr.SameRepository(pr.Head.Repo, repo) || !agentpr.SameRepository(pr.Base.Repo, repo) ||
			pr.Base.Ref != repo.DefaultBranch || !strings.HasPrefix(pr.Head.Ref, branchPrefix) {
			return nil, nil
		}
		return []int64{pr.Number}, nil
	case "workflow_run":
		if pr := completedRevision(repo, options.Event); pr > 0 {
			return []int64{pr}, nil
		}
		run := options.Event.WorkflowRun
		if options.Event.Action != "completed" || run.Status != "completed" || run.ID <= 0 ||
			!agentpr.SameRepository(run.HeadRepository, repo) || !commitSHA.MatchString(run.HeadSHA) ||
			!eligibleActionEvent(run.Event) || run.Path == ".github/workflows/agent-ci.yaml" {
			return nil, nil
		}
		var numbers []int64
		for _, pr := range run.PullRequests {
			if pr.Number > 0 {
				numbers = append(numbers, pr.Number)
			}
		}
		if len(numbers) > 0 {
			slices.Sort(numbers)
			return slices.Compact(numbers), nil
		}
		if parts := testMergeRef.FindStringSubmatch(run.HeadBranch); len(parts) != 0 {
			if number, err := fixer.PositiveID(parts[1]); err == nil {
				return []int64{number}, nil
			}
		}
		if !strings.HasPrefix(run.HeadBranch, branchPrefix) {
			return nil, nil
		}
		owner, _, _ := strings.Cut(repo.FullName, "/")
		return listTargets(ctx, client, repo, owner+":"+run.HeadBranch)
	case "schedule":
		return listTargets(ctx, client, repo, "")
	default:
		return nil, fmt.Errorf("unsupported CI observation event")
	}
}

func listTargets(ctx context.Context, client *fixer.GitHubClient, repo fixer.Repository, head string) ([]int64, error) {
	query := url.Values{"state": {"open"}, "base": {repo.DefaultBranch}}
	if head != "" {
		query.Set("head", head)
	}
	items, err := client.List(ctx, "/pulls?"+query.Encode(), "")
	if err != nil {
		return nil, err
	}
	var numbers []int64
	for _, item := range items {
		var pr agentpr.PullRequest
		if json.Unmarshal(item, &pr) != nil {
			return nil, fmt.Errorf("invalid pull request list")
		}
		if pr.Number > 0 && agentpr.SameRepository(pr.Head.Repo, repo) && strings.HasPrefix(pr.Head.Ref, branchPrefix) {
			numbers = append(numbers, pr.Number)
		}
	}
	slices.Sort(numbers)
	return slices.Compact(numbers), nil
}

func observePullRequest(ctx context.Context, client *fixer.GitHubClient, repo fixer.Repository, number int64, options Options) (*Report, error) {
	pr, state, err := agentpr.Read(ctx, client, repo, number)
	if errors.Is(err, agentpr.ErrPublicationPending) && options.EventName == "pull_request_target" {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(2 * time.Second):
			pr, state, err = agentpr.Read(ctx, client, repo, number)
		}
	}
	if errors.Is(err, agentpr.ErrNotPublished) || errors.Is(err, agentpr.ErrPublicationPending) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if pr.State != "open" || pr.Merged || pr.Base.Ref != repo.DefaultBranch {
		return nil, nil
	}
	execution, ok := agentpr.PublicationExecution(state)
	if !ok {
		return nil, fmt.Errorf("engineering publication has no worker execution")
	}
	ciCompletion := options.EventName == "workflow_run" && completedRevision(repo, options.Event) == 0
	if ciCompletion {
		run := options.Event.WorkflowRun
		if run.HeadSHA != pr.Head.SHA && run.HeadSHA != pr.MergeSHA {
			return nil, nil
		}
		if len(run.PullRequests) == 0 && run.HeadBranch != pr.Head.Ref && run.HeadSHA != pr.MergeSHA {
			return nil, nil
		}
	}
	snapshot, err := ReadPullRequest(ctx, client, pr.Base.Ref, pr.Head.SHA, pr.MergeSHA, options.RequiredChecks)
	if err != nil {
		return nil, err
	}
	if ciCompletion && options.Event.WorkflowRun.HeadSHA != pr.Head.SHA && snapshot.TestMergeSHA == "" {
		return nil, nil
	}
	report := &Report{ObservedAt: time.Now().UTC(), Repository: repo.FullName, PullRequest: number, Issue: state.IssueNumber,
		PublicationSHA: state.HostedPublication.SHA, WorkerRun: *execution.Run, Disposition: "observed", CI: snapshot}
	if options.Apply {
		if err := publish(ctx, client, repo, pr, report); err != nil {
			return report, err
		}
	}
	return report, nil
}
