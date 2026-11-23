package localwork

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func TestFinalReceiptFailureDoesNotPublishVerifiedReport(t *testing.T) {
	d, err := Prepare(context.Background(), agentFixture(t))
	if err != nil {
		t.Fatal(err)
	}
	s, err := d.Snapshot(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	r := workcycle.Report{Workflow: "agent", Status: "running", Candidate: s}
	if err = d.Save(context.Background(), r); err != nil {
		t.Fatal(err)
	}
	if err = os.Mkdir(filepath.Join(d.Config.OutputDirectory, "receipt.json"), 0700); err != nil {
		t.Fatal(err)
	}
	r.Status = "verified"
	if err = d.Save(context.Background(), r); err == nil {
		t.Fatal("expected receipt write failure")
	}
	data, err := os.ReadFile(filepath.Join(d.Config.OutputDirectory, "report.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err = json.Unmarshal(data, &r); err != nil || r.Status == "verified" {
		t.Fatalf("status %q after receipt write failure: %v", r.Status, err)
	}
}
