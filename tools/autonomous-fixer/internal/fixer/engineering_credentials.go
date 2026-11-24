package fixer

import (
	"context"
	"fmt"
	"net/http"
	"strings"
)

const ActionsBotLogin = "github-actions[bot]"
const OrganizationBotLogin = "NetcrackerCLPLCI"

type EngineeringAuthors struct {
	Actions, Organization GitHubUser
}

func (a EngineeringAuthors) Contains(user GitHubUser) bool {
	return user.ID > 0 && (user == a.Actions || user == a.Organization)
}

func (a EngineeringAuthors) Writer() GitHubUser {
	if a.Organization.ID > 0 {
		return a.Organization
	}
	return a.Actions
}

func NewEngineeringGitHubClient(repository, token, orgToken string, orgMode bool, httpClient *http.Client, baseURL string) (*GitHubClient, error) {
	client, err := NewGitHubClient(repository, token, httpClient, baseURL)
	if err == nil && orgMode {
		client.useOrganizationBot = true
		client.organizationToken = orgToken
	}
	return client, err
}

func (c *GitHubClient) EngineeringAuthors(ctx context.Context) (EngineeringAuthors, error) {
	var authors EngineeringAuthors
	actions, err := c.User(ctx, ActionsBotLogin)
	if err != nil {
		return authors, err
	}
	if actions.ID <= 0 || actions.Login != ActionsBotLogin || actions.Type != "Bot" {
		return authors, fmt.Errorf("invalid GitHub Actions bot identity")
	}
	authors.Actions = actions
	if c.useOrganizationBot {
		organization, err := c.User(ctx, OrganizationBotLogin)
		if err != nil {
			return authors, err
		}
		if organization.ID <= 0 || organization.ID == actions.ID || organization.Login != OrganizationBotLogin || organization.Type != "User" {
			return authors, fmt.Errorf("invalid organization bot identity")
		}
		authors.Organization = organization
	}
	return authors, nil
}

func (c *GitHubClient) VerifyOrganizationBot(ctx context.Context) error {
	var user GitHubUser
	if _, err := c.send(ctx, http.MethodGet, "user", nil, &user); err != nil {
		return err
	}
	if user.ID <= 0 || user.Login != OrganizationBotLogin || user.Type != "User" {
		return fmt.Errorf("GH_ACCESS_TOKEN must belong to %s", OrganizationBotLogin)
	}
	return nil
}

func (c *GitHubClient) WriteIssue(ctx context.Context, author GitHubUser, method, path string, input, output any) error {
	if (method != http.MethodPost && method != http.MethodPatch && method != http.MethodDelete) ||
		(path != "/labels" && !strings.HasPrefix(path, "/issues/")) {
		return fmt.Errorf("invalid issue write")
	}
	if author.Login == ActionsBotLogin || (author == (GitHubUser{}) && !c.useOrganizationBot) {
		return c.Request(ctx, method, path, input, output)
	}
	if !c.useOrganizationBot || (author != (GitHubUser{}) && author.Login != OrganizationBotLogin) {
		return fmt.Errorf("invalid issue write author")
	}
	if c.organizationToken == "" {
		return fmt.Errorf("GH_ACCESS_TOKEN is required when AGENT_USE_ORG_BOT is true")
	}
	writer := *c
	writer.token = c.organizationToken
	if err := writer.VerifyOrganizationBot(ctx); err != nil {
		return err
	}
	return writer.Request(ctx, method, path, input, output)
}
