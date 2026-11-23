package doctriage

import (
	"math"
	"testing"
)

func auditIdentity() AuditIdentity {
	return AuditIdentity{Repository: "Netcracker/QIP", Issue: 12, BaseSHA: "base", ImplementationHeadSHA: "head", SourceSHA: "source", DocsSHA: "docs"}
}
func TestAuditSampleIsDeterministicAndFractionOnlyChangesSelection(t *testing.T) {
	id := auditIdentity()
	a, e := SampleAudit(id, "hosted", .1)
	if e != nil {
		t.Fatal(e)
	}
	b, e := SampleAudit(id, "hosted", .1)
	if e != nil || a != b {
		t.Fatal(a, b, e)
	}
	none, _ := SampleAudit(id, "hosted", 0)
	all, _ := SampleAudit(id, "hosted", 1)
	if none.Selected || !all.Selected || none.SampleSHA256 != all.SampleSHA256 || a.Score < 0 || a.Score >= 1 {
		t.Fatal(none, all)
	}
	at, _ := SampleAudit(id, "hosted", a.Score)
	above, _ := SampleAudit(id, "hosted", math.Nextafter(a.Score, 1))
	if at.Selected || !above.Selected {
		t.Fatal(at, above)
	}
}
func TestAuditRevisionAndSeedHaveSeparateIdentities(t *testing.T) {
	id := auditIdentity()
	a, _ := SampleAudit(id, "hosted", .5)
	for _, change := range []func(*AuditIdentity){func(x *AuditIdentity) { x.Issue++ }, func(x *AuditIdentity) { x.BaseSHA = "new-base" }, func(x *AuditIdentity) { x.ImplementationHeadSHA = "new-head" }, func(x *AuditIdentity) { x.SourceSHA = "new-source" }, func(x *AuditIdentity) { x.DocsSHA = "new-docs" }} {
		next := id
		change(&next)
		b, _ := SampleAudit(next, "hosted", .5)
		if a.RevisionID == b.RevisionID || a.SampleSHA256 == b.SampleSHA256 {
			t.Fatal("changed revision retained sample identity", b)
		}
	}
	b, _ := SampleAudit(id, "other-hosted", .5)
	if a.RevisionID != b.RevisionID || a.SampleSHA256 == b.SampleSHA256 {
		t.Fatal(a, b)
	}
	id.Repository = "netcracker/qip"
	b, _ = SampleAudit(id, "hosted", .5)
	if a != b {
		t.Fatal("repository casing changed selection")
	}
}
func TestAuditRequiresExplicitValidPolicyAndRevision(t *testing.T) {
	for _, fraction := range []float64{-1, 1.1, math.NaN(), math.Inf(1)} {
		if _, e := SampleAudit(auditIdentity(), "hosted", fraction); e == nil {
			t.Fatal(fraction)
		}
	}
	if _, e := SampleAudit(auditIdentity(), "", .1); e == nil {
		t.Fatal("empty seed accepted")
	}
	id := auditIdentity()
	id.DocsSHA = ""
	if _, e := SampleAudit(id, "hosted", .1); e == nil {
		t.Fatal("missing docs revision accepted")
	}
}
