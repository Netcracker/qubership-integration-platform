// Package triggers activates the chain element a test case points at.
package triggers

import (
	"context"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

// Trigger starts one chain and reports what the chain answered.
type Trigger interface {
	Activate(ctx context.Context, requestSettings *dao.RequestSettings) (*model.Exchange, error)
}
