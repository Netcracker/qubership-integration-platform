package predicates

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func assertFindSingleValueError(t *testing.T, parameters map[string][]string, name string) {
	value, err := findSingleValue(parameters, name)
	assert.Nil(t, value)
	if assert.Error(t, err) {
		assert.Contains(t, err.Error(), name)
	}
}

func TestFindSingleValue_ShouldReturnErrorWhenKeyIsNotInMap(t *testing.T) {
	assertFindSingleValueError(t, map[string][]string{"foo": {"bar"}}, "baz")
}

func TestFindSingleValue_ShouldReturnErrorWhenValueListIsEmpty(t *testing.T) {
	assertFindSingleValueError(t, map[string][]string{"foo": {}}, "foo")
}

func TestFindSingleValue_ShouldReturnErrorWhenValueListContainsMoreThanOneValue(t *testing.T) {
	assertFindSingleValueError(t, map[string][]string{"foo": {"bar", "baz"}}, "foo")
}

func TestFindSingleValue_ShouldReturnValueFromMap(t *testing.T) {
	value, err := findSingleValue(map[string][]string{"foo": {"bar"}}, "foo")
	assert.Nil(t, err)
	assert.NotNil(t, value)
	assert.Equal(t, *value, "bar")
}

func TestGetJsonPath_ShouldReturnNilWhenPathIsNotInMap(t *testing.T) {
	value, err := getJsonPath(map[string][]string{"foo": {"bar"}})
	assert.Nil(t, err)
	assert.Nil(t, value)
}

func TestGetJsonPath_ShouldReturnTrimmedPathValueWhenPathInMap(t *testing.T) {
	value, err := getJsonPath(map[string][]string{"path": {"   foo\t "}})
	assert.Nil(t, err)
	assert.NotNil(t, value)
	assert.Equal(t, "foo", *value)
}

func TestGetJsonNode_ShouldReturnRootNodeIfPathIsNotSet(t *testing.T) {
	data := []byte(`{"a": "b"}`)
	document, err := getJsonNode(&data, nil)
	assert.Nil(t, err)
	assert.NotNil(t, document)
	assert.Equal(t, map[string]any{"a": "b"}, document)
}

func TestGetJsonNode_ShouldReturnNodeInPath(t *testing.T) {
	data := []byte(`{"foo": {"bar": [{"a": "b"}]}}`)
	path := "$.foo.bar[0]"
	document, err := getJsonNode(&data, &path)
	assert.Nil(t, err)
	assert.NotNil(t, document)
	assert.Equal(t, map[string]any{"a": "b"}, document)
}

func TestGetJsonNode_ShouldReturnErrorWhenDataIsNotAValidJSON(t *testing.T) {
	data := []byte("{")
	document, err := getJsonNode(&data, nil)
	assert.Nil(t, document)
	assert.Error(t, err)
}

func TestGetJsonNode_ShouldReturnErrorWhenPathIsMalformed(t *testing.T) {
	data := []byte(`{"a": "b"}`)
	path := "$.a["
	document, err := getJsonNode(&data, &path)
	assert.Nil(t, document)
	assert.Error(t, err)
}

func TestGetJsonNode_ShouldReturnErrorWhenPathDoesNotExist(t *testing.T) {
	data := []byte(`{"a": "b"}`)
	path := "$.a.b.c.d"
	document, err := getJsonNode(&data, &path)
	assert.Nil(t, document)
	assert.Error(t, err)
}
