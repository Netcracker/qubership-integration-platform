package autofollow

import (
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentci"
)

func TestRepairStillRunningUsesChecksFromBothCommits(t *testing.T) {
	snapshot := agentci.Snapshot{HeadSHA: headSHA, TestMergeSHA: nextSHA, Checks: []agentci.Check{
		{Name: "Build", Kind: "check-run", AppID: 15368, SHA: headSHA, Status: "failed"},
		{Name: "Test", Kind: "check-run", AppID: 15368, SHA: nextSHA, Required: true, Status: "pending"},
	}}
	if !repairStillRunning(snapshot) {
		t.Fatal("a head failure and a pending required merge check should delay the batch")
	}
	snapshot.Checks = append(snapshot.Checks, agentci.Check{
		Name: "Build", Kind: "check-run", AppID: 15368, SHA: nextSHA, Status: "passed",
	})
	if repairStillRunning(snapshot) {
		t.Fatal("the corresponding passing merge check replaced the head failure")
	}
}
