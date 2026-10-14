package predicates

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestExistTest_ShouldReturnNilWhenDataIsNotNil(t *testing.T) {
	predicate, err := NewExistPredicate()
	assert.Nil(t, err)
	err = predicate.Test(&[]byte{})
	assert.Nil(t, err)
}

func TestExistTest_ShouldReturnErrorWhenDataIsNil(t *testing.T) {
	predicate, err := NewExistPredicate()
	assert.Nil(t, err)
	err = predicate.Test(nil)
	assert.Error(t, err)
}
