package predicates

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestNewMatchJsonPredicate_ShouldReturnErrorWhenSampleIsNotInParametersMap(t *testing.T) {
	_, err := NewMatchJsonPredicate(map[string][]string{"foo": {"bar"}})
	if assert.Error(t, err) {
		assert.Contains(t, err.Error(), "sample")
	}
}

func TestNewMatchJsonPredicate_ShouldReturnErrorWhenSampleIsNotValidJSON(t *testing.T) {
	_, err := NewMatchJsonPredicate(map[string][]string{"sample": {`{"type": "}`}})
	assert.Error(t, err)
}

func TestNewMatchJsonPredicate_ShouldReturnPredicateWithSampleAndPath(t *testing.T) {
	sample := `{"message": "Hello world!"}`
	predicate, err := NewMatchJsonPredicate(map[string][]string{"sample": {sample}, "path": {"$.foo"}})
	assert.Nil(t, err)
	assert.Equal(t, predicate.Sample, map[string]interface{}{"message": "Hello world!"})
	assert.Equal(t, *predicate.Path, "$.foo")
}

func TestNewMatchJsonPredicate_ShouldReturnPredicateWithSample(t *testing.T) {
	sample := `{"message": "Hello world!"}`
	predicate, err := NewMatchJsonPredicate(map[string][]string{"sample": {sample}})
	assert.Nil(t, err)
	assert.Equal(t, predicate.Sample, map[string]interface{}{"message": "Hello world!"})
	assert.Nil(t, predicate.Path)
}

func TestMatchJsonTest_ShouldReturnNilWhenDocumentAtPathMatchesSample(t *testing.T) {
	sample := `{"foo": "bar"}`
	path := "$.message"
	predicate, err := NewMatchJsonPredicate(map[string][]string{"sample": {sample}, "path": {path}})
	assert.Nil(t, err)
	data := []byte(`{"message": {"foo": "bar"}}`)
	assert.Nil(t, predicate.Test(&data))
}

func TestMatchJsonTest_ShouldReturnNilWhenWholeDocumentMatchesSampleAndPathIsNotSet(t *testing.T) {
	sample := `{"message": "Hello world!"}`
	predicate, err := NewMatchJsonPredicate(map[string][]string{"sample": {sample}})
	assert.Nil(t, err)
	data := []byte(sample)
	assert.Nil(t, predicate.Test(&data))
}

func TestMatchJson_ShouldReturnErrorWhenPathIsNotValidJSONPath(t *testing.T) {
	sample := `{"message": "Hello world!"}`
	predicate, err := NewMatchJsonPredicate(map[string][]string{"sample": {sample}, "path": {"foo["}})
	assert.Nil(t, err)
	data := []byte(sample)
	assert.Error(t, predicate.Test(&data))
}

func TestMatchJsonTest_ShouldReturnErrorWhenDataDoesNotMatchSample(t *testing.T) {
	sample := `{"message": "Hello world!"}`
	predicate, err := NewMatchJsonPredicate(map[string][]string{"sample": {sample}})
	assert.Nil(t, err)
	data := []byte(`{"message": "Hello world"}`)
	assert.Error(t, predicate.Test(&data))
}
