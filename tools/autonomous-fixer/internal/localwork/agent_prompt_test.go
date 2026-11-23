package localwork

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func TestAgentPromptsHideInternalFileHashes(t *testing.T) {
	candidate := workcycle.Snapshot{ID: "opaque-candidate", ChangedPaths: []string{"engine/code.txt"}, FileHashes: map[string]string{"engine/code.txt": "internal-hash-marker"}}
	author := workcycle.AgentAuthorRequest{Candidate: candidate}
	assessor := workcycle.AgentAssessmentRequest{Candidate: candidate, Stage: workcycle.ReviewStage}
	for _, prompt := range []string{
		agentAuthorPrompt(author, "/evidence", "base", "/task", "", "Do not delegate."),
		agentAuthorContinuationPrompt(author, "/new-evidence", "Do not delegate."),
		agentAssessmentPrompt(assessor, "/evidence", "base", "/task", ""),
	} {
		if strings.Contains(prompt, "file_hashes") || strings.Contains(prompt, "internal-hash-marker") {
			t.Fatal("internal hashes entered the model input", prompt)
		}
		if !strings.Contains(prompt, "opaque controller identifier") || !strings.Contains(prompt, "opaque-candidate") || !strings.Contains(prompt, "engine/code.txt") {
			t.Fatal("model input lost the useful candidate identity or changed paths", prompt)
		}
	}
	if author.Candidate.FileHashes["engine/code.txt"] != "internal-hash-marker" || assessor.Candidate.FileHashes["engine/code.txt"] != "internal-hash-marker" {
		t.Fatal("prompt projection modified the archived input")
	}
}

func TestAgentRepairPromptIsCompactAndArchivesFullInvocation(t *testing.T) {
	d, err := Prepare(context.Background(), agentFixture(t))
	if err != nil {
		t.Fatal(err)
	}
	input := workcycle.AgentAuthorRequest{Request: d.Request, Candidate: workcycle.Snapshot{ID: "opaque-candidate", FileHashes: map[string]string{"engine/code.txt": "internal-hash-marker"}}}
	if _, err = d.Author(context.Background(), input); err != nil {
		t.Fatal(err)
	}
	input.Feedback = []string{"Repair the healthy consumer using the new observation."}
	if _, err = d.Author(context.Background(), input); err != nil {
		t.Fatal(err)
	}
	first, err := os.ReadFile(filepath.Join(d.Config.OutputDirectory, "step-0001-author", "prompt.txt"))
	if err != nil {
		t.Fatal(err)
	}
	second, err := os.ReadFile(filepath.Join(d.Config.OutputDirectory, "step-0002-author", "prompt.txt"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(first), "Project conventions.") || !strings.Contains(string(first), "Fix the preserved value.") {
		t.Fatal("initial prompt omitted the full task or conventions", string(first))
	}
	if strings.Contains(string(second), "Project conventions.") || strings.Contains(string(second), "Fix the preserved value.") || strings.Contains(string(second), `"request"`) {
		t.Fatal("repair repeated the frozen task or project packet", string(second))
	}
	if !strings.Contains(string(second), input.Feedback[0]) || !strings.Contains(string(second), "are unchanged") || !strings.Contains(string(second), "step-0002-author") {
		t.Fatal("repair omitted feedback, continuity, or the new evidence location", string(second))
	}
	for _, name := range []string{"step-0001-author", "step-0002-author"} {
		var archived workcycle.AgentAuthorRequest
		readAgentJSON(t, filepath.Join(d.Config.OutputDirectory, name, "invocation.json"), &archived)
		if archived.Request.Context != d.Packet.Text || archived.Request.Task != d.Request.Task || archived.Candidate.FileHashes["engine/code.txt"] != "internal-hash-marker" {
			t.Fatal("complete invocation was changed for model input projection", archived)
		}
	}
}

func TestAgentVerificationAttemptsUseFreshControllerTokens(t *testing.T) {
	d, err := Prepare(context.Background(), agentFixture(t))
	if err != nil {
		t.Fatal(err)
	}
	seen := map[string]bool{}
	for _, name := range []string{"step-0001-verify", "step-0002-verify"} {
		input := workcycle.AgentAssessmentRequest{Request: d.Request, Stage: workcycle.VerifyStage, VerificationToken: "caller-chosen-token", Candidate: workcycle.Snapshot{ID: "opaque-candidate", FileHashes: map[string]string{"engine/code.txt": "internal-hash-marker"}}}
		result, err := d.Assess(context.Background(), input)
		if err != nil || result.Decision != "approve" {
			t.Fatal("fresh observations were rejected without a token echo", result, err)
		}
		dir := filepath.Join(d.Config.OutputDirectory, name)
		var archived workcycle.AgentAssessmentRequest
		readAgentJSON(t, filepath.Join(dir, "invocation.json"), &archived)
		if archived.VerificationToken == "" || archived.VerificationToken == input.VerificationToken || seen[archived.VerificationToken] {
			t.Fatal("verification did not receive a fresh controller token", archived.VerificationToken)
		}
		seen[archived.VerificationToken] = true
		if archived.Candidate.FileHashes["engine/code.txt"] != "internal-hash-marker" {
			t.Fatal("internal source binding disappeared from the archived assessment")
		}
		prompt, err := os.ReadFile(filepath.Join(dir, "prompt.txt"))
		if err != nil || !strings.Contains(string(prompt), archived.VerificationToken) || !strings.Contains(string(prompt), "as a suffix") || strings.Contains(string(prompt), "internal-hash-marker") {
			t.Fatal("verification prompt omitted fresh data guidance or exposed hashes", string(prompt), err)
		}
	}
}
