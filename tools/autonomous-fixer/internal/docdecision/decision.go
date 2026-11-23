package docdecision

import "fmt"

func Validate(action, reason string) error {
	allowed := map[string][]string{
		"needed":      {"missing_guidance", "stale_guidance"},
		"no_change":   {"already_covered", "restored_behavior", "no_explanation_needed", "internal_only", "outside_help_scope", "preexisting_only"},
		"investigate": {"insufficient_evidence", "scope_unclear"},
	}
	for _, candidate := range allowed[action] {
		if reason == candidate {
			return nil
		}
	}
	return fmt.Errorf("inconsistent documentation decision %q and reason %q", action, reason)
}
