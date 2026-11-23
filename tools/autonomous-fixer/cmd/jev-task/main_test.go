package main

import (
	"context"
	"os"
	"path/filepath"
	"testing"
)

func TestDryRunNeedsNoKeyAndPreservesEarlierOutput(t *testing.T) {
	root := t.TempDir()
	p := filepath.Join(root, "packet.json")
	out := filepath.Join(root, "out")
	if e := os.WriteFile(p, []byte(`{"task_id":"t","phase":"intake","task":"fix"}`), 0600); e != nil {
		t.Fatal(e)
	}
	args := []string{"--packet", p, "--output", out}
	if e := run(context.Background(), args); e != nil {
		t.Fatal(e)
	}
	if _, e := os.Stat(filepath.Join(out, "request.json")); e != nil {
		t.Fatal(e)
	}
	if _, e := os.Stat(filepath.Join(out, "report.json")); !os.IsNotExist(e) {
		t.Fatal("dry run produced a prediction")
	}
	if e := run(context.Background(), args); e == nil {
		t.Fatal("existing output overwritten")
	}
}
