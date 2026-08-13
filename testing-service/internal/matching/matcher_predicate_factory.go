package matching

import (
	"fmt"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/matching/predicates"
)

func GetMatcherPredicate(matcherType string, parameters map[string][]string) (MatcherPredicate, error) {
	switch matcherType {
	case "empty":
		return predicates.NewEmptyPredicate()
	case "exist":
		return predicates.NewExistPredicate()
	case "equal":
		return predicates.NewEqualPredicate(parameters)
	case "contain":
		return predicates.NewContainPredicate(parameters)
	case "match":
		return predicates.NewMatchPredicate(parameters)
	case "start_with":
		return predicates.NewStartWithPredicate(parameters)
	case "end_with":
		return predicates.NewEndWithPredicate(parameters)
	case "match_json_schema":
		return predicates.NewMatchJsonSchemaPredicate(parameters)
	case "match_json":
		return predicates.NewMatchJsonPredicate(parameters)
	default:
		return nil, fmt.Errorf("unknown predicate type: %v", matcherType)
	}
}
