package predicates

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestNewMatchJsonSchemaPredicate_ShouldReturnErrorWhenSchemaIsNotInParametersMap(t *testing.T) {
	_, err := NewMatchJsonSchemaPredicate(map[string][]string{"foo": {"bar"}})
	if assert.Error(t, err) {
		assert.Contains(t, err.Error(), "schema")
	}
}

func TestNewMatchJsonSchemaPredicate_ShouldReturnErrorWhenSchemaIsNotValid(t *testing.T) {
	_, err := NewMatchJsonSchemaPredicate(map[string][]string{"schema": {`{"type": "}`}})
	assert.Error(t, err)
}

func TestNewMatchJsonSchemaPredicate_ShouldReturnPredicateObjectWhenSchemaIsInParametersMap(t *testing.T) {
	schema := `{"type": "object", "properties": {"message": {"type": "string"}}}`
	predicate, err := NewMatchJsonSchemaPredicate(map[string][]string{"schema": {schema}})
	assert.Nil(t, err)
	assert.NotNil(t, predicate)
}

func TestNewMatchJsonSchemaPredicate_ShouldSetPathInPredicateObjectFromParametersMap(t *testing.T) {
	schema := `{"type": "object", "properties": {"message": {"type": "string"}}}`
	predicate, err := NewMatchJsonSchemaPredicate(map[string][]string{"schema": {schema}, "path": {"$.foo"}})
	assert.Nil(t, err)
	assert.NotNil(t, predicate)
	assert.NotNil(t, predicate.Path)
	assert.Equal(t, "$.foo", *predicate.Path)
}

func TestMatchJsonSchemaTest_ShouldReturnNilWhenDataMatchesSchema(t *testing.T) {
	schema := `{"type": "object", "properties": {"message": {"type": "string"}}, "required": ["message"]}`
	predicate, err := NewMatchJsonSchemaPredicate(map[string][]string{"schema": {schema}})
	assert.Nil(t, err)
	data := []byte(`{"message": "Hello world!"}`)
	assert.Nil(t, predicate.Test(&data))
}

func TestMatchJsonSchemaTest_ShouldReturnNilWhenDataInPathMatchesSchema(t *testing.T) {
	schema := `{"type": "object", "properties": {"message": {"type": "string"}}, "required": ["message"]}`
	predicate, err := NewMatchJsonSchemaPredicate(map[string][]string{"schema": {schema}, "path": {"$.payload"}})
	assert.Nil(t, err)
	data := []byte(`{"payload": {"message": "Hello world!"}}`)
	assert.Nil(t, predicate.Test(&data))
}

func TestMatchJsonSchemaTest_ShouldReturnErrorWhenDataDoesNotMatchSchema(t *testing.T) {
	schema := `{"type": "object", "properties": {"message": {"type": "string"}}, "required": ["message"]}`
	predicate, err := NewMatchJsonSchemaPredicate(map[string][]string{"schema": {schema}})
	assert.Nil(t, err)
	data := []byte(`{"text": "Hello world!"}`)
	assert.Error(t, predicate.Test(&data))
}
