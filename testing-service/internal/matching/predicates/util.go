// Package predicates holds the nine matcher predicates the matching engine
// selects between.
package predicates

import (
	"bytes"
	"fmt"
	"strings"

	"github.com/PaesslerAG/jsonpath"
	"github.com/santhosh-tekuri/jsonschema/v6"
)

func findSingleValue(parameters map[string][]string, name string) (*string, error) {
	values, ok := parameters[name]
	if !ok {
		return nil, fmt.Errorf("matcher parameter '%v' not found", name)
	}
	if len(values) != 1 {
		return nil, fmt.Errorf("wrong matcher parameter '%v' count: %v", name, len(values))
	}
	return &values[0], nil
}

func getJsonPath(parameters map[string][]string) (*string, error) {
	values, ok := parameters["path"]
	if !ok {
		return nil, nil
	}
	if len(values) != 1 {
		return nil, fmt.Errorf("wrong matcher parameter '%v' count: %v", "path", len(values))
	}
	path := strings.Trim(values[0], " \t")
	return &path, nil
}

func getJsonNode(data *[]byte, path *string) (any, error) {
	var buffer bytes.Buffer
	if data != nil {
		buffer = *bytes.NewBuffer(*data)
	}
	document, err := jsonschema.UnmarshalJSON(&buffer)
	if err != nil {
		return nil, fmt.Errorf("failed to unmarshal JSON document: %w", err)
	}

	if path != nil && len(*path) > 0 {
		document, err = jsonpath.Get(*path, document)
		if err != nil {
			return nil, fmt.Errorf("failed to get document node: %w", err)
		}
	}
	return document, nil
}
