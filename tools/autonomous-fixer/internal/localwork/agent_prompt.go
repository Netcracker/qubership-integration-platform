package localwork

import (
	"encoding/json"
	"fmt"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func agentAuthorPrompt(input workcycle.AgentAuthorRequest, evidence, base, taskDirectory, productCheck, delegation string) string {
	input.Candidate.FileHashes = nil
	data, _ := json.MarshalIndent(input, "", "  ")
	return fmt.Sprintf(`Complete the QIP issue in this checkout using the supplied project conventions.
You own the engineering work: inspect the code, write acceptance criteria for yourself, reproduce the reported defect and a healthy neighboring behavior, implement the fix, and run focused tests and live product checks. Choose the order and tools that help you establish the behavior. All source changes needed for the issue are allowed during your work. Preserve a regression case that fails without the fix and passes with it when the project rules call for one.

The controller owns independent reviews, managed static checks, and final verification. Use focused checks while editing; avoid repeating the full managed check set without a concrete need. Do not publish, commit, push, comment, or change anything in GitHub. The supplied local assignment replaces the playbook's GitHub intake, delivery, delegation, and fixed attempt-count rules. Use the supplied task packet; do not repeat GitHub intake or refetch issue and pull request history. Model and effort are fixed by the controller.

%s

Work in the existing product environment. Check the actual source, image, service, and data identities before using earlier recipes or observations. Preserve raw output and enough detail to rerun your checks. Original issue images, attachments, and preflight records are valid references, but they do not prove that the current candidate works. Final independent verification reruns all required live scenarios on fresh dataset B in this same environment.

Use any convenient evidence format: prose, logs, screenshots, test reports, file paths, and links. A suggested directory for new evidence is %q. Existing evidence may be referenced wherever it lives. No JSON response, phase transition, observation ID, or evidence envelope is required. Finish with normal prose explaining the change, measured results, remaining failures, and useful evidence references. If a product decision or missing access blocks you, explain what is needed and what you observed; do not claim success.

Controller input:
%s

candidate.id is an opaque controller identifier. Compare source against the base commit and inspect deployed product identity; do not interpret candidate.id as a Git or image hash.
Base commit for source comparison: %s
Task attachment directory: %q. Read the complete selected attachments when needed.
%s
`, delegation, evidence, data, base, taskDirectory, optionalAgentProductCheck(productCheck, evidence))
}

func agentAuthorContinuationPrompt(input workcycle.AgentAuthorRequest, evidence, delegation string) string {
	input.Candidate.FileHashes = nil
	data, _ := json.MarshalIndent(struct {
		Candidate workcycle.Snapshot        `json:"candidate"`
		Feedback  []string                  `json:"feedback,omitempty"`
		Routing   []workcycle.RoutingAdvice `json:"routing,omitempty"`
	}{input.Candidate, input.Feedback, input.Routing}, "", "  ")
	return fmt.Sprintf(`Continue the same QIP issue in this native session. The original task, acceptance criteria, required consumers, project conventions, permissions, and product environment are unchanged.
Investigate the feedback below, repair confirmed problems, and run focused checks for the changes. Use the earlier observations and recipes in this session after checking their current source and product identity. The controller owns independent reviews, managed static checks, and final verification. Preserve new raw results and useful references under %q or another convenient location. Finish in normal prose with your changes, measured results, evidence references, and any blocker.

%s

Continuation input:
%s

candidate.id is an opaque controller identifier, not a Git or image hash.
`, evidence, delegation, data)
}

func agentAssessmentPrompt(input workcycle.AgentAssessmentRequest, evidence, base, taskDirectory, productCheck string) string {
	if input.Stage == workcycle.VerificationReassessmentStage {
		return verificationReassessmentPrompt(input)
	}
	input.Candidate.FileHashes = nil
	data, _ := json.MarshalIndent(input, "", "  ")
	assignment := agentReviewAssignment(input.Lens, input.ReviewDelta)
	approval := "Approve when the source and evidence support the behavior in your assigned review scope."
	fields, coverage := "decision, summary, and findings", ""
	access := fmt.Sprintf("Read source and run checks without editing source or tests. Work remains in the supplied checkout and product environment. Preserve raw results, data identities, and useful evidence under %q or another convenient location. Confirm deployed source and image identity before using the product.", evidence)
	if input.CodeOnly {
		access = "Inspect the immutable source copy and selected task attachments with read-only commands. Inspect tests by reading them. Cite source locations as repository-relative path and line number. The separate final verifier runs the live scenarios in the product environment."
		productCheck = ""
	} else if input.Stage == workcycle.ReviewStage {
		assignment += "\nUse focused tests or product checks to resolve questions in this scope. The final verifier runs the complete live scenario matrix."
	}
	if input.Stage == workcycle.VerifyStage {
		assignment = verificationAssignment(input)
		approval = "Approval requires successful observations for all required cases."
		fields, coverage = "decision, summary, findings, and coverage", verificationDisposition
	}
	return fmt.Sprintf(`You are an independent QIP assessor in a fresh native session.
Use the task, trusted project conventions, required criteria, consumer pairs, and checks supplied below. Evaluate the supplied evidence against actual source and behavior. Use original issue materials for requirements and history. Use the controller-selected evidence and any supplied repair findings. Do not read private session archives or other reviewer reports.

%s

%s
Do not launch another model or delegate work. Do not commit, publish, push, or write to GitHub. You may cite files, images, links, and existing source materials without constructing path IDs.

Controller input:
%s

candidate.id is an opaque controller identifier.
Base commit for source comparison: %s
Task attachment directory: %q. Read the complete selected attachments when needed.
%s

Finish with one JSON object containing %s. decision is approve, changes-requested, needs-human, or verification-blocked. summary is a concrete explanation of your observations. findings is an array of strings describing confirmed defects and useful evidence references. Use an empty array when there are none. %s Use needs-human when your assignment requires an owner decision. Use verification-blocked when missing access or an unavailable environment prevents a required observation.
%s
`, assignment, access, data, base, taskDirectory, optionalAgentProductCheck(productCheck, evidence), fields, approval, coverage)
}

func agentReviewAssignment(lens string, delta *workcycle.AgentReviewDelta) string {
	assignment := "Independently review the candidate within the assigned scope. " + reviewFocus(lens)
	switch lens {
	case "correctness":
		assignment += " Inspect regression coverage for the reported defect and healthy neighboring behavior affected by the patch."
	case "compatibility":
		assignment += " Use the required consumer pairs and actual callers to identify affected entry and exit paths. For changes to shared UI behavior, trace the browser app and the VS Code webview where each consumes that code."
	}
	switch {
	case delta == nil:
		assignment += "\nInspect the candidate diff against the base commit. Read new files listed in candidate.changed_paths."
	case !delta.ChangedPathsKnown:
		assignment += "\nThe changed paths since the previous review are incomplete. Inspect the candidate diff against the base commit for the full scope of this review and recheck any supplied findings."
	case len(delta.ChangedPaths) == 0:
		assignment += "\nThe source matches the previous review. Recheck the supplied findings using current observations."
	default:
		assignment += "\nStart with the paths changed since the previous review and any supplied findings. Check the repairs and their effects on relevant callers. Read the current source of those paths even if a repair restored the base version. Expand the review when the repair changes shared behavior, an interface, or assumptions elsewhere in your assigned scope."
	}
	return assignment
}

func optionalAgentProductCheck(binary, evidence string) string {
	if binary == "" {
		return ""
	}
	return fmt.Sprintf("\nOptional product-check adapter: %q. Run --help for its command syntax. It can capture actual child status and fresh test reports under %q. Inspect the raw report and assertion failures; a successful shell exit alone does not prove the scenario. No observation IDs are required in your response.\n", binary, evidence)
}
