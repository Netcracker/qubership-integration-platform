package predicates

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestNewContainPredicate_ShouldReturnErrorWhenValueIsNotInParametersMap(t *testing.T) {
	_, err := NewContainPredicate(map[string][]string{"foo": {"bar"}})
	if assert.Error(t, err) {
		assert.Contains(t, err.Error(), "value")
	}
}

func TestNewContainPredicate_ShouldReturnPredicateObjectWhenValueIsInParametersMap(t *testing.T) {
	predicate, err := NewContainPredicate(map[string][]string{"value": {"foo"}})
	assert.Nil(t, err)
	if assert.NotNil(t, predicate) {
		assert.Equal(t, predicate.Value, "foo")
	}
}

func TestContainTest_ShouldReturnNilWhenDataContainsValue(t *testing.T) {
	predicate, err := NewContainPredicate(map[string][]string{"value": {"foo"}})
	assert.Nil(t, err)
	data := []byte("bar foo baz")
	assert.Nil(t, predicate.Test(&data))
}

func TestContainTest_ShouldReturnErrorWhenDataDoesNotContainValue(t *testing.T) {
	predicate, err := NewContainPredicate(map[string][]string{"value": {"foo"}})
	assert.Nil(t, err)
	data := []byte("bar")
	assert.Error(t, predicate.Test(&data))
}
