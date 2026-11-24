package fixer

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestDocumentationWorkflowCredentialsStayInTrustedSteps(t *testing.T) {
	for _, workflow := range []struct {
		file         string
		projectSteps []string
	}{
		{
			file: "agent-docs.yaml",
			projectSteps: []string{
				"Prepare trusted input",
				"Claim the scheduled documentation basis",
				"Publish the documentation result",
				"Reconcile publication",
				"Update the documentation claim",
			},
		},
		{file: "agent-docs-schedule.yaml", projectSteps: []string{"Discover issues in documentation review"}},
		{file: "agent-docs-reconcile-merge.yaml", projectSteps: []string{"Complete the documentation issue"}},
	} {
		t.Run(workflow.file, func(t *testing.T) {
			data, err := os.ReadFile(filepath.Join("..", "..", "..", "..", ".github", "workflows", workflow.file))
			if err != nil {
				t.Fatal(err)
			}
			source := string(data)
			takeStep := func(name string) string {
				t.Helper()
				marker := "\n      - name: " + name + "\n"
				_, rest, ok := strings.Cut(source, marker)
				if !ok {
					t.Fatalf("missing step %q", name)
				}
				step, _, _ := strings.Cut(rest, "\n      - ")
				step, _, _ = strings.Cut(step, "\n\n  ")
				source = strings.Replace(source, marker+step, "", 1)
				for _, required := range []string{
					"AGENT_USE_ORG_BOT: ${{ vars.AGENT_USE_ORG_BOT == 'true' }}",
					"GITHUB_TOKEN: >-",
					"(vars.AGENT_USE_ORG_BOT == 'true' && secrets.GH_ACCESS_TOKEN)",
					"(vars.AGENT_USE_ORG_BOT != 'true' &&",
				} {
					if !strings.Contains(step, required) {
						t.Errorf("step %q is missing %q", name, required)
					}
				}
				return step
			}
			for _, name := range workflow.projectSteps {
				step := takeStep(name)
				if !strings.Contains(step, "AGENT_PROJECT_TOKEN: ${{ vars.AGENT_USE_ORG_BOT == 'true' && secrets.AGENT_DOCS_GITHUB_TOKEN || '' }}") {
					t.Errorf("step %q needs the split Project credential", name)
				}
			}
			switch workflow.file {
			case "agent-docs.yaml":
				_, call, _ := strings.Cut(source, "\n  workflow_call:\n")
				call, _, _ = strings.Cut(call, "\npermissions:")
				for _, name := range []string{"GH_ACCESS_TOKEN", "AGENT_DOCS_GITHUB_TOKEN"} {
					if !strings.Contains(call, "\n      "+name+":\n        required: false") {
						t.Errorf("reusable workflow must accept optional secret %s", name)
					}
				}
			case "agent-docs-schedule.yaml":
				step := takeStep("Reply to the skipped documentation command")
				if !strings.Contains(step, "github.token") || strings.Contains(step, "AGENT_PROJECT_TOKEN") ||
					strings.Contains(step, "secrets.AGENT_DOCS_GITHUB_TOKEN") {
					t.Error("the command reply needs the repository credential for each mode")
				}
				_, process, _ := strings.Cut(source, "\n  process:\n")
				process, _, _ = strings.Cut(process, "\n  reply:\n")
				if !strings.Contains(process, "uses: ./.github/workflows/agent-docs.yaml") {
					t.Error("the schedule must call the reusable documentation workflow")
				}
				for _, name := range []string{"GH_ACCESS_TOKEN", "AGENT_DOCS_GITHUB_TOKEN"} {
					passing := "\n      " + name + ": ${{ secrets." + name + " }}"
					if !strings.Contains(process, passing) {
						t.Errorf("the schedule must pass %s to the called workflow", name)
					}
					source = strings.Replace(source, passing, "", 1)
				}
			}
			for _, credential := range []string{"secrets.GH_ACCESS_TOKEN", "secrets.AGENT_DOCS_GITHUB_TOKEN", "AGENT_PROJECT_TOKEN"} {
				if strings.Contains(source, credential) {
					t.Errorf("credential %s is exposed outside the trusted steps", credential)
				}
			}
		})
	}
}

func TestDocumentationPullRequestGatesUseConfiguredPublisher(t *testing.T) {
	for file, required := range map[string][]string{
		"agent-docs.yaml": {
			"AGENT_ORG_BOT_LOGIN: ${{ vars.AGENT_ORG_BOT_LOGIN }}",
			"if [[ \"$AGENT_USE_ORG_BOT\" == true && -z \"$AGENT_ORG_BOT_LOGIN\" ]]; then",
		},
		"agent-docs-reconcile-merge.yaml": {
			"vars.AGENT_USE_ORG_BOT == 'true' && vars.AGENT_ORG_BOT_LOGIN != '' && github.event.pull_request.user.login == vars.AGENT_ORG_BOT_LOGIN",
			"vars.AGENT_USE_ORG_BOT != 'true' && vars.AGENT_DOCS_PUBLISHER_LOGIN != '' && github.event.pull_request.user.login == vars.AGENT_DOCS_PUBLISHER_LOGIN",
			"(vars.AGENT_USE_ORG_BOT == 'true' && vars.AGENT_ORG_BOT_LOGIN) || (vars.AGENT_USE_ORG_BOT != 'true' && vars.AGENT_DOCS_PUBLISHER_LOGIN) || ''",
			"--publisher-login \"$PUBLISHER\"",
		},
		"pr-linked-issue.yaml": {
			"(vars.AGENT_USE_ORG_BOT == 'true' && vars.AGENT_ORG_BOT_LOGIN) || (vars.AGENT_USE_ORG_BOT != 'true' && vars.AGENT_DOCS_PUBLISHER_LOGIN) || ''",
			"if: vars.AGENT_USE_ORG_BOT == 'true' && env.DOCS_PUBLISHER == '' && startsWith(github.head_ref, 'agent/docs/')",
			"env.DOCS_PUBLISHER != '' && github.event.pull_request.user.login == env.DOCS_PUBLISHER",
			"--publisher-login \"$DOCS_PUBLISHER\"",
		},
	} {
		t.Run(file, func(t *testing.T) {
			data, err := os.ReadFile(filepath.Join("..", "..", "..", "..", ".github", "workflows", file))
			if err != nil {
				t.Fatal(err)
			}
			source := strings.Join(strings.Fields(string(data)), " ")
			for _, condition := range required {
				if !strings.Contains(source, condition) {
					t.Errorf("missing publisher check %q", condition)
				}
			}
		})
	}
}
