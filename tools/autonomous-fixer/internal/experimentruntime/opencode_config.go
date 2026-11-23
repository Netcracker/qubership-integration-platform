package experimentruntime

import (
	"encoding/json"
	"fmt"
	"path/filepath"
	"strings"
)

const (
	HostedOpenCodeAuthor   = "qip-author"
	HostedOpenCodeReviewer = "qip-reviewer"
	OpenRouterRelayKey     = "qip-local-provider-relay"
)

// OpenCodeConfig pins auxiliary requests to the role's model and variant.
// OpenCode 2.2.5 otherwise chooses a smaller model or effort for session titles.
func OpenCodeConfig(p Profile, workspace string) (string, error) {
	config, err := openCodeConfig(p, workspace)
	if err != nil {
		return "", err
	}
	b, err := json.Marshal(config)
	return string(b), err
}

func openCodeConfig(p Profile, workspace string) (map[string]any, error) {
	if p.Runtime != "opencode" {
		return nil, fmt.Errorf("OpenCode configuration requires an OpenCode profile")
	}
	if _, err := BuildCommand(p, workspace, ""); err != nil {
		return nil, err
	}
	model := openCodeModelReference(p)
	return map[string]any{
		"skills":       []string{filepath.Join(workspace, ".agents", "skills")},
		"instructions": []string{filepath.Join(workspace, "AGENTS.md")},
		"compaction":   map[string]any{"auto": true, "keep": map[string]int{"tokens": 15000}, "buffer": "15%"},
		"agents":       map[string]any{"compaction": map[string]string{"model": model}, "title": map[string]string{"model": model}},
		"permissions":  []map[string]string{{"action": "subagent", "resource": "*", "effect": "deny"}},
		"experimental": map[string]int{"subagent_depth": 0},
	}, nil
}

func HostedOpenCodeConfig(author, reviewer Profile, workspace, relayURL string) (string, error) {
	config, err := openCodeConfig(author, workspace)
	if err != nil {
		return "", err
	}
	if err := ValidateHostedOpenCodeProfile(author); err != nil {
		return "", err
	}
	if err := ValidateHostedOpenCodeProfile(reviewer); err != nil {
		return "", err
	}
	rule := func(action, resource, effect string) map[string]string {
		return map[string]string{"action": action, "resource": resource, "effect": effect}
	}
	agents := config["agents"].(map[string]any)
	agents[HostedOpenCodeAuthor] = map[string]any{
		"mode":  "primary",
		"model": openCodeModelReference(author),
		"permissions": []map[string]string{
			rule("subagent", "*", "deny"),
			rule("subagent", HostedOpenCodeReviewer, "allow"),
		},
	}
	agents[HostedOpenCodeReviewer] = map[string]any{
		"description": "Review candidate code and tests for the assigned risk.",
		"mode":        "subagent",
		"model":       openCodeModelReference(reviewer),
		"permissions": []map[string]string{
			rule("*", "*", "deny"),
			rule("read", "*", "allow"),
			rule("glob", "*", "allow"),
			rule("grep", "*", "allow"),
		},
	}
	config["default_agent"] = HostedOpenCodeAuthor
	config["permissions"] = []map[string]string{
		rule("shell", "gh *", "deny"),
		rule("shell", "git push *", "deny"),
		rule("shell", "git commit *", "deny"),
	}
	config["experimental"] = map[string]int{"subagent_depth": 1}
	return openCodeRelayConfig(config, relayURL)
}

func HostedDocumentationOpenCodeConfig(profile Profile, workspace, relayURL string) (string, error) {
	if err := ValidateHostedOpenCodeProfile(profile); err != nil {
		return "", err
	}
	config, err := openCodeConfig(profile, workspace)
	if err != nil {
		return "", err
	}
	config["permissions"] = append(config["permissions"].([]map[string]string),
		map[string]string{"action": "shell", "resource": "gh *", "effect": "deny"},
		map[string]string{"action": "shell", "resource": "git push *", "effect": "deny"},
		map[string]string{"action": "shell", "resource": "git commit *", "effect": "deny"},
	)
	return openCodeRelayConfig(config, relayURL)
}

func openCodeRelayConfig(config map[string]any, relayURL string) (string, error) {
	config["providers"] = map[string]any{
		"openrouter": map[string]any{
			"settings": map[string]string{"baseURL": relayURL, "apiKey": OpenRouterRelayKey},
		},
	}
	b, err := json.Marshal(config)
	return string(b), err
}

func ValidateHostedOpenCodeProfile(profile Profile) error {
	upstream, prefix := strings.CutPrefix(profile.Model, "openrouter/")
	provider, model, separator := strings.Cut(upstream, "/")
	if profile.Runtime != "opencode" || !prefix || !separator || provider == "" || model == "" ||
		strings.ContainsAny(upstream, "# \r\n\t\x00") || strings.ContainsAny(profile.Effort, "# /\r\n\t\x00") {
		return fmt.Errorf("hosted profiles require an OpenCode model in the form openrouter/provider/model")
	}
	return nil
}

func openCodeModelReference(profile Profile) string {
	if profile.Effort == "" {
		return profile.Model
	}
	return profile.Model + "#" + profile.Effort
}
