package localwork

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/openrouterusage"
)

func hostedOpenCodeFixture(t *testing.T) Config {
	t.Helper()
	cfg := agentFixture(t)
	cfg.Workflow = "fix-issue"
	cfg.Hosted = true
	cfg.Profile = experimentruntime.Profile{Runtime: "opencode", Model: "openrouter/openai/gpt-6-luna", Effort: "high"}
	cfg.ReviewProfile = &experimentruntime.Profile{Runtime: "opencode", Model: "openrouter/openai/gpt-6-luna", Effort: "xhigh"}
	cfg.LoginFile, cfg.ModelCatalog = "", ""
	return cfg
}

func TestHostedFixIssueUsesOpenCodeAndDoesNotPassProviderKey(t *testing.T) {
	cfg := hostedOpenCodeFixture(t)
	t.Setenv("OPENROUTER_API_KEY", "fixture-secret")
	t.Setenv("GITHUB_TOKEN", "fixture-github-token")
	if err := os.WriteFile(filepath.Join(cfg.TaskDirectory, "task.md"), []byte("Check the fixture. Sensitive marker fixture-github-token."), 0600); err != nil {
		t.Fatal(err)
	}
	script := `#!/bin/sh
set -eu
test -z "${OPENROUTER_API_KEY+x}"
test -z "${GITHUB_TOKEN+x}"
if [ "$1" = '--version' ]; then echo 'fixture runtime'; exit; fi
case "$OPENCODE_CONFIG_CONTENT" in
	*fixture-secret*|*fixture-github-token*) exit 12 ;;
esac
mkdir -p "$XDG_DATA_HOME"
printf '%s' "$OPENCODE_CONFIG_CONTENT" > "$XDG_DATA_HOME/native-config.json"
printf '%s\n' '{"type":"text","sessionID":"fixture","part":{"type":"text","messageID":"message","text":"Checked the patch. Sensitive marker fixture-secret.\n\nPR title: fix(schemas): Preserve boolean defaults\n## Why\nImporting a chain dropped its false default.\n## What\nThe import preserves explicit false defaults.\n## How to verify\nCommand: npm test\nThe schema regression test passed.\nEnd-to-end decision: The module test exercises the importer.\n## For the reviewer\n\nOutcome: fixed"}}'
`
	if err := os.WriteFile(cfg.RuntimeBinary, []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
	report, err := Execute(context.Background(), cfg)
	if err != nil || report.Workflow != "fix-issue" || report.Status != "author-reported-fixed" || report.Successful() {
		t.Fatal(report, err)
	}
	var command experimentruntime.Command
	dir := filepath.Join(cfg.OutputDirectory, "step-0001-author")
	readAgentJSON(t, filepath.Join(dir, "command.json"), &command)
	if !slices.Contains(command.Args, "--standalone") || !slices.Contains(command.Args, "--file") ||
		!slices.Contains(command.Args, "qip-author") || !slices.Contains(command.Args, cfg.Profile.Model+"#high") {
		t.Fatal(command.Args)
	}
	var native struct {
		DefaultAgent string                    `json:"default_agent"`
		Agents       map[string]map[string]any `json:"agents"`
		Providers    map[string]map[string]any `json:"providers"`
	}
	readAgentJSON(t, filepath.Join(cfg.OutputDirectory, "author-home", "data", "native-config.json"), &native)
	settings := native.Providers["openrouter"]["settings"].(map[string]any)
	if native.DefaultAgent != "qip-author" || native.Agents["qip-author"]["model"] != cfg.Profile.Model+"#high" ||
		native.Agents["qip-reviewer"]["model"] != cfg.ReviewProfile.Model+"#xhigh" ||
		!strings.HasPrefix(settings["baseURL"].(string), "http://127.0.0.1:") || settings["apiKey"] != experimentruntime.OpenRouterRelayKey {
		t.Fatal(native)
	}
	prompt, err := os.ReadFile(filepath.Join(dir, "prompt.txt"))
	if err != nil || !strings.Contains(string(prompt), "native OpenCode qip-reviewer subagent") ||
		strings.Contains(string(prompt), "Codex's native subagents") || !strings.Contains(string(prompt), "E2E_PROVISION=auto") ||
		!strings.Contains(string(prompt), "choose two small inputs") ||
		!strings.Contains(string(prompt), "## How to verify") || !strings.Contains(string(prompt), "End-to-end decision:") ||
		!strings.Contains(report.Summary, "End-to-end decision: The module test exercises the importer.") {
		t.Fatal(string(prompt), err)
	}
	for _, path := range []string{filepath.Join(dir, "response-raw.txt"), filepath.Join(cfg.OutputDirectory, "report.json")} {
		content, err := os.ReadFile(path)
		if err != nil || strings.Contains(string(content), "fixture-secret") || strings.Contains(string(content), "fixture-github-token") || !strings.Contains(string(content), "<REDACTED>") && !strings.Contains(string(content), "\\u003cREDACTED\\u003e") {
			t.Fatalf("hosted output did not redact the fixture credentials: %s, %v", path, err)
		}
	}
	var providerUsage openrouterusage.Report
	readAgentJSON(t, filepath.Join(cfg.OutputDirectory, openrouterusage.Filename), &providerUsage)
	if providerUsage.Attempts != 0 || providerUsage.ReportedCostUSD != nil {
		t.Fatal(providerUsage)
	}
}

func TestHostedOpenCodeRequiresExplicitProviderProfiles(t *testing.T) {
	t.Setenv("OPENROUTER_API_KEY", "fixture-secret")
	for name, damage := range map[string]func(*Config){
		"local OpenCode default": func(c *Config) { c.Hosted = false },
		"legacy workflow":        func(c *Config) { c.Workflow = "agent" },
		"reviewer absent":        func(c *Config) { c.ReviewProfile = nil },
		"another provider":       func(c *Config) { c.Profile.Model = "openai/test-author" },
		"credential for tools":   func(c *Config) { c.Environment["OPENROUTER_API_KEY"] = "other-key" },
	} {
		t.Run(name, func(t *testing.T) {
			cfg := hostedOpenCodeFixture(t)
			damage(&cfg)
			if _, err := Prepare(context.Background(), cfg); err == nil {
				t.Fatal("invalid hosted configuration was accepted")
			}
		})
	}
}

func TestHostedFixIssueRejectsNonzeroExitAfterFixedOutcome(t *testing.T) {
	cfg := hostedOpenCodeFixture(t)
	t.Setenv("OPENROUTER_API_KEY", "fixture-secret")
	script := `#!/bin/sh
if [ "$1" = '--version' ]; then echo 'fixture runtime'; exit; fi
printf '%s\n' '{"type":"text","sessionID":"fixture","part":{"type":"text","messageID":"message","text":"Outcome: fixed"}}'
printf '%s\n' '{"type":"error","sessionID":"fixture","error":{"type":"provider.rate-limit","status":429,"message":"private response"}}'
exit 1
`
	if err := os.WriteFile(cfg.RuntimeBinary, []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
	report, err := Execute(context.Background(), cfg)
	if err == nil || report.Status != "execution-failed" || report.Successful() || report.Summary != "Outcome: fixed" ||
		report.Error != "exit status 1" {
		t.Fatal(report, err)
	}
	var process experimentruntime.Result
	dir := filepath.Join(cfg.OutputDirectory, "step-0001-author")
	readAgentJSON(t, filepath.Join(dir, "process.json"), &process)
	if process.ExitCode != 1 || process.Classification != "process_error" {
		t.Fatal(process)
	}
}

func TestOpenRouterRelayForwardsOnlyDeclaredModelsAndRoutes(t *testing.T) {
	cfg := hostedOpenCodeFixture(t)
	cfg.Profile.Model = "openrouter/openai/test-author"
	cfg.ReviewProfile.Model = "openrouter/openai/test-reviewer"
	pending := make(chan string, 8)
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Model string `json:"model"`
			Usage struct {
				Include bool `json:"include"`
			} `json:"usage"`
			StreamOptions struct {
				Include bool   `json:"include_usage"`
				Custom  string `json:"custom"`
			} `json:"stream_options"`
		}
		if r.Header.Get("Authorization") != "Bearer fixture-secret" || r.Header.Get("Accept-Encoding") != "identity" ||
			json.NewDecoder(r.Body).Decode(&body) != nil || !body.Usage.Include || !body.StreamOptions.Include || body.StreamOptions.Custom != "retained" {
			http.Error(w, "invalid request", http.StatusBadRequest)
			return
		}
		pending <- body.Model
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = io.WriteString(w, "data: {\"id\":\"fixture-generation\",\"usage\":{\"cost\":0.01}}\n\n")
	}))
	t.Cleanup(upstream.Close)
	target, err := url.Parse(upstream.URL)
	if err != nil {
		t.Fatal(err)
	}
	var meter openrouterusage.Recorder
	relay := openRouterRelay(target, "fixture-secret", []experimentruntime.Profile{cfg.Profile, *cfg.ReviewProfile}, &meter)
	for _, tc := range []struct {
		method, path, body, key string
		want                    int
	}{
		{"POST", "/api/v1/chat/completions", `{"model":"openai/test-author","stream":true,"stream_options":{"include_usage":false,"custom":"retained"}}`, experimentruntime.OpenRouterRelayKey, 200},
		{"POST", "/api/v1/chat/completions", `{"model":"openai/test-reviewer","stream":true,"stream_options":{"custom":"retained"}}`, experimentruntime.OpenRouterRelayKey, 200},
		{"POST", "/api/v1/responses", `{"model":"openai/test-reviewer"}`, experimentruntime.OpenRouterRelayKey, 404},
		{"POST", "/api/v1/chat/completions", `{"model":"openai/other"}`, experimentruntime.OpenRouterRelayKey, 403},
		{"POST", "/api/v1/chat/completions", `{"model":"openai/test-author","models":["openai/other"]}`, experimentruntime.OpenRouterRelayKey, 403},
		{"POST", "/api/v1/chat/completions", `{"model":"openai/test-author"}`, "", 401},
		{"POST", "/api/v1/credits", `{"model":"openai/test-author"}`, experimentruntime.OpenRouterRelayKey, 404},
		{"GET", "/api/v1/chat/completions", "", experimentruntime.OpenRouterRelayKey, 404},
	} {
		r := httptest.NewRequest(tc.method, tc.path, strings.NewReader(tc.body))
		r.Header.Set("Authorization", "Bearer "+tc.key)
		w := httptest.NewRecorder()
		relay.ServeHTTP(w, r)
		if actual := w.Result(); actual.StatusCode != tc.want || strings.Contains(w.Body.String(), "fixture-secret") {
			t.Fatalf("%s %s: code=%d body=%q", tc.method, tc.path, actual.StatusCode, w.Body.String())
		}
	}
	if len(pending) != 2 || <-pending != "openai/test-author" || <-pending != "openai/test-reviewer" {
		t.Fatal("relay forwarded a disallowed request")
	}
	if report := meter.Report(); report.Attempts != 2 || len(report.Generations) != 1 || report.DuplicateResponses != 1 ||
		!report.Generations[0].Conflict || report.ReportedCostUSD != nil {
		t.Fatal(report)
	}
}
