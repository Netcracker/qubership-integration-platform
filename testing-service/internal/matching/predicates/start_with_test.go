package predicates

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestNewStartWithPredicate_ShouldReturnErrorWhenValueIsNotInParametersMap(t *testing.T) {
	_, err := NewStartWithPredicate(map[string][]string{"foo": {"bar"}})
	if assert.Error(t, err) {
		assert.Contains(t, err.Error(), "value")
	}
}

func TestNewStartWithPredicate_ShouldReturnPredicateObjectWhenValueIsInParametersMap(t *testing.T) {
	predicate, err := NewStartWithPredicate(map[string][]string{"value": {"foo"}})
	assert.Nil(t, err)
	if assert.NotNil(t, predicate) {
		assert.Equal(t, predicate.Value, "foo")
	}
}

func TestStartWithTest_ShouldReturnNilWhenDataStartsWithValue(t *testing.T) {
	predicate, err := NewStartWithPredicate(map[string][]string{"value": {"foo"}})
	assert.Nil(t, err)
	data := []byte("foobar")
	assert.Nil(t, predicate.Test(&data))
}

func TestStartWithTest_ShouldReturnErrorWhenDataDoesNotStartWithValue(t *testing.T) {
	predicate, err := NewStartWithPredicate(map[string][]string{"value": {"bar"}})
	assert.Nil(t, err)
	data := []byte("foobar")
	assert.Error(t, predicate.Test(&data))
}
