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
	repositories := dao.Repositories{Matchers: matchers, MatcherParameters: parameters}
	owner := uuid.New()

	created, err := createMatchers(context.Background(), repositories, owner, []*dao.Matcher{
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
	repositories := dao.Repositories{Matchers: matchers, MatcherParameters: parameters}

	created, err := createMatchers(context.Background(), repositories, uuid.New(), []*dao.Matcher{{
		Name:       "status is 200",
		Parameters: []*dao.MatcherParameter{{Name: "value", Value: "200"}, nil},
	}})

	require.NoError(t, err)
	require.Len(t, parameters.batches, 1)
	require.Len(t, parameters.batches[0], 1)
	assert.Equal(t, (*created)[0].ID, parameters.batches[0][0].MatcherID)
}

// The schema of a match_json_schema matcher is caller input, and the compiler
// would read whatever a reference in it names. Both save paths refuse it, and the
// refusal is a 400 that describes nothing of the filesystem it did not read.
func TestValidateMatcherRefusesASchemaReferringToAFile(t *testing.T) {
	matcher := &dao.Matcher{
		Name:       "leaky",
		Type:       "match_json_schema",
		EntityType: "body",
		Parameters: []*dao.MatcherParameter{{Name: "schema", Value: `{"$ref": "file:///etc/passwd"}`}},
	}

	err := validateMatcher("response validation rule", matcher)

	require.ErrorIs(t, err, ErrInvalidRequest)
	assert.Contains(t, err.Error(), "outside the schema itself")
	assert.NotContains(t, err.Error(), "/etc/passwd")
	assert.NotContains(t, err.Error(), "invalid character")
}

func TestMatchersServiceReportsAFailingInsert(t *testing.T) {
	failure := errors.New("constraint violated")
	repositories := dao.Repositories{
		Matchers:          &fakeMatchersRepository{insertErr: failure},
		MatcherParameters: &fakeMatcherParametersRepository{},
	}

	created, err := createMatchers(context.Background(), repositories, uuid.New(), []*dao.Matcher{{Name: "first"}})

	require.ErrorIs(t, err, failure)
	assert.Nil(t, created)
}
