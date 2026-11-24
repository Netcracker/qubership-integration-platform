package main

import "testing"

func TestGitHubCredentials(t *testing.T) {
	for _, tc := range []struct {
		name, mode, repoToken, localToken, projectToken string
		wantRepoError, wantProjectError                 bool
	}{
		{name: "local fallback", localToken: "local-token"},
		{name: "single repository token", repoToken: "repo-token"},
		{name: "organization bot", mode: "true", repoToken: "repo-token", projectToken: "project-token"},
		{name: "organization bot without project token", mode: "true", repoToken: "repo-token", wantProjectError: true},
		{name: "organization bot ignores local token", mode: "true", localToken: "local-token", projectToken: "project-token", wantRepoError: true, wantProjectError: true},
		{name: "project token alone", projectToken: "project-token", wantRepoError: true, wantProjectError: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Setenv("AGENT_USE_ORG_BOT", tc.mode)
			t.Setenv("GITHUB_TOKEN", tc.repoToken)
			t.Setenv("GH_TOKEN", tc.localToken)
			t.Setenv("AGENT_PROJECT_TOKEN", tc.projectToken)
			if _, err := githubRepository("org/repo"); (err != nil) != tc.wantRepoError {
				t.Errorf("repository credentials: %v", err)
			}
			if _, err := github("org/repo"); (err != nil) != tc.wantProjectError {
				t.Errorf("repository and project credentials: %v", err)
			}
		})
	}
}
