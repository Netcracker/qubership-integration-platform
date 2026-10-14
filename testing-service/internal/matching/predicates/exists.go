package predicates

import "fmt"

type ExistPredicate struct{}

func NewExistPredicate() (*ExistPredicate, error) {
	return &ExistPredicate{}, nil
}

func (p *ExistPredicate) Test(data *[]byte) error {
	if data == nil {
		return fmt.Errorf("does not exist")
	}
	return nil
}
