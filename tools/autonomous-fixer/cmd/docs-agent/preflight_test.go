package main

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docgithub"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docpacket"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doctriage"
)

func TestIncompletePacketSkipsCredentialLookup(t *testing.T) {
	t.Setenv("OPENROUTER_API_KEY", "")
	p := doctriage.Packet{ID: "issue-12", Scope: "implementation", Repository: "o/r", Issue: json.RawMessage(`{"number":12}`), Complete: false, Limitations: []string{"source was truncated"}}
	key := filepath.Join(t.TempDir(), "key-that-does-not-exist")
	report, e := classifyPacket(context.Background(), p, key)
	if e != nil || !doctriage.IsPreflightSkip(report) || report.RequestAttempted || report.Response != nil {
		t.Fatal(report, e)
	}
	p.PartialContext = true
	p.Diff = "diff --git a/path b/path"
	p.Documentation = []doctriage.Document{{Path: "help/docs/guide.md", Content: "Current guide"}}
	p.BaseSHA = "base"
	p.HeadSHA = "head"
	p.DocsSHA = "docs"
	p.ChangedPaths = []string{"file"}
	if _, e = classifyPacket(context.Background(), p, key); e == nil {
		t.Fatal("usable partial packet skipped the provider credential lookup")
	}
}

func TestWorkPersistsRouteBeforeWorktreeFailure(t *testing.T) {
	root := t.TempDir()
	prepared := filepath.Join(root, "prepared")
	output := filepath.Join(root, "result")
	base := strings.Repeat("a", 40)
	packet := doctriage.Packet{ID: "issue-12", Scope: "implementation", Repository: "o/r",
		Issue: json.RawMessage(`{"number":12}`), DocsSHA: base}
	plan := docgithub.Plan{
		Version: "docs-plan-v2", IssueState: "closed", Repository: packet.Repository, IssueNumber: 12,
		IssueNodeID: "ISSUE", IssueTitle: "Update workflow", IssueBodyHash: docgithub.Digest("Issue body"),
		DefaultBranch: "main", BaseSHA: base, PacketSHA256: docgithub.Digest(packet),
		Project:         docgithub.Project{ID: "PROJECT", ItemID: "ITEM", StatusFieldID: "FIELD", ReviewOptionID: "REVIEW", DoneOptionID: "DONE"},
		ImplementingPRs: []docpacket.PR{{Number: 42, MergeSHA: base, Target: "main"}},
	}
	plan.ID = docgithub.Digest(plan)
	if err := os.Mkdir(prepared, 0700); err != nil {
		t.Fatal(err)
	}
	if err := save(filepath.Join(prepared, "packet.json"), packet); err != nil {
		t.Fatal(err)
	}
	if err := save(filepath.Join(prepared, "plan.json"), plan); err != nil {
		t.Fatal(err)
	}
	config := filepath.Join(root, "config.json")
	writeFixtureFile(t, config, `{}`, 0600)
	err := run(context.Background(), []string{"work", "--prepared", prepared, "--output", output,
		"--source", filepath.Join(root, "missing-repo"), "--workspace", filepath.Join(root, "candidate"), "--runtime-config", config})
	if err == nil || !strings.Contains(err.Error(), "git worktree") {
		t.Fatalf("work error = %v, want worktree failure", err)
	}
	var routing doctriage.Routing
	if err = read(filepath.Join(output, "routing.json"), &routing); err != nil {
		t.Fatal(err)
	}
	if routing.Route != "docs-assess" || routing.ReasonCode != doctriage.RouteIncompleteEvidence {
		t.Fatalf("early routing = %+v", routing)
	}
	diagnostics := t.TempDir()
	if err = writeWorkSummary(prepared, output, diagnostics, "failure", ""); err != nil {
		t.Fatal(err)
	}
	var summary workDiagnostics
	if err = read(filepath.Join(diagnostics, "summary.json"), &summary); err != nil {
		t.Fatal(err)
	}
	if summary.Status != nil || summary.Route == nil || *summary.Route != "docs-assess" ||
		summary.RoutingReason != "Issue evidence was incomplete, so the workflow selected assessment." {
		t.Fatalf("early failure summary = %+v", summary)
	}
}
