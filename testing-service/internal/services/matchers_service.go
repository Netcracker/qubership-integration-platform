package services

import (
	"context"

	"github.com/google/uuid"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
)

// MatchersService stores the matchers of a test case or an endpoint mock.
type MatchersService interface {
	// Create stores matchers along with their parameters under the given owner.
	// It runs inside the caller's transaction and starts none of its own.
	Create(ctx context.Context, ownerID uuid.UUID, matchers []*dao.Matcher) (*[]*dao.Matcher, error)
}

type matchersService struct {
	repositories Repositories
}

// NewMatchersService returns a MatchersService over the given repositories.
func NewMatchersService(repositories Repositories) MatchersService {
	return &matchersService{repositories: repositories}
}

func (s *matchersService) Create(ctx context.Context, ownerID uuid.UUID, matchers []*dao.Matcher) (*[]*dao.Matcher, error) {
	var result []*dao.Matcher
	for _, matcher := range matchers {
		if matcher == nil {
			continue
		}
		matcher.OwnerID = ownerID
		createdMatcher, err := s.repositories.Matchers.Insert(ctx, matcher)
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
		if err = s.repositories.MatcherParameters.BulkInsert(ctx, &parameters); err != nil {
			return nil, err
		}
		createdMatcher.Parameters = matcher.Parameters
	}
	return &result, nil
}
