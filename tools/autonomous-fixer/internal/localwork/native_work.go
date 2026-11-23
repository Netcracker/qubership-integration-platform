package localwork

import (
	"context"
	"errors"
	"fmt"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

const nativeFixIssueSkill = ".agents/skills/fix-issue/SKILL.md"

func (d *Driver) nativeAuthorPrompt(evidence string) string {
	reviewer := d.Config.ReviewProfile
	reviewAssignment := fmt.Sprintf("Use Codex's native subagents for the review with model %s and %s reasoning effort.", reviewer.Model, reviewer.Effort)
	productCheck := ""
	draft := ""
	if d.Config.Hosted {
		reviewAssignment = "Use the native OpenCode " + experimentruntime.HostedOpenCodeReviewer + " subagent with its configured model and variant."
		productCheck = "The hosted stack is preprovisioned. Set E2E_PROVISION=auto for the first product check after changing a deployed backend or chart. Use the default E2E_PROVISION=never for subsequent checks.\n"
		draft = `
If you fixed the issue, read the change-description-authoring and english-developer-style skills. Finish your report with the following draft for the maintainer, immediately before the outcome line. Use these headings and labels. Use plain text or bulleted lists. The controller adds the issue link, evidence link, and signature.

PR title: fix(<module>): <symptom in the reporter's words>
## Why
Name the product path, symptom, and relevant before and after evidence.
## What
Explain the change in behavior and the reason for the approach.
## How to verify
Give the reviewer the commands or steps and the results you observed. Refer to retained evidence where useful.
Put each literal command on its own line starting with "Command: ".
End-to-end decision: Name the regression test, target, command from this section, and observed results before and after the fix. If no end-to-end case is needed, name the reason in AGENTS.md and explain why it applies to this change.
## For the reviewer
Name any generated contract change, changed test expectation, or decision the maintainer must review. Leave this section empty if there is nothing to flag.
`
	}
	var requirements strings.Builder
	for _, criterion := range d.Request.RequiredCriteria {
		fmt.Fprintf(&requirements, "Required criterion: %s\n", criterion)
	}
	for _, consumer := range d.Request.RequiredConsumers {
		fmt.Fprintf(&requirements, "Required consumer: %s\n", consumer)
	}
	for _, pair := range d.Request.RequiredCoverage {
		fmt.Fprintf(&requirements, "Verify %s for %s.\n", pair.CriterionID, pair.Consumer)
	}
	for _, check := range d.Request.RequiredChecks {
		fmt.Fprintf(&requirements, "Required check: %s (scope: %s).\n", check.ID, check.Scope)
		for _, recipe := range d.Config.StaticChecks {
			if recipe.ID == check.ID && recipe.Scope == check.Scope && len(recipe.Command) != 0 {
				fmt.Fprintf(&requirements, "  Configured command (argv): %q, working directory: %q.\n", recipe.Command, recipe.CWD)
			}
		}
	}
	for _, scenario := range d.Request.VerificationScenarios {
		fmt.Fprintf(&requirements, "Scenario %s (%s, %s): %s\n", scenario.ID, scenario.CriterionID, scenario.Consumer, scenario.Description)
	}
	return fmt.Sprintf(`Use the repository's fix-issue skill at %s to complete the local assignment in %q. Read the applicable AGENTS.md files and the skill's companion files in this worktree.

The supplied task and worktree replace GitHub intake and worktree creation. Use the allocated product environment. Follow the skill through engineering verification and review. %s Assign focused, read-only review questions. You own the patch, checks, and decisions on findings.

When the patch changes precedence, import behavior, or reference resolution, ask the correctness reviewer to state one invariant from the contract and choose two small inputs that test it. For example, if object key order has no meaning, swap two keys and compare the resolved definitions. Use existing test results if they cover that pair. Otherwise, run both inputs through the changed code and report the expected relationship and the two observed results.

%s
%s
Keep the patch in this worktree and save useful evidence under %q. This local run ends with a report. Do not commit, push, publish, write to GitHub, or remove the worktree.

Report what you changed, what you observed, and any unresolved blockers in ordinary prose. End with one line: Outcome: fixed, Outcome: needs-human, or Outcome: blocked. Use fixed when the applicable verification and review are complete.
%s`, nativeFixIssueSkill, filepath.Join(d.Config.TaskDirectory, "task.md"), reviewAssignment, requirements.String(), productCheck, evidence, draft)
}

func configureNativeReviewers(command *experimentruntime.Command, reviewer experimentruntime.Profile) {
	for i := 0; i+1 < len(command.Args); i++ {
		if command.Args[i] == "--disable" && command.Args[i+1] == "multi_agent" {
			command.Args[i] = "--enable"
		}
	}
	last := len(command.Args) - 1
	command.Args = append(command.Args[:last],
		"--disable", "apps",
		"-c", "agents.default_subagent_model="+strconv.Quote(reviewer.Model),
		"-c", "agents.default_subagent_reasoning_effort="+strconv.Quote(reviewer.Effort), "-")
}

func authorReportedStatus(text string) string {
	text = strings.TrimSpace(text)
	outcome := strings.TrimSpace(text[strings.LastIndexByte(text, '\n')+1:])
	switch strings.ToLower(outcome) {
	case "outcome: fixed":
		return "author-reported-fixed"
	case "outcome: needs-human":
		return "needs-human"
	case "outcome: blocked":
		return "blocked"
	default:
		return "author-reported"
	}
}

func (d *Driver) runNative(ctx context.Context) (report workcycle.Report, err error) {
	report = workcycle.Report{Workflow: "fix-issue", Request: d.Request}
	defer func() {
		if err != nil {
			report.Status = "execution-failed"
			if ctx.Err() != nil {
				report.Status = "interrupted"
			}
			report.Error = err.Error()
		}
		if d.Config.Hosted {
			report.Error = sanitizeDiagnostic(report.Error)
			report.Request.Task = sanitizeDiagnostic(report.Request.Task)
		}
		if saveErr := d.Save(context.WithoutCancel(ctx), report); saveErr != nil {
			err = errors.Join(err, saveErr)
			report.Status = "execution-failed"
			report.Error = err.Error()
		}
	}()
	recordSource := func() workcycle.Snapshot {
		snapshot, snapshotErr := d.Snapshot(ctx)
		if snapshotErr != nil {
			report.Warnings = append(report.Warnings, "Could not record the source snapshot. "+snapshotErr.Error())
		} else {
			report.Candidate = snapshot
		}
		return snapshot
	}
	before := recordSource()
	result, err := d.Author(ctx, workcycle.AgentAuthorRequest{Request: d.Request, Candidate: before})
	after := recordSource()
	report.Summary = result.Text
	report.Status = authorReportedStatus(result.Text)
	report.Observations = []workcycle.Observation{{
		Stage: workcycle.AuthorStage, Before: before, After: after,
		Declaration: workcycle.Response{CandidateID: before.ID, Narrative: result.Text, Evidence: result.Evidence},
	}}
	return report, err
}
