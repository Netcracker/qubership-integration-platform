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
				for _, required := range []string{"group: qip-agent-work", "qip-agent\" admit", "qip-agent\" work", "needs.admit.outputs.admitted == 'true'", "INPUT_TRUSTED_SHA:", "if: inputs.mode == 'fake'", "if: inputs.mode == 'hosted'"} {
					if !strings.Contains(source, required) {
						t.Errorf("missing %q", required)
					}
				}
				if strings.Count(source, "name: Build agent") != 2 {
					t.Fatal("each work job must build its binary")
				}
				for _, setup := range []string{"actions/setup-node@", "actions/setup-java@", "name: Run the author and reviewer"} {
					_, rest, found := strings.Cut(source, setup)
					step, _, _ := strings.Cut(rest, "\n      - ")
					if !found || !strings.Contains(step, "\n        if: inputs.mode == 'hosted'") {
						t.Errorf("product step %q must run only in hosted mode", setup)
					}
				}
				worker, publisher, _ := strings.Cut(source, "\n  publish-revision:")
				_, author, _ := strings.Cut(worker, "- name: Run the author and reviewer")
				author, _, _ = strings.Cut(author, "- name: Stage hosted evidence")
				if !strings.Contains(author, "if: inputs.mode == 'hosted'") || !strings.Contains(author, "OPENROUTER_API_KEY:") || strings.Contains(author, "github.token") || strings.Contains(author, "AGENT_VARIABLES_READ_TOKEN") || strings.Contains(worker, "AGENT_PUBLICATION_TOKEN") {
					t.Fatal("hosted execution must receive only its model credential")
				}
				if !strings.Contains(publisher, "AGENT_PUBLICATION_TOKEN:") || strings.Contains(publisher, "OPENROUTER_API_KEY:") ||
					!strings.Contains(publisher, "needs: [admit-revision, work]") || !strings.Contains(publisher, "ref: ${{ github.sha }}") ||
					!strings.Contains(publisher, "agent-hosted-${{ github.run_id }}-1") ||
					!strings.Contains(worker, "needs.admit-revision.outputs.admitted == 'true' && github.run_attempt == 1") {
					t.Fatal("the revision publisher must use the admitted workflow and its own credentials")
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
	work := read("agent-work.yaml")
	_, prepare, _ := strings.Cut(work, "- name: Prepare the product environment")
	initial, revision, _ := strings.Cut(prepare, "- name: Prepare the revision environment")
	revision, _, _ = strings.Cut(revision, "- name: Run the author and reviewer")
	if !strings.Contains(initial, "needs.admit.outputs.admitted == 'true' && inputs.mode == 'hosted'") ||
		!strings.Contains(initial, "MAVEN_GITHUB_PASSWORD:") {
		t.Fatal("initial hosted preparation needs its Maven authentication")
	}
	if !strings.Contains(revision, "needs.admit-revision.outputs.admitted == 'true'") || !strings.Contains(revision, "hosted_prepare_revision.sh") ||
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
		"if: needs.admit-revision.outputs.admitted == 'true'\n        run: '\"$RUNNER_TEMP/qip-agent-revise\" work'",
	} {
		if !strings.Contains(workflow, required) {
			t.Errorf("missing %q", required)
		}
	}
	if strings.Count(workflow, "group: qip-agent-revision-${{ github.event.issue.number || inputs.auto_pr }}") != 2 {
		t.Fatal("both revision admission and publication must share the pull request's concurrency group")
	}
}

func TestEngineeringWorkerHasNoWriteCredentials(t *testing.T) {
	source, err := os.ReadFile(filepath.Join("..", "..", "..", "..", ".github", "workflows", "agent-work.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	_, jobs, found := strings.Cut(string(source), "\n  work:\n")
	worker, _, ended := strings.Cut(jobs, "\n  notify:\n")
	if !found || !ended {
		t.Fatal("cannot identify the hosted worker job")
	}
	for _, forbidden := range []string{"GH_ACCESS_TOKEN", "AGENT_PUBLICATION_TOKEN", ": write\n"} {
		if strings.Contains(worker, forbidden) {
			t.Errorf("worker job has %q", forbidden)
		}
	}
	if !strings.Contains(worker, "AGENT_USE_ORG_BOT: ${{ vars.AGENT_USE_ORG_BOT }}") {
		t.Fatal("worker needs organization bot identity to read admitted receipts")
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
