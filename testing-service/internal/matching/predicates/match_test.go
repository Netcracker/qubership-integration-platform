package predicates

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestNewMatchPredicate_ShouldReturnErrorWhenPatternIsNotInParametersMap(t *testing.T) {
	_, err := NewMatchPredicate(map[string][]string{"foo": {"bar"}})
	if assert.Error(t, err) {
		assert.Contains(t, err.Error(), "pattern")
	}
}

func TestNewMatchPredicate_ShouldReturnPredicateObjectWhenPatternIsInParametersMap(t *testing.T) {
	predicate, err := NewMatchPredicate(map[string][]string{"pattern": {"foo"}})
	assert.Nil(t, err)
	if assert.NotNil(t, predicate) {
		assert.Equal(t, predicate.Pattern.String(), "foo")
	}
}

func TestMatchTest_ShouldReturnNilWhenDataMatchesPattern(t *testing.T) {
	predicate, err := NewMatchPredicate(map[string][]string{"pattern": {"^\\w[\\w\\d_]*$"}})
	assert.Nil(t, err)
	data := []byte("fooBar_baz0")
	assert.Nil(t, predicate.Test(&data))
}

func TestMatchTest_ShouldReturnErrorWhenDataDoesNotMatchPattern(t *testing.T) {
	predicate, err := NewMatchPredicate(map[string][]string{"pattern": {"^\\w[\\w\\d_]*$"}})
	assert.Nil(t, err)
	data := []byte("foo-bar")
	assert.Error(t, predicate.Test(&data))
}
