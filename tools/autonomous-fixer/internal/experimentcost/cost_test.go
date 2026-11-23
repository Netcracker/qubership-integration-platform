package experimentcost

import (
	"math"
	"testing"
)

func count(n int64) *int64 { return &n }
func usage(i, c, w, o int64) Usage {
	return Usage{Input: count(i), CachedInput: count(c), CacheWrite: count(w), Output: count(o)}
}
func total(t *testing.T, e Estimate, want float64) {
	t.Helper()
	if e.TotalUSD == nil || math.Abs(*e.TotalUSD-want) > 1e-12 {
		t.Fatalf("estimate=%+v want=%v", e, want)
	}
}
func TestComponents(t *testing.T) {
	u := usage(1000, 400, 200, 300)
	u.Reasoning = count(200)
	e := Calculate("gpt-6.1-sol", []Usage{u})
	total(t, e, .0008+.00004+.0005+.003)
	if e.Components.CacheWriteUSD != .0005 || len(e.Sources) != 1 || e.PricingVersion != "2026-11-08-standard" {
		t.Fatalf("estimate=%+v", e)
	}
}
func TestRequestThreshold(t *testing.T) {
	e := Calculate("gpt-6.1-sol", []Usage{usage(272000, 0, 0, 100), usage(272001, 100, 200, 100)})
	total(t, e, 272000*2.0/1e6+.001+(271701*2.0+100*.1+200*2.5)*2/1e6+.0015)
	if e.LongRequests != 1 {
		t.Fatalf("long requests=%d", e.LongRequests)
	}
	e = Calculate("gpt-6.1-sol", []Usage{usage(200000, 0, 0, 0), usage(200000, 0, 0, 0)})
	total(t, e, .8)
	if e.LongRequests != 0 {
		t.Fatal("aggregate input triggered long pricing")
	}
}
func TestRates(t *testing.T) {
	for _, tt := range []struct {
		model string
		want  float64
	}{{"gpt-6.1-sol", .2}, {"gpt-6.1-terra", .1}, {"gpt-6-luna", .01}} {
		total(t, Calculate(tt.model, []Usage{usage(100000, 0, 0, 0)}), tt.want)
	}
}
func TestFreeModels(t *testing.T) {
	for _, m := range []string{"opencode/deepseek-v4.1-flash-free", "opencode/glm-5.3-flash-free", "opencode/mimo-v2.6-flash-free", "opencode/beam-free"} {
		for _, r := range [][]Usage{nil, {{}}, {usage(-1, 0, 0, 0)}} {
			e := Calculate(m, r)
			total(t, e, 0)
			if !e.Free || len(e.Reasons) == 0 {
				t.Fatalf("estimate=%+v", e)
			}
		}
	}
}
func TestUnknownModels(t *testing.T) {
	for _, m := range []string{"unknown", "opencode/unknown-free", "openai/gpt-6.1-sol", "gpt-6.1-sol-latest"} {
		e := Calculate(m, []Usage{usage(1, 0, 0, 1)})
		if e.TotalUSD != nil || e.Components != nil || len(e.Reasons) == 0 {
			t.Fatalf("estimate=%+v", e)
		}
	}
}
func TestInvalidUsage(t *testing.T) {
	for _, u := range []Usage{{}, {Input: count(1), Output: count(1)}, usage(-1, 0, 0, 0), usage(1, -1, 0, 0), usage(1, 0, -1, 0), usage(1, 0, 0, -1), usage(10, 9, 2, 0), usage(math.MaxInt64, math.MaxInt64, math.MaxInt64, 0), {Input: count(1), CachedInput: count(0), CacheWrite: count(0), Output: count(1), Reasoning: count(2)}, {Input: count(1), CachedInput: count(0), CacheWrite: count(0), Output: count(1), Reasoning: count(-1)}} {
		e := Calculate("gpt-6.1-sol", []Usage{usage(100, 0, 0, 0), u})
		if e.TotalUSD != nil || e.Components != nil || len(e.Reasons) == 0 {
			t.Fatalf("estimate=%+v", e)
		}
	}
	if Calculate("gpt-6.1-sol", nil).TotalUSD != nil {
		t.Fatal("missing requests priced as zero")
	}
	total(t, Calculate("gpt-6.1-sol", []Usage{usage(0, 0, 0, 0)}), 0)
}
