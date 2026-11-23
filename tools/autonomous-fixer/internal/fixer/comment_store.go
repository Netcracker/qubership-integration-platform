package fixer

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"reflect"
	"regexp"
	"strings"
	"unicode/utf8"
)

const stateMarker = "qip-agent-state:"

func escapeComment(text string) string {
	var result strings.Builder
	for _, r := range text {
		switch r {
		case '&':
			result.WriteString("&amp;")
		case '<':
			result.WriteString("&lt;")
		case '>':
			result.WriteString("&gt;")
		case ':':
			result.WriteString("&#58;")
		default:
			if strings.ContainsRune("\\`*_{}[]()#+.!|~-", r) {
				result.WriteByte('\\')
			}
			result.WriteRune(r)
		}
	}
	return result.String()
}
func EncodeStateComment(state *State) (string, error) {
	if err := ValidateState(state); err != nil {
		return "", err
	}
	data, err := json.Marshal(state)
	if err != nil {
		return "", err
	}
	lines := []string{"## Agent task", "", "Phase: " + escapeComment(state.Phase), fmt.Sprintf("Revision: %d", state.Revision), "", "Commands: `/agent status`, `/agent stop`, `/agent resume`, `/agent retry`."}
	if candidate := state.HostedCandidate; candidate != nil && state.Phase == "awaiting-publication" {
		lines = append(lines, "", fmt.Sprintf("The author reported a fix. Review [worker run %d, attempt %d](https://github.com/%s/actions/runs/%d/attempts/%d) before publication.",
			candidate.Run.RunID, candidate.Run.RunAttempt, state.Repository, candidate.Run.RunID, candidate.Run.RunAttempt))
	}
	if publication := state.HostedPublication; publication != nil {
		lines = append(lines, "", fmt.Sprintf("Draft pull request #%d was published. It awaits checks and human review.", publication.Number))
	}
	for _, q := range state.Decisions {
		if q.Status != "open" {
			continue
		}
		question := escapeComment(q.Question)
		for _, effect := range state.Effects {
			if effect.Kind == "report-hosted" && effect.Payload.DecisionID == q.ID && effect.Result != nil {
				question = fmt.Sprintf("[Read the worker's request](#issuecomment-%d).", effect.Result.CommentID)
				break
			}
		}
		lines = append(lines, "", fmt.Sprintf("Question %s (version %d, contract %d):", escapeComment(q.ID), q.Version, q.ContractVersion), question, "", "Resume condition: "+escapeComment(q.ResumeCondition), "", "Answer with: "+escapeComment(fmt.Sprintf("/agent answer %s %d %d <answer>", q.ID, q.Version, q.ContractVersion)))
		if q.Kind == "no-change" {
			lines = append(lines, "Replace the answer placeholder with `confirm` to accept no change, or `continue` to request more work.")
		} else {
			lines = append(lines, "Replace the answer placeholder with your decision.")
		}
	}
	lines = append(lines, "", "<!-- qip-agent-state:v1 "+base64.StdEncoding.EncodeToString(data)+" -->")
	body := strings.Join(lines, "\n")
	if utf8.RuneCountInString(body) > 65536 {
		return "", fmt.Errorf("task state exceeds the GitHub comment limit")
	}
	return body, nil
}
func DecodeStateComment(body string) (*State, error) {
	matches := regexp.MustCompile(`<!-- qip-agent-state:v1 ([A-Za-z0-9+/]+={0,2}) -->`).FindAllStringSubmatch(body, -1)
	if len(matches) != 1 || strings.Count(body, stateMarker) != 1 {
		return nil, fmt.Errorf("invalid state marker")
	}
	encoded := matches[0][1]
	data, err := base64.StdEncoding.Strict().DecodeString(encoded)
	if err != nil || base64.StdEncoding.EncodeToString(data) != encoded || !utf8.Valid(data) {
		return nil, fmt.Errorf("invalid state encoding")
	}
	var state State
	if err = DecodeJSON(data, &state); err != nil {
		return nil, err
	}
	if err = ValidateState(&state); err != nil {
		return nil, err
	}
	return &state, nil
}

type CommentStore struct {
	client        *GitHubClient
	repository    string
	issue, botID  int64
	taskID        string
	canonicalID   int64
	observed      *State
	read, unknown bool
}

func NewCommentStore(client *GitHubClient, repo string, issue int64, botID int64, taskID string) *CommentStore {
	return &CommentStore{client: client, repository: repo, issue: issue, botID: botID, taskID: taskID}
}
func (s *CommentStore) binding(state *State) bool {
	return state != nil && state.Repository == s.repository && state.IssueNumber == s.issue && state.TaskID == s.taskID
}
func (s *CommentStore) inspect(ctx context.Context) (*State, error) {
	if s.client == nil || s.issue <= 0 || s.botID <= 0 || s.taskID == "" {
		return nil, fmt.Errorf("invalid comment store identity")
	}
	items, err := s.client.List(ctx, fmt.Sprintf("/issues/%d/comments", s.issue), "")
	if err != nil {
		return nil, err
	}
	var marked []Comment
	for _, item := range items {
		var comment Comment
		if json.Unmarshal(item, &comment) != nil {
			return nil, fmt.Errorf("invalid comment")
		}
		if comment.User.ID == s.botID && comment.User.Type == "Bot" && strings.Contains(comment.Body, stateMarker) {
			marked = append(marked, comment)
		}
	}
	if len(marked) > 1 {
		return nil, fmt.Errorf("duplicate state comments")
	}
	if s.canonicalID != 0 && (len(marked) != 1 || marked[0].ID != s.canonicalID) {
		return nil, fmt.Errorf("canonical state comment changed")
	}
	if len(marked) == 0 {
		return nil, nil
	}
	s.canonicalID = marked[0].ID
	if s.canonicalID <= 0 {
		return nil, fmt.Errorf("invalid state comment identity")
	}
	state, err := DecodeStateComment(marked[0].Body)
	if err != nil {
		return nil, err
	}
	if !s.binding(state) {
		return nil, fmt.Errorf("state belongs to another task")
	}
	return state, nil
}
func (s *CommentStore) ReadState(ctx context.Context) (StateRead, error) {
	if s.unknown {
		return StateRead{Status: "unknown", Reason: "state-comment-invalid"}, nil
	}
	state, err := s.inspect(ctx)
	if err != nil {
		s.unknown = true
		return StateRead{Status: "unknown", Reason: "state-comment-unavailable"}, nil
	}
	s.read = true
	s.observed = cloneState(state)
	return StateRead{Status: "ok", State: state}, nil
}
func (s *CommentStore) WriteState(ctx context.Context, state *State) error {
	if s.unknown || !s.read {
		return fmt.Errorf("read task state before writing")
	}
	if err := ValidateState(state); err != nil {
		return err
	}
	revision := 0
	if s.observed != nil {
		revision = s.observed.Revision
	}
	if !s.binding(state) || state.Revision != revision+1 {
		return fmt.Errorf("task state revision or identity changed")
	}
	body, err := EncodeStateComment(state)
	if err != nil {
		return err
	}
	live, err := s.inspect(ctx)
	if err != nil {
		return err
	}
	if !reflect.DeepEqual(live, s.observed) {
		return fmt.Errorf("task state changed before writing")
	}
	payload := struct {
		Body string `json:"body"`
	}{body}
	if s.canonicalID == 0 {
		var created Comment
		err = s.client.Request(ctx, "POST", fmt.Sprintf("/issues/%d/comments", s.issue), payload, &created)
		if err == nil && created.ID > 0 {
			s.canonicalID = created.ID
		}
		return err
	}
	return s.client.Request(ctx, "PATCH", fmt.Sprintf("/issues/comments/%d", s.canonicalID), payload, nil)
}
