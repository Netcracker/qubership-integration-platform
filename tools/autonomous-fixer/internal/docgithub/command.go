package docgithub

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

const documentationCommand = "/agent docs"

type DocumentationCommand struct {
	Issue     int   `json:"issue"`
	CommentID int64 `json:"comment_id"`
}

func ReadDocumentationCommand(ctx context.Context, c *Client, payload fixer.GitHubPayload, actor, triggeringActor, allowed string) (DocumentationCommand, error) {
	comment := payload.Comment
	if payload.Action != "created" || payload.Repository.FullName != c.Repository || payload.Issue.Number <= 0 ||
		(len(payload.Issue.PullRequest) > 0 && string(payload.Issue.PullRequest) != "null") ||
		comment.ID <= 0 || comment.Body != documentationCommand || comment.CreatedAt == "" || comment.CreatedAt != comment.UpdatedAt ||
		comment.User.ID <= 0 || comment.User.Type != "User" || payload.Sender != comment.User ||
		!strings.EqualFold(actor, comment.User.Login) || !fixer.Allowed(allowed, actor, triggeringActor) {
		return DocumentationCommand{}, nil
	}
	var current fixer.Comment
	if err := c.Request(ctx, "GET", fmt.Sprintf("/issues/comments/%d", comment.ID), nil, &current); err != nil {
		var httpError *fixer.HTTPError
		if errors.As(err, &httpError) && httpError.Status == http.StatusNotFound {
			return DocumentationCommand{}, nil
		}
		return DocumentationCommand{}, err
	}
	if current != comment {
		return DocumentationCommand{}, nil
	}
	return DocumentationCommand{Issue: int(payload.Issue.Number), CommentID: comment.ID}, nil
}

func documentationSkipMessage(reason string) string {
	switch reason {
	case "outside-doc-review":
		return "The issue is outside **In doc review**."
	case "not-in-configured-project":
		return "The issue is outside the configured documentation project."
	case "archived":
		return "The project item is archived."
	case "missing-implementation":
		return "The issue needs an unambiguous merged implementing pull request."
	case "ambiguous-implementations":
		return "The linked implementing pull requests need manual review."
	case "existing-docs-pr":
		return "A documentation pull request already exists for this issue."
	case "active-docs-run", "unidentified-active-docs-run":
		return "A documentation run is already active."
	case "publication-in-progress":
		return "An existing documentation publication needs to finish."
	case "basis-already-published":
		return "The documentation queue already published a result for these implementing changes."
	case "basis-already-attempted":
		return "The documentation queue already attempted these implementing changes."
	case "basis-changed":
		return "The issue or implementing pull requests changed after discovery."
	case "intake-unavailable", "prior-work-unavailable":
		return "The documentation queue could not verify eligibility."
	case "schedule-apply-disabled":
		return "Documentation application is disabled."
	default:
		return ""
	}
}

func ReplyToDocumentationCommand(ctx context.Context, c *Client, command DocumentationCommand, reason string, runID int64) error {
	message := documentationSkipMessage(reason)
	if command.Issue <= 0 || command.CommentID <= 0 || message == "" || runID <= 0 {
		return fmt.Errorf("invalid documentation command reply")
	}
	bot, err := c.API.User(ctx, "github-actions[bot]")
	if err != nil || bot.ID <= 0 {
		return fmt.Errorf("GitHub Actions comment author is unavailable")
	}
	path := fmt.Sprintf("/issues/%d/comments", command.Issue)
	marker := fmt.Sprintf("<!-- qip-docs-command:v1 %d -->\n", command.CommentID)
	found := func() (bool, error) {
		rows, err := c.API.List(ctx, path, "")
		if err != nil {
			return false, err
		}
		for _, raw := range rows {
			var comment issueComment
			if err := json.Unmarshal(raw, &comment); err != nil {
				return false, err
			}
			if comment.User.ID == bot.ID && strings.HasPrefix(comment.Body, marker) {
				return true, nil
			}
		}
		return false, nil
	}
	if exists, err := found(); err != nil || exists {
		return err
	}
	body := fmt.Sprintf("%s\n%s [Read the documentation run](https://github.com/%s/actions/runs/%d).", marker, message, c.Repository, runID)
	requestErr := c.Request(ctx, "POST", path, map[string]string{"body": body}, nil)
	if exists, err := found(); err == nil && exists {
		return nil
	}
	if requestErr != nil {
		return fmt.Errorf("documentation command reply was not confirmed: %w", requestErr)
	}
	return fmt.Errorf("documentation command reply was not confirmed")
}
