package main

import (
	"context"
	"encoding/json"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doctriage"
	"path/filepath"
	"testing"
)

func TestIncompletePacketSkipsCredentialLookup(t *testing.T) {
	t.Setenv("OPENROUTER_API_KEY", "")
	p := doctriage.Packet{ID: "issue-12", Scope: "implementation", Repository: "o/r", Issue: json.RawMessage(`{"number":12}`), Complete: false, Limitations: []string{"source was truncated"}}
	key := filepath.Join(t.TempDir(), "key-that-does-not-exist")
	report, e := classifyPacket(context.Background(), p, key)
	if e != nil || !doctriage.IsPreflightSkip(report) || report.RequestAttempted || report.Response != nil {
		t.Fatal(report, e)
	}
	p.Complete = true
	p.BaseSHA = "base"
	p.HeadSHA = "head"
	p.DocsSHA = "docs"
	p.ChangedPaths = []string{"file"}
	if _, e = classifyPacket(context.Background(), p, key); e == nil {
		t.Fatal("complete packet ignored missing credentials")
	}
}
