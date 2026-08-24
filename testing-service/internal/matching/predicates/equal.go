package predicates

import "fmt"

type EqualPredicate struct {
	Value string
}

func NewEqualPredicate(parameters map[string][]string) (*EqualPredicate, error) {
	value, err := findSingleValue(parameters, "value")
	if err != nil {
		return nil, err
	}
	return &EqualPredicate{Value: *value}, nil
}

func (p *EqualPredicate) Test(data *[]byte) error {
	var s string
	if data != nil {
		s = string(*data)
	}
	if s != p.Value {
		return fmt.Errorf("expected: '%v', got: '%v'", p.Value, s)
	}
	return nil
}
