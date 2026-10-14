// Package matching evaluates matcher definitions against an exchange: an
// EntityDataGetter extracts the data a matcher looks at, a MatcherPredicate
// decides whether that data passes.
package matching

import (
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

type EntityDataGetter interface {
	GetData(exchange model.Exchange) (*[]byte, error)
}

type MatcherPredicate interface {
	Test(data *[]byte) error
}
