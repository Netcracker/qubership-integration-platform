package predicates

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestEmptyTest_ShouldReturnNilWhenDataIsNil(t *testing.T) {
	predicate, err := NewEmptyPredicate()
	assert.Nil(t, err)
	err = predicate.Test(nil)
	assert.Nil(t, err)
}

func TestEmptyTest_ShouldReturnNilWhenDataIsEmptyList(t *testing.T) {
	predicate, err := NewEmptyPredicate()
	assert.Nil(t, err)
	data := make([]byte, 0)
	err = predicate.Test(&data)
	assert.Nil(t, err)
}

func TestEmptyTest_ShouldReturnErrorWhenDataIsNotEmpty(t *testing.T) {
	predicate, err := NewEmptyPredicate()
	assert.Nil(t, err)
	err = predicate.Test(&[]byte{0})
	assert.Error(t, err)
}
