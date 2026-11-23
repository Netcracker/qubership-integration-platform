package doccycle

import (
	"fmt"
	"sort"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

type claimIdentity struct {
	attribution string
	needsChange bool
	separate    bool
}
type claimRequirements map[string]claimIdentity

func (r claimRequirements) retain(d *workcycle.DocumentationDecision) {
	for _, c := range d.Claims {
		r[c.ID] = claimIdentity{c.Attribution, c.NeedsChange, false}
	}
	for _, c := range d.SeparateFindings {
		r[c.ID] = claimIdentity{c.Attribution, c.NeedsChange, true}
	}
}
func (r claimRequirements) check(d *workcycle.DocumentationDecision) error {
	present := claimRequirements{}
	present.retain(d)
	ids := make([]string, 0, len(r))
	for id := range r {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	for _, id := range ids {
		identity, ok := present[id]
		if !ok {
			return fmt.Errorf("documentation decision dropped claim %s", id)
		}
		if identity != r[id] {
			return fmt.Errorf("documentation decision reclassified claim %s", id)
		}
	}
	return nil
}
func allClaims(d *workcycle.DocumentationDecision) []workcycle.DocClaim {
	claims := make([]workcycle.DocClaim, 0, len(d.Claims)+len(d.SeparateFindings))
	claims = append(claims, d.Claims...)
	return append(claims, d.SeparateFindings...)
}
