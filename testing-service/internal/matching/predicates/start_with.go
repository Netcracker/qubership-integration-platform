package predicates

import (
	"fmt"
	"strings"
)

type StartWithPredicate struct {
	Value string
}

func NewStartWithPredicate(parameters map[string][]string) (*StartWithPredicate, error) {
	value, err := findSingleValue(parameters, "value")
	if err != nil {
		return nil, err
	}
	return &StartWithPredicate{Value: *value}, nil
}

func (p *StartWithPredicate) Test(data *[]byte) error {
	var s string
	if data != nil {
		s = string(*data)
	}
	if !strings.HasPrefix(s, p.Value) {
		return fmt.Errorf("'%v' doesn't start with '%v'", s, p.Value)
	}
	return nil
}
