package predicates

import (
	"fmt"
	"regexp"
)

type MatchPredicate struct {
	Pattern regexp.Regexp
}

func NewMatchPredicate(parameters map[string][]string) (*MatchPredicate, error) {
	expression, err := findSingleValue(parameters, "pattern")
	if err != nil {
		return nil, err
	}
	pattern, err := regexp.Compile(*expression)
	if err != nil {
		return nil, err
	}
	return &MatchPredicate{Pattern: *pattern}, nil
}

func (p *MatchPredicate) Test(data *[]byte) error {
	var s string
	if data != nil {
		s = string(*data)
	}
	if !p.Pattern.MatchString(s) {
		return fmt.Errorf("'%v' doesn't match '%v'", s, p.Pattern.String())
	}
	return nil
}
