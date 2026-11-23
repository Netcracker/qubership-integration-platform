package main

import (
	"bytes"
	"encoding/json"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
	"testing"
)

func TestExitReflectsReportedOutcome(t *testing.T) {
	for status, success := range map[string]bool{"verified": true, "docs-ready-for-pr": true, "docs-no-change": true, "verification-blocked": false, "needs-human": false, "interrupted": false, "rejected": false, "running": false} {
		var out bytes.Buffer
		err := finish(&out, workcycle.Report{Status: status}, nil)
		var saved workcycle.Report
		if json.Unmarshal(out.Bytes(), &saved) != nil || saved.Status != status || (err == nil) != success {
			t.Errorf("%s: returned %v, printed status %q", status, err, saved.Status)
		}
	}
}

func TestNativeAuthorReportIsACompletedInvocationWithoutVerification(t *testing.T) {
	for status, success := range map[string]bool{"author-reported-fixed": true, "author-reported": false, "needs-human": false, "blocked": false} {
		var out bytes.Buffer
		report := workcycle.Report{Workflow: "fix-issue", Status: status}
		if err := finish(&out, report, nil); (err == nil) != success || report.Successful() {
			t.Fatal(report, err)
		}
	}
}
