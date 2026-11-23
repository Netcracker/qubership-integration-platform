package localwork

import (
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func TestAgentReviewPromptAssignsFocusedConsumerReview(t *testing.T) {
	input := workcycle.AgentAssessmentRequest{
		Stage: workcycle.ReviewStage,
		Lens:  "compatibility",
		ReviewDelta: &workcycle.AgentReviewDelta{
			PreviousCandidateID: "previous",
			ChangedPaths:        []string{"ui/src/page.tsx"},
			ChangedPathsKnown:   true,
			Findings:            []string{"The VS Code webview lost its original header."},
		},
	}
	prompt := agentAssessmentPrompt(input, "/evidence", "base", "/task", "")
	for _, expected := range []string{
		"trace the browser app and the VS Code webview",
		"Start with the paths changed since the previous review",
		"ui/src/page.tsx",
		input.ReviewDelta.Findings[0],
	} {
		if !strings.Contains(prompt, expected) {
			t.Fatalf("missing %q in consumer review prompt", expected)
		}
	}
	if strings.Contains(prompt, "every required criterion and consumer pair") || strings.Contains(prompt, "fresh dataset B") {
		t.Fatal("consumer review received the complete final verification assignment")
	}
	input.ReviewDelta.ChangedPathsKnown = false
	prompt = agentAssessmentPrompt(input, "/evidence", "base", "/task", "")
	if !strings.Contains(prompt, "for the full scope of this review") || strings.Contains(prompt, "Start with the paths changed") {
		t.Fatal("incomplete repair data requires the full review scope")
	}
}
