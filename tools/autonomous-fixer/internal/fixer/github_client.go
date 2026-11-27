package fixer

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
)

type HTTPError struct{ Status int }

func (e *HTTPError) Error() string {
	return fmt.Sprintf("GitHub request failed with status %d", e.Status)
}

var errGraphQLPermissionDenied = errors.New("GitHub GraphQL permission denied")

func GitHubWriteRejected(err error) bool {
	var response *HTTPError
	if errors.As(err, &response) {
		switch response.Status {
		case http.StatusBadRequest, http.StatusUnauthorized, http.StatusForbidden, http.StatusNotFound,
			http.StatusUnprocessableEntity, http.StatusTooManyRequests:
			return true
		}
	}
	return errors.Is(err, errGraphQLPermissionDenied)
}

type GitHubClient struct {
	repository, token string
	organizationToken string
	organizationLogin string
	apiVersion        string
	base              *url.URL
	client            *http.Client
}

type githubResponse struct {
	status int
	header http.Header
}

func (c *GitHubClient) RepositoryVariable(ctx context.Context, name string) (string, error) {
	var variable struct {
		Value string `json:"value"`
	}
	if err := c.Request(ctx, "GET", "/actions/variables/"+url.PathEscape(name), nil, &variable); err != nil {
		return "", fmt.Errorf("read repository variable %s: %w", name, err)
	}
	return variable.Value, nil
}

func NewGitHubClient(repository, token string, httpClient *http.Client, baseURL string) (*GitHubClient, error) {
	if !regexp.MustCompile(`^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$`).MatchString(repository) {
		return nil, fmt.Errorf("invalid repository")
	}
	for _, part := range strings.Split(repository, "/") {
		if part == "." || part == ".." {
			return nil, fmt.Errorf("invalid repository")
		}
	}
	if baseURL == "" {
		baseURL = "https://api.github.com"
	}
	base, err := url.Parse(strings.TrimRight(baseURL, "/") + "/")
	if err != nil || (base.Scheme != "https" && base.Scheme != "http") || base.Host == "" || base.User != nil || base.RawQuery != "" || base.Fragment != "" {
		return nil, fmt.Errorf("invalid API URL")
	}
	client := http.Client{Timeout: 30 * time.Second}
	if httpClient != nil {
		client = *httpClient
	}
	if client.Timeout <= 0 {
		client.Timeout = 30 * time.Second
	}
	previous := client.CheckRedirect
	client.CheckRedirect = func(req *http.Request, via []*http.Request) error {
		if req.URL.Scheme != base.Scheme || req.URL.Host != base.Host {
			return fmt.Errorf("cross-origin redirect refused")
		}
		if previous != nil {
			return previous(req, via)
		}
		return http.ErrUseLastResponse
	}
	return &GitHubClient{repository: repository, token: token, apiVersion: "2026-03-10", base: base, client: &client}, nil
}
func (c *GitHubClient) repoPath(path string) (string, error) {
	if (path != "" && (!strings.HasPrefix(path, "/") || strings.HasPrefix(path, "//"))) || strings.ContainsAny(path, "#\\") {
		return "", fmt.Errorf("invalid repository API path")
	}
	value := "repos/" + c.repository + path
	ref, err := url.Parse(value)
	if err != nil {
		return "", fmt.Errorf("invalid API path")
	}
	resolved := c.base.ResolveReference(ref)
	prefix := c.base.Path + "repos/" + c.repository
	if resolved.Path != prefix && !strings.HasPrefix(resolved.Path, prefix+"/") {
		return "", fmt.Errorf("invalid repository API path")
	}
	return value, nil
}
func (c *GitHubClient) send(ctx context.Context, method, path string, body, target any) (githubResponse, error) {
	result, err := c.sendOnce(ctx, method, path, body, target)
	if method != http.MethodGet || body != nil {
		return result, err
	}
	switch result.status {
	case http.StatusInternalServerError, http.StatusBadGateway, http.StatusServiceUnavailable, http.StatusGatewayTimeout:
		timer := time.NewTimer(250 * time.Millisecond)
		defer timer.Stop()
		select {
		case <-ctx.Done():
		case <-timer.C:
		}
		if err := ctx.Err(); err != nil {
			return githubResponse{}, err
		}
		return c.sendOnce(ctx, method, path, body, target)
	default:
		return result, err
	}
}

func (c *GitHubClient) sendOnce(ctx context.Context, method, path string, body, target any) (githubResponse, error) {
	ref, err := url.Parse(path)
	if err != nil {
		return githubResponse{}, fmt.Errorf("invalid API path")
	}
	endpoint := c.base.ResolveReference(ref)
	if endpoint.Scheme != c.base.Scheme || endpoint.Host != c.base.Host || !strings.HasPrefix(endpoint.Path, c.base.Path) {
		return githubResponse{}, fmt.Errorf("invalid API path")
	}
	var reader io.Reader
	if body != nil {
		data, e := json.Marshal(body)
		if e != nil {
			return githubResponse{}, e
		}
		reader = bytes.NewReader(data)
	}
	req, err := http.NewRequestWithContext(ctx, method, endpoint.String(), reader)
	if err != nil {
		return githubResponse{}, fmt.Errorf("invalid GitHub request")
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("Authorization", "Bearer "+c.token)
	req.Header.Set("X-GitHub-Api-Version", c.apiVersion)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	response, err := c.client.Do(req)
	if err != nil {
		return githubResponse{}, fmt.Errorf("GitHub request failed")
	}
	defer response.Body.Close()
	result := githubResponse{status: response.StatusCode, header: response.Header}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return result, &HTTPError{Status: response.StatusCode}
	}
	data, err := io.ReadAll(response.Body)
	if err != nil {
		return result, fmt.Errorf("GitHub response failed")
	}
	if response.StatusCode == 204 || response.StatusCode == 202 && len(bytes.TrimSpace(data)) == 0 {
		return result, nil
	}
	if !json.Valid(data) {
		return result, fmt.Errorf("GitHub returned invalid JSON")
	}
	if target != nil {
		if err = json.Unmarshal(data, target); err != nil {
			return result, fmt.Errorf("GitHub returned invalid JSON")
		}
	}
	return result, nil
}
func (c *GitHubClient) request(ctx context.Context, method, path string, body, target any) (githubResponse, error) {
	p, e := c.repoPath(path)
	if e != nil {
		return githubResponse{}, e
	}
	return c.send(ctx, method, p, body, target)
}
func (c *GitHubClient) Request(ctx context.Context, method, path string, body, target any) error {
	_, e := c.request(ctx, method, path, body, target)
	return e
}

func (c *GitHubClient) ReadPullRequestWithMerge(ctx context.Context, number int64, target any) error {
	if number <= 0 {
		return fmt.Errorf("invalid pull request number")
	}
	// GitHub removed merge_commit_sha from pull request responses in API version 2026-03-10.
	legacy := *c
	legacy.apiVersion = "2022-11-28"
	return legacy.Request(ctx, http.MethodGet, fmt.Sprintf("/pulls/%d", number), nil, target)
}

func (c *GitHubClient) List(ctx context.Context, path, collectionKey string) ([]json.RawMessage, error) {
	p, err := c.repoPath(path)
	if err != nil {
		return nil, err
	}
	parsed, _ := url.Parse(p)
	result := []json.RawMessage{}
	for page := 1; ; page++ {
		q := parsed.Query()
		q.Set("per_page", "100")
		q.Set("page", strconv.Itoa(page))
		parsed.RawQuery = q.Encode()
		var data json.RawMessage
		response, e := c.send(ctx, "GET", parsed.String(), nil, &data)
		if e != nil {
			return nil, e
		}
		if collectionKey != "" {
			var object map[string]json.RawMessage
			if json.Unmarshal(data, &object) != nil {
				return nil, fmt.Errorf("GitHub returned an invalid collection")
			}
			data = object[collectionKey]
		}
		var items []json.RawMessage
		if len(data) == 0 || data[0] != '[' || json.Unmarshal(data, &items) != nil {
			return nil, fmt.Errorf("GitHub returned an invalid collection")
		}
		result = append(result, items...)
		link, present := response.header["Link"]
		if present {
			if !strings.Contains(strings.Join(link, ","), `rel="next"`) {
				break
			}
			if len(items) == 0 {
				return nil, fmt.Errorf("GitHub pagination did not advance")
			}
		} else if len(items) < 100 {
			break
		}
	}
	return result, nil
}
func (c *GitHubClient) User(ctx context.Context, login string) (GitHubUser, error) {
	var user GitHubUser
	if !regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9-]*(\[bot\])?$`).MatchString(login) {
		return user, fmt.Errorf("invalid GitHub login")
	}
	_, err := c.send(ctx, "GET", "users/"+url.PathEscape(login), nil, &user)
	return user, err
}

func (c *GitHubClient) GraphQL(ctx context.Context, query string, variables map[string]any, target any) error {
	var envelope struct {
		Data   json.RawMessage `json:"data"`
		Errors []struct {
			Message string            `json:"message"`
			Type    string            `json:"type"`
			Path    []json.RawMessage `json:"path"`
		} `json:"errors"`
	}
	_, err := c.send(ctx, "POST", "graphql", map[string]any{"query": query, "variables": variables}, &envelope)
	if err != nil {
		return err
	}
	// A permission error in the mutation payload can follow a successful write.
	if len(envelope.Errors) == 1 && envelope.Errors[0].Type == "FORBIDDEN" && envelope.Errors[0].Message != "" && len(envelope.Errors[0].Path) == 1 {
		var field string
		var data map[string]json.RawMessage
		if json.Unmarshal(envelope.Errors[0].Path[0], &field) == nil && field != "" &&
			json.Unmarshal(envelope.Data, &data) == nil && len(data) == 1 && string(data[field]) == "null" {
			return errGraphQLPermissionDenied
		}
	}
	if len(envelope.Errors) > 0 || len(envelope.Data) == 0 || string(envelope.Data) == "null" {
		return fmt.Errorf("GitHub GraphQL request failed")
	}
	if target == nil {
		return nil
	}
	return json.Unmarshal(envelope.Data, target)
}
