package predicates

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestNewEndWithPredicate_ShouldReturnErrorWhenValueIsNotInParametersMap(t *testing.T) {
	_, err := NewEndWithPredicate(map[string][]string{"foo": {"bar"}})
	if assert.Error(t, err) {
		assert.Contains(t, err.Error(), "value")
	}
}

func TestNewEndWithPredicate_ShouldReturnPredicateObjectWhenValueIsInParametersMap(t *testing.T) {
	predicate, err := NewEndWithPredicate(map[string][]string{"value": {"foo"}})
	assert.Nil(t, err)
	if assert.NotNil(t, predicate) {
		assert.Equal(t, predicate.Value, "foo")
	}
}

func TestEndWithTest_ShouldReturnNilWhenDataEndsWithValue(t *testing.T) {
	predicate, err := NewEndWithPredicate(map[string][]string{"value": {"bar"}})
	assert.Nil(t, err)
	data := []byte("foobar")
	assert.Nil(t, predicate.Test(&data))
}

func TestEndWithTest_ShouldReturnErrorWhenDataDoesNotEndWithValue(t *testing.T) {
	predicate, err := NewEndWithPredicate(map[string][]string{"value": {"bar"}})
	assert.Nil(t, err)
	data := []byte("bar baz")
	assert.Error(t, predicate.Test(&data))
}
