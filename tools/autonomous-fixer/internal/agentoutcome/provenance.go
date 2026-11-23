package agentoutcome

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentpr"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/hostedrevision"
)

type Discussion struct {
	ID   int64            `json:"id"`
	Kind string           `json:"kind"`
	User fixer.GitHubUser `json:"user"`
}

type CommitProvenance struct {
	SHA               string           `json:"sha"`
	Author            fixer.GitHubUser `json:"author"`
	Committer         fixer.GitHubUser `json:"committer"`
	Verified          bool             `json:"verified"`
	RevisionCommentID int64            `json:"revision_comment_id,omitempty"`
}

type Provenance struct {
	Status      string             `json:"status"`
	Discussion  []Discussion       `json:"discussion,omitempty"`
	Commits     []CommitProvenance `json:"commits,omitempty"`
	Diagnostics []string           `json:"diagnostics,omitempty"`
}

func userParticipation(user, publisher fixer.GitHubUser) bool {
	return user.ID > 0 && user.Type == "User" && user.ID != publisher.ID
}

func inPublicationWindow(date time.Time, pr agentpr.PullRequest) bool {
	return !date.IsZero() && !date.Before(pr.CreatedAt) && !date.After(pr.ClosedAt)
}

func readProvenance(ctx context.Context, client *fixer.GitHubClient, pr agentpr.PullRequest, comments []fixer.Comment, bot fixer.GitHubUser, git gitRepository, report Report) Provenance {
	result := Provenance{Status: "unknown"}
	record := func(id int64, kind string, user fixer.GitHubUser, date time.Time) {
		if id > 0 && userParticipation(user, pr.User) && inPublicationWindow(date, pr) {
			result.Discussion = append(result.Discussion, Discussion{ID: id, Kind: kind, User: user})
			result.Status = "observed"
		}
	}
	for _, comment := range comments {
		date, _ := time.Parse(time.RFC3339, comment.CreatedAt)
		record(comment.ID, "conversation", comment.User, date)
	}
	for _, kind := range []string{"reviews", "comments"} {
		items, err := client.List(ctx, fmt.Sprintf("/pulls/%d/%s", pr.Number, kind), "")
		if err != nil {
			result.Diagnostics = append(result.Diagnostics, "GitHub pull request "+kind+" are unavailable.")
			continue
		}
		for _, item := range items {
			var entry struct {
				ID          int64            `json:"id"`
				User        fixer.GitHubUser `json:"user"`
				SubmittedAt time.Time        `json:"submitted_at"`
				CreatedAt   time.Time        `json:"created_at"`
			}
			if err := json.Unmarshal(item, &entry); err != nil {
				result.Diagnostics = append(result.Diagnostics, "GitHub returned an invalid pull request "+kind+" entry.")
				break
			}
			if kind == "reviews" {
				record(entry.ID, "review", entry.User, entry.SubmittedAt)
			} else {
				record(entry.ID, "inline-review", entry.User, entry.CreatedAt)
			}
		}
	}
	revisions := hostedrevision.PublishedCommits(comments, bot, report.Repository.ID, pr.Number)
	result.readCommits(ctx, client, pr, git, report.Published, report.Final, revisions)
	return result
}

func (p *Provenance) readCommits(ctx context.Context, client *fixer.GitHubClient, pr agentpr.PullRequest, git gitRepository, published, final Snapshot, revisions map[string]int64) {
	if published.HeadSHA == final.HeadSHA {
		return
	}
	if final.ComparisonBaseSHA == "" || !git.hasCommit(ctx, published.HeadSHA) {
		p.Diagnostics = append(p.Diagnostics, "The commits added after publication are unavailable.")
		return
	}
	shas, err := git.run(ctx, "rev-list", final.HeadSHA, "--not", published.HeadSHA, final.ComparisonBaseSHA)
	if err != nil {
		p.Diagnostics = append(p.Diagnostics, "The commits added after publication are unavailable.")
		return
	}
	expected := make(map[string]bool)
	for _, sha := range strings.Fields(string(shas)) {
		expected[sha] = true
	}
	if len(expected) == 0 {
		return
	}
	items, err := client.List(ctx, fmt.Sprintf("/pulls/%d/commits", pr.Number), "")
	if err != nil {
		p.Diagnostics = append(p.Diagnostics, "GitHub commit attribution is unavailable.")
		return
	}
	for _, item := range items {
		var row struct {
			CommitProvenance
			Commit struct {
				Verification struct {
					Verified bool `json:"verified"`
				} `json:"verification"`
			} `json:"commit"`
		}
		if json.Unmarshal(item, &row) != nil || !expected[row.SHA] {
			continue
		}
		delete(expected, row.SHA)
		row.Verified = row.Commit.Verification.Verified
		row.RevisionCommentID = revisions[row.SHA]
		p.Commits = append(p.Commits, row.CommitProvenance)
		if row.RevisionCommentID == 0 && row.Verified && row.Committer.Login == "web-flow" && userParticipation(row.Author, pr.User) {
			p.Status = "observed"
		}
	}
	if len(expected) != 0 {
		p.Diagnostics = append(p.Diagnostics, "GitHub did not return attribution for every commit added after publication.")
	}
}
