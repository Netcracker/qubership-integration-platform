package experiment

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestCancelledCampaignCannotExecute(t *testing.T) {
	for _, data := range []string{`{"continuation_authorized":false}`, `{"status":"cancelled-by-user"}`, `{"status":"remaining-experiments-cancelled-by-user","continuation_authorized":true}`} {
		root := t.TempDir()
		if err := os.WriteFile(filepath.Join(root, "queue-policy.json"), []byte(data), 0600); err != nil {
			t.Fatal(err)
		}
		_, err := Execute(context.Background(), root, "unused", "implement", ToolPaths{}, "", "", "")
		if err == nil || !strings.Contains(err.Error(), "cancelled") {
			t.Fatalf("execution passed cancellation gate: %v", err)
		}
		files, _ := os.ReadDir(root)
		if len(files) != 1 {
			t.Fatal("cancelled execution wrote files")
		}
	}
}

func TestContinuationAllowsUncancelledCampaign(t *testing.T) {
	root := t.TempDir()
	if err := CheckContinuation(root); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "queue-policy.json"), []byte(`{"primary_job_order":["job"]}`), 0600); err != nil {
		t.Fatal(err)
	}
	if err := CheckContinuation(root); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "queue-policy.json"), []byte(`broken`), 0600); err != nil {
		t.Fatal(err)
	}
	if err := CheckContinuation(root); err == nil {
		t.Fatal("malformed policy accepted")
	}
}
