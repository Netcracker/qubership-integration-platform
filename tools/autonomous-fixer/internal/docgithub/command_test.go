package docgithub

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

func TestDocumentationCommandAuthorizesOnlyAnExactCurrentIssueComment(t *testing.T) {
	const timestamp = "2026-11-19T12:00:00Z"
	user := fixer.GitHubUser{ID: 8, Login: "alice", Type: "User"}
	comment := fixer.Comment{ID: 7, Body: "/agent docs", User: user, CreatedAt: timestamp, UpdatedAt: timestamp}
	payload := fixer.GitHubPayload{
		Action: "created", Repository: fixer.Repository{FullName: "org/repo"},
		Issue: fixer.Issue{Number: 11, State: "closed"}, Sender: user, Comment: comment,
	}
	var reads int
	current := comment
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" || r.URL.Path != "/repos/org/repo/issues/comments/7" {
			http.NotFound(w, r)
			return
		}
		reads++
		if reads == 1 {
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		_ = json.NewEncoder(w).Encode(current)
	}))
	t.Cleanup(server.Close)
	api, err := fixer.NewGitHubClient("org/repo", "test", server.Client(), server.URL)
	if err != nil {
		t.Fatal(err)
	}
	client := &Client{API: api, Repository: "org/repo"}
	cmd, err := ReadDocumentationCommand(t.Context(), client, payload, "alice", "alice", "alice")
	if err != nil || cmd != (DocumentationCommand{Issue: 11, CommentID: 7}) || reads != 2 {
		t.Fatal(cmd, err, reads)
	}
	for _, test := range []struct {
		name           string
		change         func(*fixer.GitHubPayload)
		actor, trigger string
	}{
		{"engineering command", func(p *fixer.GitHubPayload) { p.Comment.Body = "/agent fix" }, "alice", "alice"},
		{"command with arguments", func(p *fixer.GitHubPayload) { p.Comment.Body += " now" }, "alice", "alice"},
		{"command with different casing", func(p *fixer.GitHubPayload) { p.Comment.Body = "/agent DOCS" }, "alice", "alice"},
		{"pull request comment", func(p *fixer.GitHubPayload) { p.Issue.PullRequest = json.RawMessage(`{}`) }, "alice", "alice"},
		{"different repository", func(p *fixer.GitHubPayload) { p.Repository.FullName = "other/repo" }, "alice", "alice"},
		{"unauthorized triggering actor", func(*fixer.GitHubPayload) {}, "alice", "bob"},
		{"actor does not own comment", func(*fixer.GitHubPayload) {}, "bob", "bob"},
		{"bot author", func(p *fixer.GitHubPayload) {
			p.Comment.User.Type = "Bot"
			p.Sender = p.Comment.User
		}, "alice", "alice"},
	} {
		t.Run(test.name, func(t *testing.T) {
			changed := payload
			test.change(&changed)
			cmd, err := ReadDocumentationCommand(t.Context(), client, changed, test.actor, test.trigger, "alice")
			if err != nil || cmd.Issue != 0 || reads != 2 {
				t.Fatalf("invalid command advanced: %+v, %v, reads=%d", cmd, err, reads)
			}
		})
	}
	current.UpdatedAt = "2026-11-19T12:01:00Z"
	cmd, err = ReadDocumentationCommand(t.Context(), client, payload, "alice", "alice", "alice")
	if err != nil || cmd.Issue != 0 || reads != 3 {
		t.Fatal(cmd, err, reads)
	}
}

func TestDocumentationCommandReplyIsIdempotentAndLinksRun(t *testing.T) {
	const (
		marker       = "<!-- qip-docs-command:v1 7 -->\n"
		actionsBotID = 99
		orgBotID     = 43
	)
	for _, test := range []struct {
		name         string
		useOrgBot    bool
		legacyReply  bool
		wrongAuthor  bool
		missingLogin bool
	}{
		{name: "Actions bot"},
		{name: "organization bot", useOrgBot: true},
		{name: "historical Actions bot reply", useOrgBot: true, legacyReply: true},
		{name: "wrong organization bot", useOrgBot: true, wrongAuthor: true},
		{name: "missing organization bot login", useOrgBot: true, missingLogin: true},
	} {
		t.Run(test.name, func(t *testing.T) {
			comments := []issueComment{{ID: 1, Body: marker + "Foreign comment", User: fixer.GitHubUser{ID: 3}}}
			if test.legacyReply {
				comments = append(comments, issueComment{ID: 2, Body: marker + "Existing reply", User: fixer.GitHubUser{ID: actionsBotID}})
			}
			author := fixer.GitHubUser{ID: actionsBotID, Login: "github-actions[bot]"}
			if test.useOrgBot {
				author = fixer.GitHubUser{ID: orgBotID, Login: "org-bot"}
			}
			viewer := author.Login
			if test.wrongAuthor {
				viewer = "wrong-bot"
			}
			posts := 0
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				switch {
				case r.URL.Path == "/users/github-actions[bot]":
					_ = json.NewEncoder(w).Encode(fixer.GitHubUser{ID: actionsBotID, Login: "github-actions[bot]"})
				case r.URL.Path == "/repos/org/repo/actions/variables/AGENT_DOCS_PUBLISHER_LOGIN":
					_ = json.NewEncoder(w).Encode(map[string]any{"value": viewer})
				case r.URL.Path == "/repos/org/repo/actions/variables/AGENT_ORG_BOT_LOGIN":
					if test.missingLogin {
						http.NotFound(w, r)
						return
					}
					_ = json.NewEncoder(w).Encode(map[string]any{"value": "org-bot"})
				case r.URL.Path == "/graphql":
					_ = json.NewEncoder(w).Encode(map[string]any{"data": map[string]any{
						"viewer": map[string]any{"databaseId": orgBotID, "login": viewer},
					}})
				case r.URL.Path == "/repos/org/repo/issues/11/comments" && r.Method == "GET":
					_ = json.NewEncoder(w).Encode(comments)
				case r.URL.Path == "/repos/org/repo/issues/11/comments" && r.Method == "POST":
					var body struct{ Body string }
					_ = json.NewDecoder(r.Body).Decode(&body)
					comments = append(comments, issueComment{ID: 2, Body: body.Body, User: author})
					posts++
					w.WriteHeader(502)
				default:
					http.NotFound(w, r)
				}
			}))
			t.Cleanup(server.Close)
			client, err := NewClient("org/repo", "test", ClientOptions{UseOrgBot: test.useOrgBot, HTTPClient: server.Client(), BaseURL: server.URL})
			if err != nil {
				t.Fatal(err)
			}
			cmd := DocumentationCommand{Issue: 11, CommentID: 7}
			for range 2 {
				err = ReplyToDocumentationCommand(t.Context(), client, cmd, "outside-doc-review", 502)
				if test.wrongAuthor || test.missingLogin {
					if err == nil || !strings.Contains(err.Error(), "AGENT_ORG_BOT_LOGIN") || posts != 0 {
						t.Fatal(err, posts)
					}
					return
				}
				if err != nil {
					t.Fatal(err)
				}
			}
			if test.legacyReply {
				if posts != 0 {
					t.Fatal(posts, comments)
				}
				return
			}
			if posts != 1 || !strings.HasPrefix(comments[1].Body, marker) ||
				!strings.Contains(comments[1].Body, "outside **In doc review**") ||
				!strings.Contains(comments[1].Body, "(https://github.com/org/repo/actions/runs/502)") {
				t.Fatal(posts, comments)
			}
		})
	}
}
