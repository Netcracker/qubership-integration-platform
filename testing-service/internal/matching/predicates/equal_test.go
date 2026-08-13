package predicates

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestNewEqualPredicate_ShouldReturnErrorWhenValueIsNotInParametersMap(t *testing.T) {
	_, err := NewEqualPredicate(map[string][]string{"foo": {"bar"}})
	if assert.Error(t, err) {
		assert.Contains(t, err.Error(), "value")
	}
}

func TestNewEqualPredicate_ShouldReturnPredicateObjectWhenValueIsInParametersMap(t *testing.T) {
	predicate, err := NewEqualPredicate(map[string][]string{"value": {"foo"}})
	assert.Nil(t, err)
	if assert.NotNil(t, predicate) {
		assert.Equal(t, predicate.Value, "foo")
	}
}

func TestEqualTest_ShouldReturnNilWhenValuesAreEqual(t *testing.T) {
	predicate, err := NewEqualPredicate(map[string][]string{"value": {"foo"}})
	assert.Nil(t, err)
	data := []byte("foo")
	assert.Nil(t, predicate.Test(&data))
}

func TestEqualTest_ShouldReturnErrorWhenValuesAreNotEqual(t *testing.T) {
	predicate, err := NewEqualPredicate(map[string][]string{"value": {"foo"}})
	assert.Nil(t, err)
	data := []byte("bar")
	assert.Error(t, predicate.Test(&data))
}
