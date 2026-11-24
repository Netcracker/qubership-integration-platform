package fixer

import (
	"context"
	"fmt"
	"net/http"
	"regexp"
	"strings"
)

const ActionsBotLogin = "github-actions[bot]"

var organizationBotLoginPattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9-]*$`)

func validateOrganizationBotLogin(login string) error {
	if !organizationBotLoginPattern.MatchString(login) {
		return fmt.Errorf("AGENT_ORG_BOT_LOGIN must be a GitHub user login when AGENT_USE_ORG_BOT is true")
	}
	return nil
}

func matchesOrganizationBot(user GitHubUser, login string) bool {
	return user.ID > 0 && user.Type == "User" && strings.EqualFold(user.Login, login)
}

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

func NewEngineeringGitHubClient(repository, token, orgToken, orgLogin string, orgMode bool, httpClient *http.Client, baseURL string) (*GitHubClient, error) {
	if orgMode {
		if err := validateOrganizationBotLogin(orgLogin); err != nil {
			return nil, err
		}
	}
	client, err := NewGitHubClient(repository, token, httpClient, baseURL)
	if err == nil && orgMode {
		client.organizationLogin = orgLogin
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
	if c.organizationLogin != "" {
		organization, err := c.User(ctx, c.organizationLogin)
		if err != nil {
			return authors, err
		}
		if organization.ID == actions.ID || !matchesOrganizationBot(organization, c.organizationLogin) {
			return authors, fmt.Errorf("invalid organization bot identity")
		}
		authors.Organization = organization
	}
	return authors, nil
}

func (c *GitHubClient) VerifyOrganizationBot(ctx context.Context, login string) error {
	return c.verifyOrganizationBot(ctx, login, 0)
}

func (c *GitHubClient) verifyOrganizationBot(ctx context.Context, login string, id int64) error {
	if err := validateOrganizationBotLogin(login); err != nil {
		return err
	}
	var user GitHubUser
	if _, err := c.send(ctx, http.MethodGet, "user", nil, &user); err != nil {
		return err
	}
	if !matchesOrganizationBot(user, login) || (id > 0 && user.ID != id) {
		return fmt.Errorf("GH_ACCESS_TOKEN must belong to the user configured in AGENT_ORG_BOT_LOGIN")
	}
	return nil
}

func (c *GitHubClient) WriteIssue(ctx context.Context, author GitHubUser, method, path string, input, output any) error {
	if (method != http.MethodPost && method != http.MethodPatch && method != http.MethodDelete) ||
		(path != "/labels" && !strings.HasPrefix(path, "/issues/")) {
		return fmt.Errorf("invalid issue write")
	}
	if author.Login == ActionsBotLogin || (author == (GitHubUser{}) && c.organizationLogin == "") {
		return c.Request(ctx, method, path, input, output)
	}
	if c.organizationLogin == "" || (author != (GitHubUser{}) && !matchesOrganizationBot(author, c.organizationLogin)) {
		return fmt.Errorf("invalid issue write author")
	}
	if c.organizationToken == "" {
		return fmt.Errorf("GH_ACCESS_TOKEN is required when AGENT_USE_ORG_BOT is true")
	}
	writer := *c
	writer.token = c.organizationToken
	if err := writer.verifyOrganizationBot(ctx, c.organizationLogin, author.ID); err != nil {
		return err
	}
	return writer.Request(ctx, method, path, input, output)
}
