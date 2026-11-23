package experiment

import (
	"context"
	"math"
	"os"
	"path/filepath"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentcost"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentusage"
)

func metricTokens(n int64) *int64 { return &n }

func TestRoleMetricsPriceActualModelsAndPreserveUnknownCoverage(t *testing.T) {
	u := experimentcost.Usage{Input: metricTokens(1000), CachedInput: metricTokens(500), CacheWrite: metricTokens(0), Output: metricTokens(100), Reasoning: metricTokens(50)}
	r := experimentusage.Report{Complete: true, Tokens: u, Models: []experimentusage.ModelUsage{
		{Model: "gpt-6.1-sol", Requests: []experimentcost.Usage{u}},
		{Model: "gpt-6.1-terra", Requests: []experimentcost.Usage{u}},
	}}
	p := Prepared{JobID: "1215-c1-03", Role: "implement", Profile: Profile{Model: "gpt-6.1-sol"}}
	m := roleCost(p, r)
	want := .00205 + .001025
	if m.APIEquivalentUSD == nil || math.Abs(*m.APIEquivalentUSD-want) > 1e-12 || len(m.ModelCosts) != 2 {
		t.Fatalf("unexpected mixed-model cost: %+v", m)
	}
	r.Complete = false
	m = roleCost(p, r)
	if m.APIEquivalentUSD != nil || math.Abs(m.KnownCostUSD-want) > 1e-12 {
		t.Fatalf("incomplete usage became a full cost: %+v", m)
	}
	p.Profile.Model = "opencode/beam-free"
	m = roleCost(p, experimentusage.Report{})
	if m.APIEquivalentUSD == nil || *m.APIEquivalentUSD != 0 || m.TotalTokens != nil {
		t.Fatalf("free cost must not invent token counts: %+v", m)
	}
}

func TestRefreshMetricsWhileExecutionLockHeldSkipsActiveRoles(t *testing.T) {
	root, _ := campaign(t)
	p, err := Prepare(context.Background(), root, "1215-o3-01", "implement")
	if err != nil {
		t.Fatal(err)
	}
	dir, _ := RunDirectory(root, p.JobID, p.Role)
	executionDir := filepath.Join(dir, "execution")
	if err = os.Mkdir(executionDir, 0700); err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(filepath.Join(executionDir, "stdout.jsonl"), []byte("{\"type\":\"step_finish\",\"sessionID\":\"s\",\"part\":{\"id\":\"p\",\"tokens\":{\"input\":10,\"output\":2,\"reasoning\":3,\"cache\":{\"read\":4,\"write\":0}}}}\n"), 0600); err != nil {
		t.Fatal(err)
	}
	unlock, err := Lock(root)
	if err != nil {
		t.Fatal(err)
	}
	defer unlock()
	s, err := RefreshMetrics(root, "", "implement")
	if err != nil || s.Total.CompletedRoles != 0 {
		t.Fatalf("active role was included: %+v %v", s, err)
	}
	if err = WriteJSON(filepath.Join(executionDir, "result.json"), Execution{JobID: p.JobID, Role: p.Role, Profile: p.Profile, Outcome: "unverified"}); err != nil {
		t.Fatal(err)
	}
	s, err = RefreshMetrics(root, "", "implement")
	if err != nil || s.Total.CompletedRoles != 1 || s.Total.APIEquivalentUSD == nil || *s.Total.APIEquivalentUSD != 0 {
		t.Fatalf("completed free role missing: %+v %v", s, err)
	}
	var m RoleMetrics
	if err = ReadJSON(filepath.Join(executionDir, "usage-cost.json"), &m); err != nil {
		t.Fatal(err)
	}
	if m.TotalTokens == nil || *m.TotalTokens != 19 {
		t.Fatalf("cache and reasoning were not normalized: %+v", m.Usage)
	}
}

func TestMetricTotalsIncludePaidReviewsAndRetainUnknownCosts(t *testing.T) {
	total := emptyMetricTotal()
	free, paid := 0.0, 1.25
	addMetric(&total, "implementation", &free, 0, experimentcost.Usage{})
	addMetric(&total, "review", &paid, paid, experimentcost.Usage{})
	if total.APIEquivalentUSD == nil || *total.APIEquivalentUSD != paid {
		t.Fatal("free implementation hid paid review")
	}
	addMetric(&total, "unknown", nil, .5, experimentcost.Usage{})
	if total.APIEquivalentUSD != nil || total.KnownCostUSD != 1.75 || len(total.UnknownCostRoles) != 1 {
		t.Fatalf("partial cost was reported as complete: %+v", total)
	}
}
