package predicates

import "fmt"

type EmptyPredicate struct{}

func NewEmptyPredicate() (*EmptyPredicate, error) {
	return &EmptyPredicate{}, nil
}

func (p *EmptyPredicate) Test(data *[]byte) error {
	isEmpty := data == nil || len(*data) == 0
	if !isEmpty {
		return fmt.Errorf("not empty")
	}
	return nil
}
