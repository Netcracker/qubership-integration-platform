package matching

import "slices"

// What the "covers every type" tests reach for: the keys of the two factory
// tables. Nothing in production asks for them, so they live here rather than on
// the package surface.

// entityTypes names every entity type the factory builds, sorted.
func entityTypes() []string {
	types := make([]string, 0, len(entityDataGetters))
	for entityType := range entityDataGetters {
		types = append(types, entityType)
	}
	slices.Sort(types)
	return types
}

// matcherTypes names every matcher type the factory builds, sorted.
func matcherTypes() []string {
	types := make([]string, 0, len(matcherPredicates))
	for matcherType := range matcherPredicates {
		types = append(types, matcherType)
	}
	slices.Sort(types)
	return types
}
