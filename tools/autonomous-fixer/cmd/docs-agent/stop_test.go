package main

import (
	"testing"

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
