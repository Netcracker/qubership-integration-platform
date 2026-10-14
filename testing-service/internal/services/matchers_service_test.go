package services

import (
	"context"
	"errors"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/matching"
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

	err := refuse(matcherViolations("response validation rule", []*dao.Matcher{matcher}))

	require.ErrorIs(t, err, ErrInvalidRequest)
	assert.Contains(t, err.Error(), "outside the schema itself")
	assert.NotContains(t, err.Error(), "/etc/passwd")
	assert.NotContains(t, err.Error(), "invalid character")
}

// The key of a violation names the offending value, so an update that reorders
// the parameters of a legacy matcher is still updating the same one.
func TestViolationKeysDoNotDependOnTheParameterOrder(t *testing.T) {
	matcher := func(parameters ...*dao.MatcherParameter) []*dao.Matcher {
		return []*dao.Matcher{{
			Name: "m", Type: "match", EntityType: matching.EntityTypeBody, Parameters: parameters,
		}}
	}
	first := &dao.MatcherParameter{Name: "pattern", Value: "("}
	second := &dao.MatcherParameter{Name: "note", Value: "legacy"}

	one := matcherViolations("response validation rule", matcher(first, second))
	other := matcherViolations("response validation rule", matcher(second, first))

	require.Len(t, one, 1)
	require.Len(t, other, 1)
	assert.Equal(t, one[0].key, other[0].key)
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
