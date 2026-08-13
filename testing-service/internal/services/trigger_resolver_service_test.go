package services

import (
	"context"
	"errors"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/qip"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/triggers"
)

type fakeCatalogClient struct {
	element         *qip.ChainElement
	err             error
	chainID         string
	elementID       string
	lookupPerformed bool
}

func (c *fakeCatalogClient) FindChainElement(_ context.Context, chainID, elementID string) (*qip.ChainElement, error) {
	c.lookupPerformed = true
	c.chainID = chainID
	c.elementID = elementID
	return c.element, c.err
}

type fakeTriggerFactory struct {
	triggerType string
	parameters  map[string]any
	err         error
}

func (f *fakeTriggerFactory) GetTrigger(triggerType string, parameters map[string]any) (triggers.Trigger, error) {
	f.triggerType = triggerType
	f.parameters = parameters
	if f.err != nil {
		return nil, f.err
	}
	return &fakeTrigger{}, nil
}

func TestResolveTriggerPassesTheChainAndElementOfTheReference(t *testing.T) {
	catalog := &fakeCatalogClient{element: &qip.ChainElement{
		Type:       "http-trigger",
		Properties: map[string]any{"contextPath": "/orders"},
	}}
	factory := &fakeTriggerFactory{}
	service := NewTriggerResolverService(catalog, factory)

	trigger, err := service.ResolveTrigger(context.Background(),
		&dao.TriggerReference{ChainID: "chain-1", ElementID: "element-1"})

	require.NoError(t, err)
	assert.NotNil(t, trigger)
	assert.Equal(t, "chain-1", catalog.chainID)
	assert.Equal(t, "element-1", catalog.elementID)
	assert.Equal(t, "http-trigger", factory.triggerType)
	assert.Equal(t, map[string]any{"contextPath": "/orders"}, factory.parameters)
}

// The reference is nullable, and the source dereferenced it unguarded.
func TestResolveTriggerRejectsATestCaseWithoutATriggerReference(t *testing.T) {
	catalog := &fakeCatalogClient{}
	service := NewTriggerResolverService(catalog, &fakeTriggerFactory{})

	trigger, err := service.ResolveTrigger(context.Background(), nil)

	require.Error(t, err)
	assert.Nil(t, trigger)
	assert.False(t, catalog.lookupPerformed)
}

func TestResolveTriggerReportsAFailingCatalogLookup(t *testing.T) {
	failure := errors.New("catalog unavailable")
	service := NewTriggerResolverService(&fakeCatalogClient{err: failure}, &fakeTriggerFactory{})

	trigger, err := service.ResolveTrigger(context.Background(), &dao.TriggerReference{})

	require.ErrorIs(t, err, failure)
	assert.Nil(t, trigger)
}

func TestResolveTriggerReportsAnUnsupportedElementType(t *testing.T) {
	failure := errors.New("trigger type not supported")
	service := NewTriggerResolverService(
		&fakeCatalogClient{element: &qip.ChainElement{Type: "sender"}},
		&fakeTriggerFactory{err: failure},
	)

	trigger, err := service.ResolveTrigger(context.Background(), &dao.TriggerReference{})

	require.ErrorIs(t, err, failure)
	assert.Nil(t, trigger)
}
