package services

import (
	"context"
	"errors"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
)

func TestMatchersServiceStampsTheOwnerOnEveryMatcher(t *testing.T) {
	matchers := &fakeMatchersRepository{}
	parameters := &fakeMatcherParametersRepository{}
	service := NewMatchersService(Repositories{Matchers: matchers, MatcherParameters: parameters})
	owner := uuid.New()

	created, err := service.Create(context.Background(), owner, []*dao.Matcher{
		{Name: "first"},
		nil,
		{Name: "second"},
	})

	require.NoError(t, err)
	require.Len(t, *created, 2)
	for _, matcher := range matchers.inserted {
		assert.Equal(t, owner, matcher.OwnerID)
	}
}

func TestMatchersServiceLinksParametersToTheStoredMatcher(t *testing.T) {
	matchers := &fakeMatchersRepository{}
	parameters := &fakeMatcherParametersRepository{}
	service := NewMatchersService(Repositories{Matchers: matchers, MatcherParameters: parameters})

	created, err := service.Create(context.Background(), uuid.New(), []*dao.Matcher{{
		Name:       "status is 200",
		Parameters: []*dao.MatcherParameter{{Name: "value", Value: "200"}, nil},
	}})

	require.NoError(t, err)
	require.Len(t, parameters.batches, 1)
	require.Len(t, parameters.batches[0], 1)
	assert.Equal(t, (*created)[0].ID, parameters.batches[0][0].MatcherID)
}

func TestMatchersServiceReportsAFailingInsert(t *testing.T) {
	failure := errors.New("constraint violated")
	service := NewMatchersService(Repositories{
		Matchers:          &fakeMatchersRepository{insertErr: failure},
		MatcherParameters: &fakeMatcherParametersRepository{},
	})

	created, err := service.Create(context.Background(), uuid.New(), []*dao.Matcher{{Name: "first"}})

	require.ErrorIs(t, err, failure)
	assert.Nil(t, created)
}
