package matching

import (
	"fmt"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/matching/predicates"
)

// matcherPredicates is the whole set of matcher types. A table rather than a
// switch, so matcherTypes names exactly what the factory builds: a type added
// here is a type the tests iterate.
var matcherPredicates = map[string]func(map[string][]string) (MatcherPredicate, error){
	"empty": func(map[string][]string) (MatcherPredicate, error) {
		return predicates.NewEmptyPredicate()
	},
	"exist": func(map[string][]string) (MatcherPredicate, error) {
		return predicates.NewExistPredicate()
	},
	"equal": func(parameters map[string][]string) (MatcherPredicate, error) {
		return predicates.NewEqualPredicate(parameters)
	},
	"contain": func(parameters map[string][]string) (MatcherPredicate, error) {
		return predicates.NewContainPredicate(parameters)
	},
	"match": func(parameters map[string][]string) (MatcherPredicate, error) {
		return predicates.NewMatchPredicate(parameters)
	},
	"start_with": func(parameters map[string][]string) (MatcherPredicate, error) {
		return predicates.NewStartWithPredicate(parameters)
	},
	"end_with": func(parameters map[string][]string) (MatcherPredicate, error) {
		return predicates.NewEndWithPredicate(parameters)
	},
	"match_json_schema": func(parameters map[string][]string) (MatcherPredicate, error) {
		return predicates.NewMatchJsonSchemaPredicate(parameters)
	},
	"match_json": func(parameters map[string][]string) (MatcherPredicate, error) {
		return predicates.NewMatchJsonPredicate(parameters)
	},
}

func GetMatcherPredicate(matcherType string, parameters map[string][]string) (MatcherPredicate, error) {
	build, ok := matcherPredicates[matcherType]
	if !ok {
		return nil, fmt.Errorf("unknown predicate type: %v", matcherType)
	}
	return build(parameters)
}
