package doctriage

import (
	"context"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/jev"
	"testing"
)

func TestIncompletePreflightHasBoundIdentityAndNoProviderAttempt(t *testing.T) {
	p := packet()
	p.Complete = false
	r := SkipIncomplete(p, jev.DefaultModel)
	if !IsPreflightSkip(r) || r.InputSHA256 == "" || r.PacketSHA256 == "" || r.RequestAttempted || r.Response != nil {
		t.Fatal(r)
	}
	route, e := SelectRoute(p, r, DefaultRoutingPolicy())
	if e != nil || route.Route != "docs-assess" || route.Recommendation.Action != "investigate" {
		t.Fatal(route, e)
	}
	for _, damage := range []string{"complete", "hash", "response", "attempted", "http", "duration", "success"} {
		t.Run(damage, func(t *testing.T) {
			copy := r
			input := p
			switch damage {
			case "complete":
				input.Complete = true
				copy = SkipIncomplete(input, jev.DefaultModel)
			case "hash":
				copy.InputSHA256 = "wrong"
			case "response":
				copy.Response = &jev.Response{ID: "fake"}
			case "attempted":
				copy.RequestAttempted = true
			case "http":
				copy.HTTPStatus = 400
			case "duration":
				copy.DurationMS = 1
			case "success":
				copy.Recommendation.Action = "no_change"
			}
			if e := ValidateReport(input, copy); e == nil {
				t.Fatal("invalid skipped request accepted", damage)
			}
		})
	}
}
func TestEvaluateRecordsRealAttemptAndPreservesLegacyResponse(t *testing.T) {
	r, e := Evaluate(context.Background(), packet(), jev.DefaultModel, &fake{action: "no_change", reason: "outside_help_scope", attribution: "unchanged"})
	if e != nil || !r.RequestAttempted {
		t.Fatal(r, e)
	}
	r.RequestAttempted = false
	if e = ValidateReport(packet(), r); e != nil {
		t.Fatal("legacy response rejected", e)
	}
	route, e := SelectRoute(packet(), r, DefaultRoutingPolicy())
	if e != nil || route.Route != "docs-no-change" {
		t.Fatal(route, e)
	}
}
