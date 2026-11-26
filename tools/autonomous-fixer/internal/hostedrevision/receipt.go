package hostedrevision

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"slices"
	"strconv"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentsignature"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/autorequest"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

const receiptMarker = "<!-- qip-agent-revision:v1 "
const resultMarker = "<!-- qip-agent-revision-result:v1 "

var commitSHA = regexp.MustCompile(`^[0-9a-f]{40}$`)
var digestSHA = regexp.MustCompile(`^[0-9a-f]{64}$`)
var publishedMarker = regexp.MustCompile(`^` + regexp.QuoteMeta(resultMarker) + `([1-9][0-9]*):([1-9][0-9]*):([1-9][0-9]*):[1-9][0-9]*:1:([0-9a-f]{40}):published:([0-9a-f]{40}) -->$`)

func trustedComment(comment fixer.Comment, authors fixer.EngineeringAuthors) bool {
	return comment.ID > 0 && authors.Contains(comment.User)
}

func PublishedCommits(comments []fixer.Comment, authors fixer.EngineeringAuthors, repoID, pullNumber int64) map[string]int64 {
	return publishedComments(comments, authors, repoID, pullNumber, false)
}

func PublishedRevisions(comments []fixer.Comment, authors fixer.EngineeringAuthors, repoID, pullNumber int64) map[string]int64 {
	return publishedComments(comments, authors, repoID, pullNumber, true)
}

func publishedComments(comments []fixer.Comment, authors fixer.EngineeringAuthors, repoID, pullNumber int64, requestID bool) map[string]int64 {
	published := map[string]int64{}
	repository, pull := strconv.FormatInt(repoID, 10), strconv.FormatInt(pullNumber, 10)
	for _, comment := range comments {
		if !trustedComment(comment, authors) || strings.HasPrefix(comment.Body, autorequest.Prefix) {
			continue
		}
		for line := range strings.SplitSeq(comment.Body, "\n") {
			fields := publishedMarker.FindStringSubmatch(line)
			if fields == nil || fields[1] != repository || fields[2] != pull || fields[4] == fields[5] {
				continue
			}
			id := comment.ID
			if requestID {
				var err error
				id, err = fixer.PositiveID(fields[3])
				if err != nil {
					continue
				}
			}
			published[fields[5]] = id
		}
	}
	return published
}

type receipt struct {
	Assignment
	CheckpointSHA string `json:"checkpointSha256"`
}

func (c *Controller) trustedComments(ctx context.Context) ([]fixer.Comment, error) {
	raw, err := c.cfg.Client.List(ctx, fmt.Sprintf("/issues/%d/comments", c.pullNumber), "")
	if err != nil {
		return nil, err
	}
	comments := make([]fixer.Comment, 0, len(raw))
	for _, item := range raw {
		var comment fixer.Comment
		if err := json.Unmarshal(item, &comment); err != nil {
			return nil, err
		}
		if trustedComment(comment, c.authors) {
			comments = append(comments, comment)
		}
	}
	return comments, nil
}

func (c *Controller) receipts(ctx context.Context) (map[int64]Assignment, error) {
	comments, err := c.trustedComments(ctx)
	if err != nil {
		return nil, err
	}
	return Assignments(comments, c.authors, c.repo.ID, c.pullNumber)
}

func Assignments(comments []fixer.Comment, authors fixer.EngineeringAuthors, repoID, pullNumber int64) (map[int64]Assignment, error) {
	receipts := map[int64]Assignment{}
	for _, comment := range comments {
		if !trustedComment(comment, authors) || strings.HasPrefix(comment.Body, autorequest.Prefix) {
			continue
		}
		for line := range strings.SplitSeq(comment.Body, "\n") {
			if !strings.HasPrefix(line, receiptMarker) || !strings.HasSuffix(line, " -->") {
				continue
			}
			encoded := strings.TrimSuffix(strings.TrimPrefix(line, receiptMarker), " -->")
			data, err := base64.StdEncoding.Strict().DecodeString(encoded)
			if err != nil {
				return nil, err
			}
			var stored receipt
			if err := fixer.DecodeJSON(data, &stored); err != nil {
				return nil, err
			}
			a := stored.Assignment
			a.checkpointSHA = stored.CheckpointSHA
			if !digestSHA.MatchString(a.checkpointSHA) || a.RepositoryID != repoID || a.PullNumber != pullNumber || a.PullID <= 0 ||
				a.CommentID <= 0 || a.Issue <= 0 || !commitSHA.MatchString(a.Head) || !commitSHA.MatchString(a.WorkflowSHA) ||
				a.Run.RunID <= 0 || a.Run.RunAttempt != 1 || a.OriginRun.RunID <= 0 || a.OriginRun.RunAttempt <= 0 ||
				(a.Automatic && (!authors.Contains(a.Actor) || len(a.ReviewIDs)+len(a.FailureKeys) == 0)) {
				return nil, fmt.Errorf("the revision receipt does not match this pull request")
			}
			if _, ok := receipts[a.CommentID]; ok {
				return nil, fmt.Errorf("the pull request has duplicate revision receipts")
			}
			receipts[a.CommentID] = a
		}
	}
	return receipts, nil
}

func (a Assignment) ConsumedReviews() []int64 {
	ids := slices.Clone(a.ReviewIDs)
	for _, feedback := range a.Feedback {
		_, anchor, ok := strings.Cut(feedback.URL, "#pullrequestreview-")
		if !ok {
			continue
		}
		id, err := fixer.PositiveID(anchor)
		if err == nil && !slices.Contains(ids, id) {
			ids = append(ids, id)
		}
	}
	return ids
}

func (c *Controller) post(ctx context.Context, body string) error {
	var result fixer.Comment
	writer := c.authors.Writer()
	postErr := c.cfg.Client.CreateIssueComment(ctx, c.pullNumber, writer, body, &result)
	if postErr == nil && result.ID > 0 && result.User == writer && result.Body == body {
		return nil
	}
	comments, err := c.trustedComments(ctx)
	for _, comment := range comments {
		if comment.Body == body {
			return nil
		}
	}
	return errors.Join(postErr, err, fmt.Errorf("GitHub did not confirm the revision comment"))
}

func (c *Controller) postReceipt(ctx context.Context, a Assignment) error {
	if a.Automatic {
		// The checkpoint binds the feedback loaded from the selected reviews and checks.
		a.Feedback = nil
	}
	data, err := json.Marshal(receipt{Assignment: a, CheckpointSHA: a.checkpointSHA})
	if err != nil {
		return err
	}
	body := fmt.Sprintf("Accepted [revision request](#issuecomment-%d) from @%s at `%s`.", a.CommentID, a.Actor.Login, a.Head)
	if a.Automatic {
		body = fmt.Sprintf("Accepted [automatic revision request](#issuecomment-%d) at `%s`.", a.CommentID, a.Head)
	}
	body += fmt.Sprintf(" Follow the [revision run](%s).\n\n%s%s -->", evidenceURL(c.repo.FullName, a.Run), receiptMarker, base64.StdEncoding.EncodeToString(data))
	if len(body) > 65536 {
		return fmt.Errorf("the selected feedback exceeds the revision receipt limit")
	}
	return c.post(ctx, body)
}

type Result struct {
	Status    string
	Commit    string
	Signature *agentsignature.Signature
}

func resultPrefix(a Assignment) string {
	return fmt.Sprintf("%s%d:%d:%d:%d:%d:%s:", resultMarker, a.RepositoryID, a.PullNumber, a.CommentID, a.Run.RunID, a.Run.RunAttempt, a.Head)
}

func RevisionResultRecorded(comments []fixer.Comment, authors fixer.EngineeringAuthors, a Assignment) bool {
	return RevisionResultStatus(comments, authors, a) != ""
}

func RevisionResultStatus(comments []fixer.Comment, authors fixer.EngineeringAuthors, a Assignment) string {
	prefix := resultPrefix(a)
	for _, comment := range comments {
		if !trustedComment(comment, authors) || strings.HasPrefix(comment.Body, autorequest.Prefix) {
			continue
		}
		for line := range strings.SplitSeq(comment.Body, "\n") {
			outcome, hasPrefix := strings.CutPrefix(line, prefix)
			outcome, hasSuffix := strings.CutSuffix(outcome, " -->")
			if !hasPrefix || !hasSuffix {
				continue
			}
			status, commit, _ := strings.Cut(outcome, ":")
			switch status {
			case "published":
				if commitSHA.MatchString(commit) && commit != a.Head {
					return status
				}
			case "stale", "closed", "withdrawn", "needs-human", "blocked", "failed":
				if commit == "-" {
					return status
				}
			}
		}
	}
	return ""
}

func (c *Controller) HasResult(ctx context.Context, a Assignment) (bool, error) {
	comments, err := c.trustedComments(ctx)
	if err != nil {
		return false, err
	}
	return RevisionResultRecorded(comments, c.authors, a), nil
}

func (c *Controller) Report(ctx context.Context, a Assignment, result Result) error {
	if a.Run.RunID != c.cfg.Run.RunID || a.CommentID != c.commentID {
		return ErrUnauthorized
	}
	published := "-"
	var outcome string
	switch result.Status {
	case "published":
		if !commitSHA.MatchString(result.Commit) || result.Commit == a.Head {
			return fmt.Errorf("the revision result needs a published commit")
		}
		published = result.Commit
		outcome = fmt.Sprintf("Published [commit `%s`](https://github.com/%s/commit/%s) to this pull request.", result.Commit[:12], c.repo.FullName, result.Commit)
	case "stale":
		outcome = "The pull request head or target changed. The revision left the branch untouched."
	case "closed":
		outcome = "The pull request closed before publication. The revision left the branch untouched."
	case "withdrawn":
		outcome = "The revision's authorization changed. The revision left the branch untouched."
	case "needs-human":
		outcome = "The author requested a maintainer decision. Read the report in the run evidence."
	case "blocked":
		outcome = "The author reported a blocker. Read the report in the run evidence."
	case "failed":
		outcome = "The workflow ended before producing a sealed result. See the run for details."
	default:
		return fmt.Errorf("unknown revision result")
	}
	done, err := c.HasResult(ctx, a)
	if err != nil || done {
		return err
	}
	body := fmt.Sprintf("### Agent revision\n\n[Revision request](#issuecomment-%d)\n\n%s\n\n[Run and verification evidence](%s)\n\n%s%s:%s -->",
		a.CommentID, outcome, evidenceURL(c.repo.FullName, a.Run), resultPrefix(a), result.Status, published)
	if result.Signature != nil {
		body = result.Signature.Append(body)
	}
	return c.post(ctx, body)
}
