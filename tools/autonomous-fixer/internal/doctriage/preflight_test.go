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

func TestPartialRetrievalRoutes(t *testing.T) {
	for _, tc := range []struct {
		name, path, action, reason, attribution, route string
	}{
		{"release tooling", "scripts/build-bom.sh", "no_change", "outside_help_scope", "unchanged", "docs-no-change"},
		{"mixed change", "ui/src/Panel.tsx", "no_change", "internal_only", "unchanged", "docs-assess"},
		{"missing help", "ui/src/Panel.tsx", "needed", "missing_guidance", "change_related", "docs-write"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			p := packet()
			p.Complete = false
			p.PartialContext = true
			p.Diff = "diff --git a/p b/p"
			p.ChangedPaths = []string{".github/workflows/build.yaml", tc.path}
			if !p.CanClassify() {
				t.Fatal("skipped a usable diff")
			}
			r, err := Evaluate(context.Background(), p, jev.DefaultModel, &fake{action: tc.action, reason: tc.reason, attribution: tc.attribution})
			if err != nil || r.SuggestedRoute != tc.route {
				t.Fatalf("route = %s, want %s; err = %v", r.SuggestedRoute, tc.route, err)
			}
			p.PartialContext = false
			if p.CanClassify() {
				t.Fatal("incomplete packet without verified change evidence reached the classifier")
			}
		})
	}
}

func TestRoutingVersionsDuringReplay(t *testing.T) {
	p := packet()
	current, err := Evaluate(context.Background(), p, jev.DefaultModel, &fake{action: "needed", reason: "missing_guidance", attribution: "change_related"})
	if err != nil {
		t.Fatal(err)
	}
	for _, v := range []struct {
		classifier, routing string
		valid               bool
	}{
		{"docs-impact-v5", "docs-routing-v1", true},
		{"docs-impact-v5", "docs-routing-v2", true},
		{"docs-impact-v6", "docs-routing-v3", true},
		{"docs-impact-v6", "docs-routing-v2", false},
		{"docs-impact-v6", "docs-routing-v1", false},
		{"docs-impact-v5", "docs-routing-v3", false},
	} {
		report := current
		report.PolicyVersion = v.classifier
		route, err := ReplayRoute(p, report, DefaultRoutingPolicy(), v.routing)
		if (err == nil) != v.valid || v.valid && (route.Route != "docs-write" || route.Version != v.routing) {
			t.Errorf("%s/%s: route %s version %s error %v", v.classifier, v.routing, route.Route, route.Version, err)
		}
	}
}
