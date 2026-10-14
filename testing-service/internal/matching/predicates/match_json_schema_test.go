package predicates

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
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

// The schema is caller input, and the compiler reads whatever a reference in it
// names. Left to the library default, a stored matcher answers whether a path
// exists, whether it is readable and what JSON it holds — mounted secrets
// included — through the 400 that refuses it. Every one of these has to be
// refused with the same message, or the message is the answer.
func TestASchemaReferringToTheFilesystemIsRefusedWithoutDescribingIt(t *testing.T) {
	directory := t.TempDir()
	readable := filepath.Join(directory, "readable.json")
	require.NoError(t, os.WriteFile(readable, []byte(`{"type": "object"}`), 0o600))
	notJSON := filepath.Join(directory, "secret.txt")
	require.NoError(t, os.WriteFile(notJSON, []byte("hunter2\n"), 0o600))

	references := map[string]string{
		"readable JSON":              "file://" + readable,
		"a pointer into it":          "file://" + readable + "#/type",
		"a pointer that misses":      "file://" + readable + "#/absent",
		"a file that is not JSON":    "file://" + notJSON,
		"a path that does not exist": "file://" + filepath.Join(directory, "absent.json"),
		"a relative path":            "../../go.mod",
		"an address on the network":  "http://169.254.169.254/latest/meta-data",
	}
	for name, reference := range references {
		t.Run(name, func(t *testing.T) {
			_, err := NewMatchJsonSchemaPredicate(map[string][]string{
				"schema": {`{"$ref": "` + reference + `"}`},
			})

			require.ErrorIs(t, err, errExternalReference)
			assert.NotContains(t, err.Error(), directory, "the message names the path it resolved")
			assert.NotContains(t, err.Error(), "no such file", "the message tells the caller the path is absent")
			assert.NotContains(t, err.Error(), "invalid character", "the message describes what the file holds")
		})
	}
}

// A metaschema the library embeds is not a reference outside the schema, and the
// draft a caller declares still selects the vocabulary it compiles against.
func TestASchemaDeclaringADraftStillCompiles(t *testing.T) {
	schema := `{"$schema": "http://json-schema.org/draft-07/schema#", "type": "object", "required": ["message"]}`
	predicate, err := NewMatchJsonSchemaPredicate(map[string][]string{"schema": {schema}})
	require.NoError(t, err)

	matching := []byte(`{"message": "Hello world!"}`)
	assert.NoError(t, predicate.Test(&matching))
	missing := []byte(`{"text": "Hello world!"}`)
	assert.Error(t, predicate.Test(&missing))
}

// A reference inside the schema resolves without a loader, so refusing external
// ones leaves it working.
func TestASchemaReferringToItselfStillCompiles(t *testing.T) {
	schema := `{
		"$defs": {"message": {"type": "string"}},
		"type": "object",
		"properties": {"message": {"$ref": "#/$defs/message"}},
		"required": ["message"]
	}`
	predicate, err := NewMatchJsonSchemaPredicate(map[string][]string{"schema": {schema}})
	require.NoError(t, err)

	matching := []byte(`{"message": "Hello world!"}`)
	assert.NoError(t, predicate.Test(&matching))
	wrongType := []byte(`{"message": 42}`)
	assert.Error(t, predicate.Test(&wrongType))
}
