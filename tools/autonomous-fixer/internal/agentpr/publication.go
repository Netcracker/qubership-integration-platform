package agentpr

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"regexp"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

var ErrNotPublished = errors.New("pull request has no confirmed engineering publication")
var ErrPublicationPending = errors.New("engineering publication is awaiting confirmation")

var branchPattern = regexp.MustCompile(`^agent/fix/([1-9][0-9]*)/([1-9][0-9]*)-([1-9][0-9]*)$`)

type Ref struct {
	SHA  string           `json:"sha"`
	Ref  string           `json:"ref"`
	Repo fixer.Repository `json:"repo"`
}

type PullRequest struct {
	ID        int64            `json:"id"`
	Number    int64            `json:"number"`
	State     string           `json:"state"`
	Merged    bool             `json:"merged"`
	Draft     bool             `json:"draft"`
	Head      Ref              `json:"head"`
	Base      Ref              `json:"base"`
	User      fixer.GitHubUser `json:"user"`
	CreatedAt time.Time        `json:"created_at"`
	ClosedAt  time.Time        `json:"closed_at"`
	MergedAt  time.Time        `json:"merged_at"`
	MergeSHA  string           `json:"merge_commit_sha"`
}

func Read(ctx context.Context, client *fixer.GitHubClient, repository fixer.Repository, number int64) (PullRequest, *fixer.State, error) {
	var pr PullRequest
	if number <= 0 || repository.ID <= 0 || repository.FullName == "" {
		return pr, nil, fmt.Errorf("invalid pull request identity")
	}
	if err := client.ReadPullRequestWithMerge(ctx, number, &pr); err != nil {
		var response *fixer.HTTPError
		if errors.As(err, &response) && response.Status == http.StatusNotFound {
			return pr, nil, ErrNotPublished
		}
		return pr, nil, err
	}
	parts := branchPattern.FindStringSubmatch(pr.Head.Ref)
	if pr.ID <= 0 || pr.Number != number || !SameRepository(pr.Head.Repo, repository) ||
		!SameRepository(pr.Base.Repo, repository) || len(parts) == 0 {
		return pr, nil, ErrNotPublished
	}
	issue, err := fixer.PositiveID(parts[1])
	if err != nil || issue == number {
		return pr, nil, ErrNotPublished
	}
	bot, err := Bot(ctx, client)
	if err != nil {
		return pr, nil, err
	}
	taskID := fmt.Sprintf("qip-%d", issue)
	store := fixer.NewCommentStore(client, repository.FullName, issue, bot.ID, taskID)
	read, err := store.ReadState(ctx)
	if err != nil {
		return pr, nil, err
	}
	if read.Status != "ok" {
		return pr, nil, fmt.Errorf("engineering publication state requires recovery: %s", read.Reason)
	}
	state := read.State
	if state == nil {
		return pr, nil, ErrNotPublished
	}
	candidate := state.HostedCandidate
	if state.HostedPublication == nil && state.Phase == "awaiting-publication" && candidate != nil &&
		branch(state.IssueNumber, candidate.Run) == pr.Head.Ref {
		return pr, nil, ErrPublicationPending
	}
	publication := state.HostedPublication
	if publication == nil || publication.Number != number || publication.Branch != pr.Head.Ref {
		return pr, nil, ErrNotPublished
	}
	if _, ok := PublicationExecution(state); !ok {
		return pr, nil, fmt.Errorf("engineering publication has no matching worker execution")
	}
	return pr, state, nil
}

func branch(issue int64, run fixer.RunIdentity) string {
	return fmt.Sprintf("agent/fix/%d/%d-%d", issue, run.RunID, run.RunAttempt)
}

func PublicationExecution(state *fixer.State) (fixer.Execution, bool) {
	if state != nil && state.HostedPublication != nil {
		for _, execution := range state.Executions {
			if execution.Mode == "hosted" && execution.Run != nil &&
				branch(state.IssueNumber, *execution.Run) == state.HostedPublication.Branch {
				return execution, true
			}
		}
	}
	return fixer.Execution{}, false
}

func SameRepository(left, right fixer.Repository) bool {
	return left.ID > 0 && left.ID == right.ID && left.FullName == right.FullName
}

func Bot(ctx context.Context, client *fixer.GitHubClient) (fixer.GitHubUser, error) {
	bot, err := client.User(ctx, "github-actions[bot]")
	if err == nil && (bot.ID <= 0 || bot.Type != "Bot" || bot.Login != "github-actions[bot]") {
		err = fmt.Errorf("invalid GitHub Actions bot identity")
	}
	return bot, err
}
