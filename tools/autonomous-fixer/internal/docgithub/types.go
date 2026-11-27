package docgithub

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docpacket"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

type Client struct {
	API        *fixer.GitHubClient
	Repository string
	projectAPI *fixer.GitHubClient
	useOrgBot  bool
}

type ClientOptions struct {
	ProjectToken string
	UseOrgBot    bool
	HTTPClient   *http.Client
	BaseURL      string
}

func NewClient(repository, token string, options ClientOptions) (*Client, error) {
	api, err := fixer.NewGitHubClient(repository, token, options.HTTPClient, options.BaseURL)
	if err != nil {
		return nil, err
	}
	client := &Client{API: api, Repository: repository, useOrgBot: options.UseOrgBot}
	if !options.UseOrgBot {
		client.projectAPI = api
	} else if options.ProjectToken != "" {
		client.projectAPI, err = fixer.NewGitHubClient(repository, options.ProjectToken, options.HTTPClient, options.BaseURL)
		if err != nil {
			return nil, err
		}
	}
	return client, nil
}
func (c *Client) Request(ctx context.Context, method, path string, body, target any) error {
	return c.API.Request(ctx, method, path, body, target)
}
func (c *Client) GraphQL(ctx context.Context, query string, variables map[string]any, target any) error {
	return c.API.GraphQL(ctx, query, variables, target)
}
func (c *Client) projectGraphQL(ctx context.Context, query string, variables map[string]any, target any) error {
	if c.projectAPI == nil {
		return fmt.Errorf("organization bot mode requires AGENT_PROJECT_TOKEN")
	}
	return c.projectAPI.GraphQL(ctx, query, variables, target)
}

type Project struct {
	Number         int    `json:"number"`
	ID             string `json:"id"`
	ItemID         string `json:"item_id"`
	StatusFieldID  string `json:"status_field_id"`
	ReviewOptionID string `json:"review_option_id"`
	DoneOptionID   string `json:"done_option_id"`
	ReviewStatus   string `json:"review_status"`
	DoneStatus     string `json:"done_status"`
}
type Plan struct {
	PacketSHA256    string         `json:"packet_sha256,omitempty"`
	IssueState      string         `json:"issue_state"`
	RequestID       string         `json:"request_id,omitempty"`
	Version         string         `json:"version"`
	ID              string         `json:"id"`
	Repository      string         `json:"repository"`
	IssueNumber     int            `json:"issue_number"`
	IssueNodeID     string         `json:"issue_node_id"`
	IssueTitle      string         `json:"issue_title"`
	IssueBodyHash   string         `json:"issue_body_hash"`
	DefaultBranch   string         `json:"default_branch"`
	BaseSHA         string         `json:"base_sha"`
	Project         Project        `json:"project"`
	ImplementingPRs []docpacket.PR `json:"implementing_prs"`
}

func Digest(v any) string {
	b, _ := json.Marshal(v)
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}
func (p Plan) Validate() error {
	id := p.ID
	p.ID = ""
	if p.Version != "docs-plan-v2" || (p.IssueState != "open" && p.IssueState != "closed") || p.Repository == "" || p.IssueNumber <= 0 || p.IssueNodeID == "" || p.IssueTitle == "" || p.IssueBodyHash == "" || len(p.BaseSHA) != 40 || p.DefaultBranch == "" || p.Project.ID == "" || p.Project.ItemID == "" || p.Project.StatusFieldID == "" || p.Project.ReviewOptionID == "" || p.Project.DoneOptionID == "" || len(p.ImplementingPRs) == 0 || id != Digest(p) {
		return fmt.Errorf("invalid documentation publication plan")
	}
	return nil
}

type Current struct {
	IssueState    string `json:"issue_state"`
	ProjectStatus string `json:"project_status"`
	BaseSHA       string `json:"base_sha"`
}

func decodeRaw(data json.RawMessage, target any) error { return json.Unmarshal(data, target) }
func (c *Client) ActorIdentity(ctx context.Context) (fixer.GitHubUser, error) {
	var d struct {
		Viewer struct {
			Login      string
			DatabaseID int64 `json:"databaseId"`
		}
	}
	err := c.GraphQL(ctx, `query{viewer{login databaseId}}`, nil, &d)
	if err != nil {
		return fixer.GitHubUser{}, err
	}
	if d.Viewer.Login == "" || d.Viewer.DatabaseID <= 0 {
		return fixer.GitHubUser{}, fmt.Errorf("authenticated GitHub identity unavailable")
	}
	return fixer.GitHubUser{ID: d.Viewer.DatabaseID, Login: d.Viewer.Login}, nil
}
