package matching

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestGetMatcherPredicateCoversEveryMatcherType(t *testing.T) {
	parameters := map[string][]string{
		"value":   {"foo"},
		"pattern": {"^foo$"},
		"sample":  {`{"a":1}`},
		"schema":  {`{"type":"object"}`},
	}

	for _, matcherType := range []string{
		"empty", "exist", "equal", "contain", "match",
		"start_with", "end_with", "match_json_schema", "match_json",
	} {
		t.Run(matcherType, func(t *testing.T) {
			predicate, err := GetMatcherPredicate(matcherType, parameters)

			require.NoError(t, err)
			assert.NotNil(t, predicate)
		})
	}
}

func TestGetMatcherPredicateRejectsAnUnknownMatcherType(t *testing.T) {
	predicate, err := GetMatcherPredicate("greater_than", nil)

	require.Error(t, err)
	assert.Nil(t, predicate)
	assert.Contains(t, err.Error(), "greater_than")
}

func TestGetMatcherPredicatePropagatesAParameterError(t *testing.T) {
	predicate, err := GetMatcherPredicate("equal", map[string][]string{})

	require.Error(t, err)
	assert.Nil(t, predicate)
	assert.Contains(t, err.Error(), "value")
}
