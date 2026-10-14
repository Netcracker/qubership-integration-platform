package predicates

import (
	"fmt"
	"strings"
)

type ContainPredicate struct {
	Value string
}

func NewContainPredicate(parameters map[string][]string) (*ContainPredicate, error) {
	value, err := findSingleValue(parameters, "value")
	if err != nil {
		return nil, err
	}
	return &ContainPredicate{Value: *value}, nil
}

func (p *ContainPredicate) Test(data *[]byte) error {
	var s string
	if data != nil {
		s = string(*data)
	}
	if !strings.Contains(s, p.Value) {
		return fmt.Errorf("'%v' doesn't contain '%v'", s, p.Value)
	}
	return nil
}
