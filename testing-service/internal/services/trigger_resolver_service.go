package services

import (
	"context"
	"errors"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/qip"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/triggers"
)

// TriggerResolverService turns the chain element a test case points at into a
// trigger the executor can activate.
type TriggerResolverService interface {
	ResolveTrigger(ctx context.Context, triggerReference *dao.TriggerReference) (triggers.Trigger, error)
}

type triggerResolverService struct {
	catalogClient  qip.CatalogClient
	triggerFactory triggers.Factory
}

// NewTriggerResolverService returns a TriggerResolverService that reads chain
// elements from the catalog and builds triggers with the given factory.
func NewTriggerResolverService(catalogClient qip.CatalogClient, triggerFactory triggers.Factory) TriggerResolverService {
	return &triggerResolverService{catalogClient: catalogClient, triggerFactory: triggerFactory}
}

func (s *triggerResolverService) ResolveTrigger(
	ctx context.Context,
	triggerReference *dao.TriggerReference,
) (triggers.Trigger, error) {
	if triggerReference == nil {
		return nil, errors.New("the test case has no trigger reference")
	}
	chainElement, err := s.catalogClient.FindChainElement(ctx, triggerReference.ChainID, triggerReference.ElementID)
	if err != nil {
		return nil, err
	}
	return s.triggerFactory.GetTrigger(chainElement.Type, chainElement.Properties)
}
