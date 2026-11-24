package fixer

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

func TestEngineeringIssueWritesSelectCredentialByAuthor(t *testing.T) {
	actions := GitHubUser{ID: 42, Login: ActionsBotLogin, Type: "Bot"}
	organization := GitHubUser{ID: 43, Login: OrganizationBotLogin, Type: "User"}
	for _, tc := range []struct {
		name, orgToken, wantWriter string
		orgMode                    bool
		author                     GitHubUser
	}{
		{name: "default", orgToken: "org-token", wantWriter: "actions-token"},
		{name: "organization bot", orgMode: true, orgToken: "org-token", wantWriter: "org-token"},
		{name: "legacy owner", orgMode: true, orgToken: "org-token", author: actions, wantWriter: "actions-token"},
		{name: "organization owner", orgMode: true, orgToken: "org-token", author: organization, wantWriter: "org-token"},
		{name: "missing organization token", orgMode: true},
		{name: "wrong organization token", orgMode: true, orgToken: "other-token"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var writes atomic.Int32
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
				switch r.Method + " " + r.URL.Path {
				case "GET /users/" + ActionsBotLogin:
					if token != "actions-token" {
						t.Errorf("read Actions bot with %q", token)
					}
					_ = json.NewEncoder(w).Encode(actions)
				case "GET /users/" + OrganizationBotLogin:
					if !tc.orgMode || token != "actions-token" {
						t.Errorf("read organization bot with %q", token)
					}
					_ = json.NewEncoder(w).Encode(organization)
				case "GET /user":
					if token == "org-token" {
						_ = json.NewEncoder(w).Encode(organization)
					} else {
						_ = json.NewEncoder(w).Encode(GitHubUser{ID: 44, Login: "other", Type: "User"})
					}
				case "PATCH /repos/org/repo/issues/comments/10":
					writes.Add(1)
					if token != tc.wantWriter {
						t.Errorf("comment write used %q, want %q", token, tc.wantWriter)
					}
					_, _ = w.Write([]byte(`{}`))
				case "POST /repos/org/repo/actions/workflows/agent-work.yaml/dispatches":
					if token != "actions-token" {
						t.Errorf("dispatch used %q", token)
					}
					w.WriteHeader(http.StatusNoContent)
				default:
					t.Errorf("unexpected request %s %s", r.Method, r.URL.Path)
					http.NotFound(w, r)
				}
			}))
			defer server.Close()
			client, err := NewEngineeringGitHubClient("org/repo", "actions-token", tc.orgToken, tc.orgMode, server.Client(), server.URL)
			if err != nil {
				t.Fatal(err)
			}
			authors, err := client.EngineeringAuthors(t.Context())
			if err != nil || !authors.Contains(actions) || authors.Contains(organization) != tc.orgMode {
				t.Fatalf("authors = %+v, %v", authors, err)
			}
			if err := client.Request(t.Context(), "POST", "/actions/workflows/agent-work.yaml/dispatches", nil, nil); err != nil {
				t.Fatal(err)
			}
			err = client.WriteIssue(t.Context(), tc.author, "PATCH", "/issues/comments/10", nil, nil)
			if tc.wantWriter == "" {
				if err == nil || writes.Load() != 0 {
					t.Fatalf("write error = %v, writes = %d", err, writes.Load())
				}
			} else if err != nil || writes.Load() != 1 {
				t.Fatalf("write error = %v, writes = %d", err, writes.Load())
			}
		})
	}
}
