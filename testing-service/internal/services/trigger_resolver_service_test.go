package services

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/qip"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/triggers"
)

const configuredEngineAddress = "http://qip-engine:8080"

type fakeCatalogClient struct {
	element         *qip.ChainElement
	err             error
	chainID         string
	elementID       string
	lookupPerformed bool

	deployments          []qip.Deployment
	deploymentsErr       error
	deploymentsChainID   string
	deploymentsRequested bool
}

func (c *fakeCatalogClient) FindChainElement(_ context.Context, chainID, elementID string) (*qip.ChainElement, error) {
	c.lookupPerformed = true
	c.chainID = chainID
	c.elementID = elementID
	return c.element, c.err
}

func (c *fakeCatalogClient) FindChainDeployments(_ context.Context, chainID string) ([]qip.Deployment, error) {
	c.deploymentsRequested = true
	c.deploymentsChainID = chainID
	return c.deployments, c.deploymentsErr
}

type fakeTriggerFactory struct {
	engineAddress string
	triggerType   string
	parameters    map[string]any
	err           error
}

func (f *fakeTriggerFactory) GetTrigger(
	engineAddress string,
	triggerType string,
	parameters map[string]any,
) (triggers.Trigger, error) {
	f.engineAddress = engineAddress
	f.triggerType = triggerType
	f.parameters = parameters
	if f.err != nil {
		return nil, f.err
	}
	return &fakeTrigger{}, nil
}

func newTestTriggerResolver(catalog qip.CatalogClient, factory triggers.Factory) TriggerResolverService {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	return NewTriggerResolverService(logger, configuredEngineAddress, catalog, factory)
}

// deployedOn builds the catalog answer for a chain served on one micro domain.
func deployedOn(domain string, hosts ...string) qip.Deployment {
	deployment := deployedOnClassic(domain, hosts...)
	deployment.DomainType = qip.DomainTypeMicro
	return deployment
}

// deployedOnClassic builds the same for a classic domain, which the catalog
// reports without a domain type.
func deployedOnClassic(domain string, hosts ...string) qip.Deployment {
	states := map[string]qip.DeploymentState{}
	for _, host := range hosts {
		states[host] = qip.DeploymentState{Status: qip.StatusDeployed}
	}
	return qip.Deployment{Domain: domain, Runtime: qip.DeploymentRuntime{States: states}}
}

func TestResolveTriggerPassesTheChainAndElementOfTheReference(t *testing.T) {
	catalog := &fakeCatalogClient{
		element: &qip.ChainElement{
			Type:       "http-trigger",
			Properties: map[string]any{"contextPath": "/orders"},
		},
		deployments: []qip.Deployment{deployedOn("default", "10.244.0.5")},
	}
	factory := &fakeTriggerFactory{}
	service := newTestTriggerResolver(catalog, factory)

	trigger, err := service.ResolveTrigger(context.Background(),
		&dao.TriggerReference{ChainID: "chain-1", ElementID: "element-1"})

	require.NoError(t, err)
	assert.NotNil(t, trigger)
	assert.Equal(t, "chain-1", catalog.chainID)
	assert.Equal(t, "element-1", catalog.elementID)
	assert.Equal(t, "chain-1", catalog.deploymentsChainID)
	assert.Equal(t, "http-trigger", factory.triggerType)
	assert.Equal(t, map[string]any{"contextPath": "/orders"}, factory.parameters)
}

// The whole point of the lookup: a chain on a micro domain is activated on the
// engine of that domain, not on the one an installation configured.
func TestResolveTriggerActivatesOnTheEngineServingTheChain(t *testing.T) {
	catalog := &fakeCatalogClient{
		element:     &qip.ChainElement{Type: "http-trigger"},
		deployments: []qip.Deployment{deployedOn("micro", "10.244.0.24")},
	}
	factory := &fakeTriggerFactory{}

	_, err := newTestTriggerResolver(catalog, factory).ResolveTrigger(context.Background(),
		&dao.TriggerReference{ChainID: "chain-1"})

	require.NoError(t, err)
	assert.Equal(t, "http://10.244.0.24:8080", factory.engineAddress)
}

// A chain on both kinds of engine is the configuration nobody meant to make.
// The classic domain wins, because that is where such a chain was activated
// before the lookup existed.
func TestResolveTriggerPrefersTheClassicDomainWhenAChainIsOnSeveralOfThem(t *testing.T) {
	catalog := &fakeCatalogClient{
		element: &qip.ChainElement{Type: "http-trigger"},
		// The micro domain sorts first by name, so only the preference itself can
		// put the classic one ahead of it.
		deployments: []qip.Deployment{
			deployedOn("alpha", "10.244.0.24"),
			deployedOnClassic("default", "10.244.0.5"),
		},
	}
	factory := &fakeTriggerFactory{}

	_, err := newTestTriggerResolver(catalog, factory).ResolveTrigger(context.Background(),
		&dao.TriggerReference{ChainID: "chain-1"})

	require.NoError(t, err)
	assert.Equal(t, "http://10.244.0.5:8080", factory.engineAddress)
}

// Without a classic domain to prefer, the name settles it, so the same
// installation resolves the same way on every run.
func TestResolveTriggerPicksTheSameDomainEveryRunAmongMicroOnes(t *testing.T) {
	catalog := &fakeCatalogClient{
		element: &qip.ChainElement{Type: "http-trigger"},
		deployments: []qip.Deployment{
			deployedOn("orders", "10.244.0.30"),
			deployedOn("billing", "10.244.0.24"),
		},
	}

	for range 5 {
		factory := &fakeTriggerFactory{}
		_, err := newTestTriggerResolver(catalog, factory).ResolveTrigger(context.Background(),
			&dao.TriggerReference{ChainID: "chain-1"})
		require.NoError(t, err)
		assert.Equal(t, "http://10.244.0.24:8080", factory.engineAddress)
	}
}

// A chain the catalog reports nowhere still runs on the configured engine, which
// is what an installation had before the lookup existed.
func TestResolveTriggerFallsBackToTheConfiguredEngineWhenNothingServesTheChain(t *testing.T) {
	catalog := &fakeCatalogClient{element: &qip.ChainElement{Type: "http-trigger"}}
	factory := &fakeTriggerFactory{}

	_, err := newTestTriggerResolver(catalog, factory).ResolveTrigger(context.Background(),
		&dao.TriggerReference{ChainID: "chain-1"})

	require.NoError(t, err)
	assert.Equal(t, configuredEngineAddress, factory.engineAddress)
}

// A deployment the engine has not taken up names no engine to reach.
func TestResolveTriggerIgnoresADeploymentThatIsNotDeployed(t *testing.T) {
	catalog := &fakeCatalogClient{
		element: &qip.ChainElement{Type: "http-trigger"},
		deployments: []qip.Deployment{{
			Domain: "micro",
			Runtime: qip.DeploymentRuntime{States: map[string]qip.DeploymentState{
				"10.244.0.24": {Status: "FAILED"},
			}},
		}},
	}
	factory := &fakeTriggerFactory{}

	_, err := newTestTriggerResolver(catalog, factory).ResolveTrigger(context.Background(),
		&dao.TriggerReference{ChainID: "chain-1"})

	require.NoError(t, err)
	assert.Equal(t, configuredEngineAddress, factory.engineAddress)
}

// The lookup is an improvement on a fixed address, not a new dependency of the
// run: a catalog that cannot answer leaves the test where it was before.
func TestResolveTriggerFallsBackToTheConfiguredEngineWhenTheDeploymentLookupFails(t *testing.T) {
	catalog := &fakeCatalogClient{
		element:        &qip.ChainElement{Type: "http-trigger"},
		deploymentsErr: errors.New("catalog unavailable"),
	}
	factory := &fakeTriggerFactory{}

	_, err := newTestTriggerResolver(catalog, factory).ResolveTrigger(context.Background(),
		&dao.TriggerReference{ChainID: "chain-1"})

	require.NoError(t, err)
	assert.Equal(t, configuredEngineAddress, factory.engineAddress)
}

// Several replicas of one domain serve the same chain, so the choice only has
// to be the same one every run.
func TestResolveTriggerPicksTheSameReplicaEveryRun(t *testing.T) {
	catalog := &fakeCatalogClient{
		element:     &qip.ChainElement{Type: "http-trigger"},
		deployments: []qip.Deployment{deployedOn("micro", "10.244.0.99", "10.244.0.24", "10.244.0.50")},
	}

	for range 5 {
		factory := &fakeTriggerFactory{}
		_, err := newTestTriggerResolver(catalog, factory).ResolveTrigger(context.Background(),
			&dao.TriggerReference{ChainID: "chain-1"})
		require.NoError(t, err)
		assert.Equal(t, "http://10.244.0.24:8080", factory.engineAddress)
	}
}

// The reference is nullable, and the source dereferenced it unguarded.
func TestResolveTriggerRejectsATestCaseWithoutATriggerReference(t *testing.T) {
	catalog := &fakeCatalogClient{}
	service := newTestTriggerResolver(catalog, &fakeTriggerFactory{})

	trigger, err := service.ResolveTrigger(context.Background(), nil)

	require.Error(t, err)
	assert.Nil(t, trigger)
	assert.False(t, catalog.lookupPerformed)
}

func TestResolveTriggerReportsAFailingCatalogLookup(t *testing.T) {
	failure := errors.New("catalog unavailable")
	service := newTestTriggerResolver(&fakeCatalogClient{err: failure}, &fakeTriggerFactory{})

	trigger, err := service.ResolveTrigger(context.Background(), &dao.TriggerReference{})

	require.ErrorIs(t, err, failure)
	assert.Nil(t, trigger)
}

func TestResolveTriggerReportsAnUnsupportedElementType(t *testing.T) {
	failure := errors.New("trigger type not supported")
	service := newTestTriggerResolver(
		&fakeCatalogClient{element: &qip.ChainElement{Type: "sender"}},
		&fakeTriggerFactory{err: failure},
	)

	trigger, err := service.ResolveTrigger(context.Background(), &dao.TriggerReference{})

	require.ErrorIs(t, err, failure)
	assert.Nil(t, trigger)
}
