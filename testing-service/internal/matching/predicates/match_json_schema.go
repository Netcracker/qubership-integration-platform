package predicates

import (
	"fmt"
	"strings"

	"github.com/santhosh-tekuri/jsonschema/v6"
)

type MatchJsonSchemaPredicate struct {
	Path   *string
	Schema *jsonschema.Schema
}

func NewMatchJsonSchemaPredicate(parameters map[string][]string) (*MatchJsonSchemaPredicate, error) {
	path, err := getJsonPath(parameters)
	if err != nil {
		return nil, err
	}
	schema, err := getJsonSchema(parameters)
	if err != nil {
		return nil, err
	}
	return &MatchJsonSchemaPredicate{Path: path, Schema: schema}, nil
}

func (p *MatchJsonSchemaPredicate) Test(data *[]byte) error {
	document, err := getJsonNode(data, p.Path)
	if err != nil {
		return err
	}
	return p.Schema.Validate(document)
}

func getJsonSchema(parameters map[string][]string) (*jsonschema.Schema, error) {
	schemaText, err := findSingleValue(parameters, "schema")
	if err != nil {
		return nil, err
	}
	schemaObject, err := jsonschema.UnmarshalJSON(strings.NewReader(*schemaText))
	if err != nil {
		return nil, fmt.Errorf("failed to unmarshal JSON Schema: %w", err)
	}
	compiler := jsonschema.NewCompiler()
	if err = compiler.AddResource("schema.json", schemaObject); err != nil {
		return nil, fmt.Errorf("failed to add resource to JSON Schema compiler: %w", err)
	}
	schema, err := compiler.Compile("schema.json")
	if err != nil {
		return nil, fmt.Errorf("failed to compile schema: %w", err)
	}
	return schema, nil
}
