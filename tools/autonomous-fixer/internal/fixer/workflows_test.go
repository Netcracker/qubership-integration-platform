package fixer

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestWorkflowsBuildGoBeforeTokenUse(t *testing.T) {
	for _, name := range []string{"control", "work", "publish"} {
		t.Run(name, func(t *testing.T) {
			data, err := os.ReadFile(filepath.Join("..", "..", "..", "..", ".github", "workflows", "agent-"+name+".yaml"))
			if err != nil {
				t.Fatal(err)
			}
			source := string(data)
			for _, required := range []string{"permissions: {}", "persist-credentials: false", "actions/setup-go@b7ad1dad31e06c5925ef5d2fc7ad053ef454303e", "go-version-file: tools/autonomous-fixer/go.mod", "cache: false", "GOTOOLCHAIN: local", "working-directory: tools/autonomous-fixer", "go build -trimpath -o \"$RUNNER_TEMP/qip-agent\" ./cmd/agent", "AGENT_MODE: ${{ vars.AGENT_MODE || 'fake' }}", "group: qip-agent-control", "queue: max", "cancel-in-progress: false"} {
				if !strings.Contains(source, required) {
					t.Errorf("missing %q", required)
				}
			}
			for _, forbidden := range []string{"node tools/", "npm ", "AGENT_MODE: live", "pull_request_target", "contents: write"} {
				if strings.Contains(source, forbidden) {
					t.Errorf("unexpected %q", forbidden)
				}
			}
			if strings.Index(source, "name: Build agent") > strings.Index(source, "GITHUB_TOKEN:") {
				t.Fatal("token use precedes agent build")
			}
			if !strings.Contains(source, "          AGENT_VARIABLES_READ_TOKEN:") || strings.Contains(source, "AGENT_ALLOWED_ACTORS:") {
				t.Fatal("the workflow must read current variables in the agent step")
			}
			if name == "control" {
				for _, required := range []string{".github/workflows/agent-*.yaml", ".github/workflows/agent-*.yml", ".github/workflows/pr-linked-issue.yaml", ".github/workflows/pr-linked-issue.yml", "python3 -B -W error -m unittest discover", "gofmt -l .", "go vet ./...", "go test -race ./...", "qip-agent\" route", "qip-agent\" control"} {
					if !strings.Contains(source, required) {
						t.Errorf("missing %q", required)
					}
				}
			}
			if name == "work" {
				for _, required := range []string{"group: qip-agent-work", "qip-agent\" admit", "needs.admit.outputs.admitted == 'true'", "uses: ./.github/workflows/agent-execute.yaml", "needs: [admit-revision, work]", "agent-hosted-${{ github.run_id }}-1"} {
					if !strings.Contains(source, required) {
						t.Errorf("missing %q", required)
					}
				}
			}
			if name == "publish" && (!strings.Contains(source, "workflow_call:") || !strings.Contains(source, "steps.authorize.outputs.process == 'true'") || strings.Contains(source, "OPENROUTER_API_KEY")) {
				t.Fatal("publication requires the trusted authorization step and no model credentials")
			}
		})
	}
}

func TestRevisionPreparationUsesTheTrustedMavenCacheWithoutCredentials(t *testing.T) {
	workflows := filepath.Join("..", "..", "..", "..", ".github", "workflows")
	read := func(name string) string {
		t.Helper()
		data, err := os.ReadFile(filepath.Join(workflows, name))
		if err != nil {
			t.Fatal(err)
		}
		return string(data)
	}
	work := read("agent-execute.yaml")
	_, prepare, _ := strings.Cut(work, "- name: Prepare the product environment")
	initial, revision, _ := strings.Cut(prepare, "- name: Prepare the revision environment")
	revision, _, _ = strings.Cut(revision, "- name: Run the author and reviewer")
	if !strings.Contains(initial, "!inputs.revision") ||
		!strings.Contains(initial, "MAVEN_GITHUB_PASSWORD:") {
		t.Fatal("initial hosted preparation needs its Maven authentication")
	}
	if !strings.Contains(revision, "if: inputs.revision") || !strings.Contains(revision, "hosted_prepare_revision.sh") ||
		strings.Contains(revision, "env:") || strings.Contains(revision, "github.token") || strings.Contains(revision, "secrets.") {
		t.Fatal("revision preparation must run in its own step without credentials")
	}
	prewarm := read("agent-revision-cache.yaml")
	_, restore, _ := strings.Cut(work, "- name: Restore the Maven repository for the revision")
	restore, _, _ = strings.Cut(restore, "\n      - ")
	for _, setting := range []string{
		"path: ${{ runner.temp }}/qip-revision-maven-repository",
		"key: maven-${{ runner.os }}-agent-revision-${{ hashFiles('trusted/**/pom.xml') }}",
	} {
		if !strings.Contains(prewarm, setting) || !strings.Contains(restore, setting) {
			t.Errorf("the revision restore and prewarm must share %q", setting)
		}
	}
	for _, required := range []string{"workflow_dispatch:", "github.event.repository.default_branch", "persist-credentials: false",
		`"$RUNNER_TEMP/qip-agent" authorize`, "working-directory: trusted", "packages: read",
		"-pl runtime-catalog,engine,sessions-management,micro-engine -am clean install"} {
		if !strings.Contains(prewarm, required) {
			t.Errorf("the trusted prewarm needs %q", required)
		}
	}
}

func TestAutomaticRevisionUsesTheExistingRevisionWorkflow(t *testing.T) {
	source, err := os.ReadFile(filepath.Join("..", "..", "..", "..", ".github", "workflows", "agent-work.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	workflow := string(source)
	for _, required := range []string{
		"format('qip-agent-auto-revise|{0}|{1}', inputs.auto_pr, inputs.auto_request)",
		"inputs.auto_pr == '' && inputs.auto_request == ''",
		"inputs.auto_pr != '' && inputs.auto_request != ''",
		"github.event.sender.login == 'github-actions[bot]'",
		"github.event.sender.type == 'Bot'",
		"needs.admit-revision.outputs.admitted == 'true' && github.run_attempt == 1",
	} {
		if !strings.Contains(workflow, required) {
			t.Errorf("missing %q", required)
		}
	}
	if strings.Count(workflow, "group: qip-agent-revision-${{ github.event.issue.number || inputs.auto_pr }}") != 2 {
		t.Fatal("both revision admission and publication must share the pull request's concurrency group")
	}
}

func TestTrustedJobsSelectTheVariablesCredential(t *testing.T) {
	const selection = "secrets[vars.AGENT_USE_ORG_BOT == 'true' && 'GH_ACCESS_TOKEN' || 'AGENT_VARIABLES_READ_TOKEN']"
	for _, name := range []string{"control", "work", "publish", "ci"} {
		data, err := os.ReadFile(filepath.Join("..", "..", "..", "..", ".github", "workflows", "agent-"+name+".yaml"))
		if err != nil {
			t.Fatal(err)
		}
		seen := false
		for _, line := range strings.Split(string(data), "\n") {
			if !strings.HasPrefix(line, "          AGENT_VARIABLES_READ_TOKEN:") {
				continue
			}
			seen = true
			if !strings.Contains(line, selection) {
				t.Errorf("%s uses the wrong credential for variable reads", name)
			}
		}
		if !seen {
			t.Errorf("%s has no variable reads", name)
		}
	}
}

func TestEngineeringWorkerHasNoWriteCredentials(t *testing.T) {
	data, err := os.ReadFile(filepath.Join("..", "..", "..", "..", ".github", "workflows", "agent-execute.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	prepare, author, found := strings.Cut(string(data), "\n  author:\n")
	if !found || !strings.Contains(prepare, "AGENT_VARIABLES_READ_TOKEN: ${{ secrets.AGENT_VARIABLES_READ_TOKEN }}") ||
		!strings.Contains(author, "needs: prepare") || !strings.Contains(author, "if: inputs.mode == 'hosted'") {
		t.Fatal("only a hosted assignment can start the author")
	}
	for _, forbidden := range []string{"GH_ACCESS_TOKEN", "AGENT_VARIABLES_READ_TOKEN", "AGENT_PUBLICATION_TOKEN", ": write\n"} {
		if strings.Contains(author, forbidden) {
			t.Errorf("author job has %q", forbidden)
		}
	}
	for _, command := range []string{`qip-agent" work`, `qip-agent-revise" work`} {
		if !strings.Contains(prepare, command) {
			t.Errorf("assignment preparation lacks %s", command)
		}
	}
}

func TestRevisionPullRequestPermissions(t *testing.T) {
	data, err := os.ReadFile(filepath.Join("..", "..", "..", "..", ".github", "workflows", "agent-work.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	for job, permission := range map[string]string{
		"admit-revision":   "write",
		"work":             "read",
		"publish-revision": "write",
	} {
		_, body, found := strings.Cut(string(data), "\n  "+job+":\n")
		beforeSteps, _, _ := strings.Cut(body, "\n    steps:")
		_, permissions, _ := strings.Cut(beforeSteps, "\n    permissions:\n")
		if !found || !strings.Contains(permissions, "      issues: read\n") ||
			!strings.Contains(permissions, "      pull-requests: "+permission+"\n") {
			t.Errorf("%s needs read access to issues and %s access to pull requests", job, permission)
		}
	}
}
