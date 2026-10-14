package predicates

import (
	"errors"
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
	loader := &refusingLoader{}
	compiler.UseLoader(loader)
	if err = compiler.AddResource("schema.json", schemaObject); err != nil {
		return nil, fmt.Errorf("failed to add resource to JSON Schema compiler: %w", err)
	}
	schema, err := compiler.Compile("schema.json")
	if err != nil {
		// The library reports a refused reference with the URL it resolved, and a
		// relative one resolves against the working directory of the process. The
		// caller gets the reason without that.
		if loader.refused {
			return nil, fmt.Errorf("failed to compile schema: %w", errExternalReference)
		}
		return nil, fmt.Errorf("failed to compile schema: %w", err)
	}
	return schema, nil
}

// errExternalReference is what a reference outside the schema itself resolves to.
var errExternalReference = errors.New("a schema reference outside the schema itself is not supported")

// refusingLoader resolves nothing, and records that it was asked to. The compiler
// loads its own metaschemas before it consults a loader, so only a reference the
// schema text carries reaches this, and the schema text is whatever the caller
// stored. The library defaults the loader to FileLoader, which turns
// `{"$ref": "file:///..."}` — or a `$schema` naming a path — into a read of the
// local filesystem, and answers whether the path exists, whether it is readable
// and what JSON it holds. That answer travels back as the 400 that refuses the
// matcher. One compile owns one loader, so the flag is written from the compiling
// goroutine alone.
type refusingLoader struct {
	refused bool
}

func (l *refusingLoader) Load(string) (any, error) {
	l.refused = true
	return nil, errExternalReference
}
