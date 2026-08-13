package predicates

import (
	"fmt"
	"strings"

	"github.com/santhosh-tekuri/jsonschema/v6"
	"github.com/wI2L/jsondiff"
)

type MatchJsonPredicate struct {
	Path   *string
	Sample any
}

func NewMatchJsonPredicate(parameters map[string][]string) (*MatchJsonPredicate, error) {
	path, err := getJsonPath(parameters)
	if err != nil {
		return nil, err
	}
	sampleText, err := findSingleValue(parameters, "sample")
	if err != nil {
		return nil, err
	}
	sample, err := jsonschema.UnmarshalJSON(strings.NewReader(*sampleText))
	if err != nil {
		return nil, fmt.Errorf("failed to unmarshal JSON sample: %w", err)
	}
	return &MatchJsonPredicate{Path: path, Sample: sample}, nil
}

func (p *MatchJsonPredicate) Test(data *[]byte) error {
	document, err := getJsonNode(data, p.Path)
	if err != nil {
		return err
	}

	patch, err := jsondiff.Compare(p.Sample, document)
	if err != nil {
		return fmt.Errorf("failed to compare document node with sample: %w", err)
	}

	if len(patch) > 0 {
		return fmt.Errorf("value does not match the sample, patch: %v", patch)
	}

	return nil
}
