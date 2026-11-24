package main

import (
	"errors"
	"os"
	"path/filepath"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docgithub"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func TestWorkStopReasonUsesOnlyKnownCategories(t *testing.T) {
	for _, tc := range []struct{ status, diagnostic, want string }{
		{"rejected", "decode stage result: json: unknown field \"arbitrary model text\"", "rejected-result-format"},
		{"rejected", "raw model text", "rejected"},
		{"needs-human", "raw model text", "needs-human"},
		{"verification-blocked", "raw model text", "verification-blocked"},
		{"docs-ready-for-pr", "raw model text", ""},
		{"arbitrary model text", "", ""},
	} {
		if got := workStopReason(workcycle.Report{Status: tc.status, Error: tc.diagnostic}); got != tc.want {
			t.Errorf("status=%q, diagnosis=%q, got %q, want %q", tc.status, tc.diagnostic, got, tc.want)
		}
	}
}

func TestReportPublicationFailureOnlyWritesTheCategory(t *testing.T) {
	output := filepath.Join(t.TempDir(), "output")
	t.Setenv("GITHUB_OUTPUT", output)
	if err := os.WriteFile(output, []byte("attempt=2\n"), 0600); err != nil {
		t.Fatal(err)
	}
	unknown := errors.New("GitHub diagnostic\nextra=untrusted text")
	if err := reportPublicationFailure(unknown); err != unknown {
		t.Fatalf("unknown error changed: %v", err)
	}
	client := &docgithub.Client{Repository: "org/repo"}
	_, publishErr := docgithub.Publish(t.Context(), client, docgithub.Plan{Repository: client.Repository}, "", docgithub.PublishOptions{})
	if err := reportPublicationFailure(publishErr); publishErr == nil || !errors.Is(err, publishErr) {
		t.Fatalf("publication error was lost: %v", err)
	}
	data, err := os.ReadFile(output)
	if err != nil || string(data) != "attempt=2\npublication_failure=validate-candidate\n" {
		t.Fatalf("workflow output=%q, error=%v", data, err)
	}
}
