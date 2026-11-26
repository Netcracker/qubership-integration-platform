package main

import (
	"errors"
	"net/http"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docgithub"
)

func TestGitHubCredentials(t *testing.T) {
	for _, tc := range []struct {
		name, mode, repoToken, localToken, projectToken, wantProjectToken string
		wantRepoError, wantProjectError                                   bool
	}{
		{name: "local fallback", localToken: "local-token", wantProjectToken: "local-token"},
		{name: "single repository token", repoToken: "repo-token", wantProjectToken: "repo-token"},
		{name: "organization mode disabled with a saved project token", mode: "false", repoToken: "repo-token", projectToken: "saved-token", wantProjectToken: "repo-token"},
		{name: "organization bot", mode: "true", repoToken: "repo-token", projectToken: "project-token", wantProjectToken: "project-token"},
		{name: "organization bot without project token", mode: "true", repoToken: "repo-token", wantProjectError: true},
		{name: "organization bot ignores local token", mode: "true", localToken: "local-token", projectToken: "project-token", wantRepoError: true, wantProjectError: true},
		{name: "project token alone", projectToken: "project-token", wantRepoError: true, wantProjectError: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Setenv("AGENT_USE_ORG_BOT", tc.mode)
			t.Setenv("GITHUB_TOKEN", tc.repoToken)
			t.Setenv("GH_TOKEN", tc.localToken)
			t.Setenv("AGENT_PROJECT_TOKEN", tc.projectToken)
			repoClient, err := githubRepository("org/repo")
			if (err != nil) != tc.wantRepoError {
				t.Fatalf("repository credentials: %v", err)
			}
			client, err := github("org/repo")
			if (err != nil) != tc.wantProjectError {
				t.Fatalf("repository and project credentials: %v", err)
			}
			requested := false
			original := http.DefaultTransport
			t.Cleanup(func() { http.DefaultTransport = original })
			http.DefaultTransport = fixtureTransport(func(r *http.Request) (*http.Response, error) {
				requested = true
				if r.URL.Path != "/graphql" || r.Header.Get("Authorization") != "Bearer "+tc.wantProjectToken {
					t.Errorf("project discovery used the wrong endpoint or credential")
				}
				return nil, errors.New("end of credential check")
			})
			if tc.mode == "true" && repoClient != nil {
				_, err := docgithub.Discover(t.Context(), repoClient, repoClient.API, 12, 0)
				if requested || err == nil || !strings.Contains(err.Error(), "AGENT_PROJECT_TOKEN") {
					t.Fatalf("repository-only client tried project discovery: %v", err)
				}
			}
			if tc.wantProjectToken == "" {
				return
			}
			if _, err := docgithub.Discover(t.Context(), client, client.API, 12, 0); err == nil || !requested {
				t.Fatalf("project discovery: %v", err)
			}
		})
	}
}
