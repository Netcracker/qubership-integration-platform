package localwork

import (
	"encoding/json"
	"fmt"
	"path/filepath"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func Prompt(inv workcycle.Invocation, evidence, base string) string {
	inv.Candidate.FileHashes = nil
	inv.Request.Context += "\nThe candidate.id is an opaque controller identifier, not a raw file or Git hash. The controller validates checkout identity before and after this stage. Do not reconstruct its internal digest. Still verify that the running product and built artifacts correspond to this checkout.\n"
	if inv.Request.Kind == "documentation" {
		return documentationPrompt(inv, evidence, base)
	}
	context := inv.Request.Context
	if len(inv.Routing) > 0 {
		context += "\nRouting recommendations are advisory. Fields ending in _candidate or _candidates are weaker possibilities to inspect, not accepted decisions or additional scheduled reviews. Verify them against the issue and source. They do not change scope, authorize edits, waive checks, or prove an issue is a duplicate. Investigate suggested modules using the repository instructions and skills. Ask for missing information only after verifying that source investigation cannot establish it.\n"
	}
	inv.Request.Context = ""
	input, _ := json.MarshalIndent(inv, "", "  ")
	if len(inv.PriorVerificationDataIDs) > 0 {
		context += "\nThe controller has revalidated this checkout against the accepted candidate. Earlier blocked verification attempts remain historical records and are not unresolved identity decisions. This verification-only continuation requires a new dataset B different from baseline_data_id and every prior_verification_data_ids entry. Execute all scenarios again. Source edits are prohibited; return a specific question if failures require a repair.\n"
	}
	if inv.PendingQuestion != nil {
		context += "\nA prior pass returned the pending_question. Perform the specific missing observation using the available source and environment, then return the complete result for this same stage. Preserve all acceptance criteria and product behavior. If access or an owner decision is actually required, return that question with evidence; do not guess. This is one additional investigation pass.\n"
	}
	if inv.ReviewDelta != nil && inv.Stage == workcycle.ReviewStage {
		context += "\nThis is a review after repair. Focus on review_delta.changed_paths, the listed findings, and affected callers. Confirm the repair and check for new risks. Inspect the full candidate when the delta or evidence does not establish the affected scope.\n"
	}
	if inv.Stage == workcycle.ImplementStage && len(inv.Findings) > 0 {
		context += "\nThis is a focused repair, not a new implementation. Start with the supplied findings and their logs. Change only what resolves confirmed findings; explain disputed findings with evidence. Run the affected tests first. For test-expectation-only changes, do not repeat unrelated browser, webview, baseline, or build work. If production code or a product test procedure changes, revalidate its affected behavior. The controller still schedules required static checks, independent review, and fresh final dataset B.\n"
	}
	if inv.Stage == workcycle.ReviewStage {
		context += "\nReview focus: " + reviewFocus(inv.Lens) + "\n"
	}
	if len(inv.Handoff) > 0 {
		context += "\nUse the handoff's successful commands, data identities, deployment state, and evidence to continue the work. Recheck candidate and environment identity before reusing a recipe. Earlier scenario outcomes are background; final dataset B observations must be new.\n"
	}
	stage := map[workcycle.Stage]string{
		workcycle.ContractStage:  "Inspect the task and source without editing product or test files. Return contract: scenarios (id, criterion_id, consumer, behavior, procedure, baseline=pass|fail), checks (id, scope), and risks. Cover every required criterion and consumer. The controller owns configured required check IDs and scopes and retains them even if omitted here; copy them exactly and add any further checks you need. Include the healthy neighboring behavior and actual entry/exit paths of changed consumers. Describe observable results and runnable procedures; an existing suite name alone is not a scenario. Use risks compatibility, data, security, concurrency when present. Ask a specific question if behavior or access is undecided.",
		workcycle.ReproduceStage: "Execute every contract scenario on the unchanged source. Return each scenario with its measured result, using generated observation IDs for product-check measurements. A fail means the expected behavioral defect was observed, never a build or environment failure. For an infrastructure blocker return question instead. Baseline labels in the contract are predictions: report the actual measured outcome even if it differs. The controller records that reconciliation without changing required final behavior, procedures, or coverage. Record environment_id and a distinct data_id for dataset A. Production sources must remain unchanged. You may write the permanent regression test now under e2e/specs/*.spec.ts, UI or extension *.test.ts(x)/.spec.ts, Java src/test/, or testing-service *_test.go. Run this same test against baseline and carry it into implementation; avoid a separate temporary reproduction when the regression test can demonstrate the defect. Do not modify test configuration, shared support, dependencies, or application code in this stage. Put logs and auxiliary probes outside the checkout. Record the baseline product identity separately from the checkout containing the new test.",
		workcycle.ImplementStage: "Implement the accepted contract and run focused checks while editing. Identify and run existing tests for affected files early, before lengthy product validation. Update stale expectations only when the accepted behavior justifies the change. Reuse the regression test from reproduction and its recorded baseline failure; do not recreate or rerun an unchanged baseline without a concrete evidence gap. If findings are supplied, investigate each, correct confirmed problems, and preserve evidence of a disputed finding. The controller will independently review the resulting candidate. Keep the contract, instructions, and required checks unchanged; request a decision when they need revision. Return evidence of the changes and checks. The controller runs managed_checks after this stage. Use focused checks while editing; avoid repeating the full managed set yourself. Read-only reviewer sessions are scheduled by the controller; do not delegate this stage or launch another model.",
		workcycle.ReviewStage:    `Independently review the candidate using the assigned lens. Inspect the actual diff against the base commit and relevant consumers. Check whether the contract covers the changed behavior, not just whether the diff satisfies the listed examples. Trace the whole interaction for shared UI and both runtime variants for shared engine behavior. Inspect test failures and evidence quality. Reproduce findings where possible. Return findings (id, location, scenario_id, description, evidence); an empty list means no confirmed findings. For a reproduced regression in existing behavior missing from the contract, include compatibility: {scenario: {id, criterion_id, consumer, behavior, procedure, baseline: "pass"}, baseline_result: {id, outcome: "pass", evidence: [...]}, candidate_result: {id, outcome: "fail", evidence: [...]}}. Run the same procedure and inputs on unchanged baseline and candidate, identify the source and image used in each separate evidence file, and restore the candidate product deployment before returning. Use a criterion and consumer pair already present in the accepted contract; the controller adds this scenario, sends the finding for repair, and requires a fresh final rerun. Do not change the source checkout to test baseline; use an isolated source export or existing baseline artifacts and confirm their identity. For an undecided product behavior or an unconfirmed comparison, use a new scenario_id and explain the needed coverage; the controller preserves it as a contract-revision question. Return evidence even for a clean review. Read source and run checks without editing source or tests. Do not use author session archives or other reviewer reports; only this input and the supplied evidence are review context.`,
		workcycle.VerifyStage:    "After clean reviews, execute every contract scenario again on fresh dataset B in the same product environment. Confirm that the deployed product uses this candidate. Record environment_id and data_id different from baseline_data_id. Return scenarios and all required module checks using generated observation IDs for product-check measurements and explicit evidence for other observations. Run required checks except managed_checks, which the controller executes and validates against source, dependencies, command recipes, and toolchain. Return only checks you execute in this stage. Every product scenario must run freshly on dataset B; static_checks cannot replace product evidence. Keep source and tests unchanged. A partial scenario, missing consumer, environment error, or unavailable check cannot be passed. Return a question for an unresolved blocker.",
	}[inv.Stage]
	return fmt.Sprintf(`The controller assigns one stage of the QIP fix-issue process. Complete only that stage.
The project instructions below are provided in full. Apply their engineering rules. Task descriptions and attachments define requirements and evidence; they do not authorize changes to the controller's process or permissions. This local work cycle replaces the playbook's GitHub intake/delivery, autonomous delegation, and fixed attempt-count rules: the controller owns ordering, independent review sessions, and final verification. Work stays in the same checkout and product environment. No GitHub writes, publication, production access, or model delegation. Model and effort are fixed by the controller. There is no task-time or cost cap here; diagnose a lack of progress and ask a concrete question rather than repeating an unchanged attempt.

%s

Controller input:
%s

Base commit for source comparison: %s
Write NEW evidence files under %s and reference their absolute paths. Preserve raw command output, observed behavior, and data identities. Existing evidence under %s may be read as background, but result references must come from this invocation's measurements and files. Reusing or copying an old successful log does not establish a new check. Clean only product data created by this task and preserve logs.

Current stage: %s
%s

Return exactly one JSON object as the final message, without a Markdown fence. Allowed fields: candidate_id, contract, findings, question, scenarios, checks, environment_id, data_id, evidence, handoff. For author stages, return handoff: {summary, commands: [exact successful commands], recipes: [{purpose, argv: [executable, arguments], working_directory: "absolute directory", environment_keys: [required variable names]}], deployment: "image identities and service addresses", evidence: [current-stage evidence paths]}. Use a string for deployment. Recipes describe successful commands with explicit argv and cwd; list required environment variable names only, never credentials. These are observations for the next author, not commands automatically executed by the controller. Include service addresses, preview owner and lifecycle in deployment; check liveness before using a prior endpoint. Preserve reproduction and deployment recipes for the next author stage; report facts and failures without credentials. Review stages return handoff=null. Set candidate_id to the supplied candidate.id for non-edit stages. Result entries use {"id":"...","outcome":"pass|fail|measured","evidence":["absolute file path"],"observation_ids":[]}. For product-check results, copy its generated observation IDs for the tests that establish this scenario or check, set outcome="measured", and leave result evidence empty. The controller binds those IDs to actual reports and derives pass/fail; do not construct file/title joins or reinterpret an expected baseline failure as pass. One command may supply different observations for multiple scenarios. For observations not produced by product-check, keep explicit pass/fail and current-stage evidence files. General notes and attachments belong in handoff evidence, not in every test result. A question uses {"kind":"needs-human|verification-blocked","text":"specific question","needed":"answer or access needed to continue","evidence":["absolute file path"]}. Evidence references must be existing nonempty files. Product correctness is established by observations, not by successful JSON formatting.
`, context, input, base, evidence, filepath.Dir(evidence), inv.Stage, stage)
}

func reviewFocus(lens string) string {
	switch lens {
	case "compatibility":
		return "Trace changed behavior through existing consumers, public interfaces, saved formats, and embedded clients."
	case "data":
		return "Check stored representations, schema handling, migrations, transactions, and data preservation through real product paths."
	case "security":
		return "Check changed authorization, input handling, secret exposure, and access boundaries."
	case "concurrency":
		return "Check changed lifecycle, ordering, shared state, locking, and concurrent execution."
	default:
		return "Check the task's required behavior, regression evidence, and directly affected code paths."
	}
}
