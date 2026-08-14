package services

import (
	"context"

	"github.com/google/uuid"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/matching"
)

// validateMatchers refuses a matcher that cannot be built, naming the kind it is
// — a request matcher of an endpoint mock, or a response validation rule of a
// test case. Both kinds are stored the same way and are checked the same way.
func validateMatchers(kind string, matchers []*dao.Matcher) error {
	for _, matcher := range matchers {
		if err := validateMatcher(kind, matcher); err != nil {
			return err
		}
	}
	return nil
}

// validateMatcher builds the matcher the way the run paths do, so a bad regular
// expression, an unparseable JSON sample or a missing parameter is refused at
// save time. Neither run path fails on such a matcher — Call skips the mock
// carrying it, and the executor records it as a validation error — and a matcher
// that never holds is a lot harder to read off the running service than a 400 on
// the request that stored it. Disabled matchers are checked too: they are one
// toggle away from being evaluated.
func validateMatcher(kind string, matcher *dao.Matcher) error {
	if matcher == nil {
		return nil
	}
	var entityName string
	if matcher.EntityName != nil {
		entityName = *matcher.EntityName
	}
	if _, err := matching.GetEntityDataGetter(matcher.EntityType, entityName); err != nil {
		return invalidRequest("%s %q: %v", kind, matcher.Name, err)
	}
	if _, err := matching.GetMatcherPredicate(matcher.Type, buildParametersMap(matcher.Parameters)); err != nil {
		return invalidRequest("%s %q: %v", kind, matcher.Name, err)
	}
	return nil
}

// createMatchers stores matchers along with their parameters under the given
// owner. It runs inside the caller's transaction and starts none of its own.
func createMatchers(
	ctx context.Context,
	repositories dao.Repositories,
	ownerID uuid.UUID,
	matchers []*dao.Matcher,
) (*[]*dao.Matcher, error) {
	var result []*dao.Matcher
	for _, matcher := range matchers {
		if matcher == nil {
			continue
		}
		matcher.OwnerID = ownerID
		createdMatcher, err := repositories.Matchers.Insert(ctx, matcher)
		if err != nil {
			return nil, err
		}
		result = append(result, createdMatcher)

		var parameters []dao.MatcherParameter
		for _, parameter := range matcher.Parameters {
			if parameter == nil {
				continue
			}
			parameter.MatcherID = createdMatcher.ID
			parameters = append(parameters, *parameter)
		}
		if err = repositories.MatcherParameters.BulkInsert(ctx, &parameters); err != nil {
			return nil, err
		}
		createdMatcher.Parameters = matcher.Parameters
	}
	return &result, nil
}
