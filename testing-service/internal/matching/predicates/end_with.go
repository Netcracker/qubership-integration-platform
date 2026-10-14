package predicates

import (
	"fmt"
	"strings"
)

type EndWithPredicate struct {
	Value string
}

func NewEndWithPredicate(parameters map[string][]string) (*EndWithPredicate, error) {
	value, err := findSingleValue(parameters, "value")
	if err != nil {
		return nil, err
	}
	return &EndWithPredicate{Value: *value}, nil
}

func (p *EndWithPredicate) Test(data *[]byte) error {
	var s string
	if data != nil {
		s = string(*data)
	}
	if !strings.HasSuffix(s, p.Value) {
		return fmt.Errorf("'%v' doesn't end with '%v'", s, p.Value)
	}
	return nil
}
