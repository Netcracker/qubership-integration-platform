package hostedrevision

import (
	"cmp"
	"context"
	"encoding/json"
	"fmt"
	"regexp"
	"slices"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

const maxFeedback = 5
const maxFeedbackBytes = 4096
const maxInstructionBytes = 2000

var feedbackAnchor = regexp.MustCompile(`^#(discussion_r|pullrequestreview-|issuecomment-)([1-9][0-9]*)$`)

type selection struct {
	anchor, kind string
	id           int64
	actor        fixer.GitHubUser
	head         string
}

type Feedback struct {
	URL  string `json:"url"`
	Body string `json:"body"`
	Path string `json:"path,omitempty"`
	Line int    `json:"line,omitempty"`
	Kind string `json:"kind,omitempty"`
}

func parseCommand(body, pullURL string) ([]selection, string, error) {
	line, instruction, _ := strings.Cut(strings.TrimSpace(body), "\n")
	fields := strings.Fields(line)
	instruction = strings.TrimSpace(instruction)
	if len(fields) < 3 || len(fields) > 2+maxFeedback || fields[0] != "/agent" || fields[1] != "revise" || len(instruction) > maxInstructionBytes {
		return nil, "", fmt.Errorf("use /agent revise followed by one to five discussion links from this pull request, with an optional instruction on the next line")
	}
	seen := map[string]bool{}
	var selections []selection
	for _, field := range fields[2:] {
		anchor := strings.TrimPrefix(field, pullURL)
		parts := feedbackAnchor.FindStringSubmatch(anchor)
		if parts == nil || seen[anchor] {
			return nil, "", fmt.Errorf("each discussion reference must be distinct and belong to this pull request")
		}
		id, err := fixer.PositiveID(parts[2])
		if err != nil {
			return nil, "", err
		}
		seen[anchor] = true
		selections = append(selections, selection{anchor: anchor, kind: parts[1], id: id})
	}
	return selections, instruction, nil
}

type discussion struct {
	ID          int64            `json:"id"`
	Body        string           `json:"body"`
	PullAPIURL  string           `json:"pull_request_url"`
	IssueURL    string           `json:"issue_url"`
	Path        string           `json:"path"`
	Line        int              `json:"line"`
	User        fixer.GitHubUser `json:"user"`
	ReviewID    int64            `json:"pull_request_review_id"`
	CommitID    string           `json:"commit_id"`
	State       string           `json:"state"`
	SubmittedAt time.Time        `json:"submitted_at"`
}

func loadFeedback(ctx context.Context, client *fixer.GitHubClient, repository string, pull int64, selections []selection) ([]Feedback, error) {
	var feedback []Feedback
	seen := map[string]bool{}
	appendComment := func(anchor string, comment discussion, automatic bool) error {
		if seen[anchor] {
			return nil
		}
		if !automatic && len(feedback) >= maxFeedback {
			return fmt.Errorf("select at most five review messages, including comments from a linked review")
		}
		if automatic && len(comment.Body) > maxFeedbackBytes {
			body := comment.Body[:maxFeedbackBytes]
			for !utf8.ValidString(body) {
				body = body[:len(body)-1]
			}
			comment.Body = body + "\n\n[Excerpt truncated]"
		}
		if strings.TrimSpace(comment.Body) == "" || (!automatic && len(comment.Body) > maxFeedbackBytes) {
			return fmt.Errorf("each selected discussion needs a body of at most 4096 bytes")
		}
		seen[anchor] = true
		feedback = append(feedback, Feedback{
			URL: pullURL(repository, pull) + anchor, Body: comment.Body, Path: comment.Path, Line: comment.Line,
		})
		return nil
	}
	api := "https://api.github.com/repos/" + repository
	pullAPI := fmt.Sprintf("%s/pulls/%d", api, pull)
	issueAPI := fmt.Sprintf("%s/issues/%d", api, pull)
	for _, s := range selections {
		if s.kind == "pullrequestreview-" {
			review := fmt.Sprintf("/pulls/%d/reviews/%d", pull, s.id)
			automatic := s.actor.ID > 0
			var summary discussion
			if err := client.Request(ctx, "GET", review, nil, &summary); err != nil {
				if automatic && notFound(err) {
					return nil, ErrUnauthorized
				}
				return nil, err
			}
			if automatic && (summary.User != s.actor || summary.CommitID != s.head || summary.State != "CHANGES_REQUESTED") {
				return nil, ErrUnauthorized
			}
			if summary.ID != s.id {
				return nil, fmt.Errorf("the review differs from the selected discussion")
			}
			var comments []discussion
			if automatic {
				raw, err := client.List(ctx, review+"/comments", "")
				if err != nil {
					return nil, err
				}
				for _, item := range raw {
					var comment discussion
					if err := json.Unmarshal(item, &comment); err != nil {
						return nil, err
					}
					if comment.User != s.actor || comment.ReviewID != s.id {
						return nil, ErrUnauthorized
					}
					comments = append(comments, comment)
				}
				slices.SortFunc(comments, func(a, b discussion) int { return cmp.Compare(a.ID, b.ID) })
			} else if err := client.Request(ctx, "GET", fmt.Sprintf("%s/comments?per_page=%d", review, maxFeedback+1), nil, &comments); err != nil {
				return nil, err
			}
			hasSummary := strings.TrimSpace(summary.Body) != ""
			if !hasSummary && len(comments) == 0 {
				if automatic {
					return nil, ErrUnauthorized
				}
				return nil, fmt.Errorf("the selected review has no feedback")
			}
			if hasSummary {
				if err := appendComment(s.anchor, summary, automatic); err != nil {
					return nil, err
				}
			}
			for _, comment := range comments {
				if comment.ID <= 0 || comment.PullAPIURL != pullAPI {
					return nil, fmt.Errorf("a selected review comment belongs to another pull request")
				}
				if err := appendComment(fmt.Sprintf("#discussion_r%d", comment.ID), comment, automatic); err != nil {
					return nil, err
				}
			}
			continue
		}
		endpoint := fmt.Sprintf("/pulls/comments/%d", s.id)
		if s.kind == "issuecomment-" {
			endpoint = fmt.Sprintf("/issues/comments/%d", s.id)
		}
		var comment discussion
		if err := client.Request(ctx, "GET", endpoint, nil, &comment); err != nil {
			return nil, err
		}
		if comment.ID != s.id || (s.kind == "discussion_r" && comment.PullAPIURL != pullAPI) ||
			(s.kind == "issuecomment-" && comment.IssueURL != issueAPI) {
			return nil, fmt.Errorf("a selected discussion belongs to another pull request")
		}
		if err := appendComment(s.anchor, comment, false); err != nil {
			return nil, err
		}
	}
	return feedback, nil
}
