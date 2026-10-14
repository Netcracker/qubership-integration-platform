package services

import (
	"context"
	"fmt"
	"log/slog"
	"sort"
	"strings"

	"github.com/google/uuid"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/matching"
)

// violation is one stored value the save-time rules refuse. The key identifies
// the offending value alone, so the same value can be recognized in the row an
// update is about to replace; the message is what the caller is told, and what
// the log names when an update keeps the value.
type violation struct {
	key     string
	message string
}

// refuse answers a create carrying any violation with a 400. The first one is
// enough: the caller fixes what it names and sends the entity again.
func refuse(violations []violation) error {
	if len(violations) == 0 {
		return nil
	}
	return invalidRequest("%s", violations[0].message)
}

// tolerateStoredViolations refuses every violation the stored row does not
// already carry, and logs the ones it does.
//
// A row saved before these rules existed still reads back — an unbuildable
// matcher is skipped, an unwritable header is left out of the response — but it
// used to become uneditable, because an update validates the whole entity. So an
// update may leave such a value alone. It may not introduce one, and replacing a
// bad value with a different bad value counts as introducing it: leniency is for
// what is already in the database.
func tolerateStoredViolations(
	ctx context.Context,
	logger *slog.Logger,
	entity string,
	id uuid.UUID,
	incoming []violation,
	stored []violation,
) error {
	if len(incoming) == 0 {
		return nil
	}
	storedKeys := make(map[string]struct{}, len(stored))
	for _, kept := range stored {
		storedKeys[kept.key] = struct{}{}
	}
	for _, found := range incoming {
		if _, ok := storedKeys[found.key]; !ok {
			return invalidRequest("%s", found.message)
		}
	}
	for _, found := range incoming {
		logger.WarnContext(ctx, "Keeping a stored value that the save-time rules refuse",
			"entity", entity, "entityId", id, "violation", found.message)
	}
	return nil
}

// matcherViolations lists what the rules refuse in a set of matchers, naming the
// kind they are — a request matcher of an endpoint mock, or a response
// validation rule of a test case. Both kinds are stored the same way and are
// checked the same way.
func matcherViolations(kind string, matchers []*dao.Matcher) []violation {
	var violations []violation
	for _, matcher := range matchers {
		violations = appendMatcherViolations(violations, kind, matcher)
	}
	return violations
}

// appendMatcherViolations builds the matcher the way the run paths do, so a bad
// regular expression, an unparseable JSON sample or a missing parameter is
// refused at save time. Neither run path fails on such a matcher — Call skips the
// mock carrying it, and the executor records it as a validation error — and a
// matcher that never holds is a lot harder to read off the running service than a
// 400 on the request that stored it. Disabled matchers are checked too: they are
// one toggle away from being evaluated.
func appendMatcherViolations(violations []violation, kind string, matcher *dao.Matcher) []violation {
	if matcher == nil {
		return violations
	}
	var entityName string
	if matcher.EntityName != nil {
		entityName = *matcher.EntityName
	}
	// The keys name the offending value and not the matcher carrying it, so a
	// caller updating a legacy entity may rename or describe the matcher; only
	// the value the rule refuses has to stay as it was stored.
	if _, err := matching.GetEntityDataGetter(matcher.EntityType, entityName); err != nil {
		violations = append(violations, violation{
			key:     fmt.Sprintf("matcher entity %s %q", matcher.EntityType, entityName),
			message: fmt.Sprintf("%s %q: %v", kind, matcher.Name, err),
		})
	}
	if _, err := matching.GetMatcherPredicate(matcher.Type, buildParametersMap(matcher.Parameters)); err != nil {
		violations = append(violations, violation{
			key:     fmt.Sprintf("matcher predicate %s %s", matcher.Type, parametersKey(matcher.Parameters)),
			message: fmt.Sprintf("%s %q: %v", kind, matcher.Name, err),
		})
	}
	return violations
}

// parametersKey renders the parameters of a matcher in a stable order, so the
// same parameters produce the same key whichever order they arrive in.
func parametersKey(parameters []*dao.MatcherParameter) string {
	pairs := make([]string, 0, len(parameters))
	for _, parameter := range parameters {
		if parameter == nil {
			continue
		}
		pairs = append(pairs, fmt.Sprintf("%q=%q", parameter.Name, parameter.Value))
	}
	sort.Strings(pairs)
	return strings.Join(pairs, ",")
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
