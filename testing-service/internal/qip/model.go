// Package qip talks to the platform services the testing service depends on:
// the runtime catalog, which describes the chain elements a test case activates
// and the engines the chain is deployed to.
package qip

import (
	"slices"
	"strings"
)

// ChainElement is one element of an integration chain, cut down to what the
// trigger resolver reads. The catalog answers with a great deal more — the
// audit fields, the swimlane, the whole subtree of children — and decoding any
// of it would only make the reply larger to walk.
type ChainElement struct {
	Type string `json:"type"`
	// Properties carries the element-type-specific settings, such as the path an
	// HTTP trigger listens on.
	Properties map[string]any `json:"properties"`
}

// StatusDeployed is the only deployment status that names an engine serving the
// chain. The catalog also reports REMOVED, FAILED, PROCESSING and DRAFT.
const StatusDeployed = "DEPLOYED"

// The kinds of engine domain. A classic deployment is reported without one, so
// an unset value reads as DomainTypeClassic.
const (
	DomainTypeClassic = "CLASSIC"
	DomainTypeMicro   = "MICRO"
)

// Deployment is one deployment of a chain, cut down to what resolving an engine
// address needs.
//
// The address is built from Runtime.States rather than from ServiceName: the
// catalog documents that field as the name to show beside an error, and it does
// not carry the version suffix the Kubernetes service of a micro domain gets —
// "qip-engine-micro" against a service actually named "qip-engine-micro-v1".
// The keys of Runtime.States are the engine hosts themselves, which is what the
// catalog uses to reach an engine of its own accord.
type Deployment struct {
	ChainID string `json:"chainId"`
	Domain  string `json:"domain"`
	// DomainType is CLASSIC or MICRO. A deployment of the classic kind is
	// reported without it, so an empty value reads as CLASSIC.
	DomainType string            `json:"domainType"`
	Runtime    DeploymentRuntime `json:"runtime"`
}

type DeploymentRuntime struct {
	// States maps an engine host to the state of this deployment on it.
	States map[string]DeploymentState `json:"states"`
}

type DeploymentState struct {
	Status string `json:"status"`
}

// DeployedHosts returns the hosts serving this deployment, sorted, so that a
// deployment spread over several replicas resolves to the same one every time.
func (d Deployment) DeployedHosts() []string {
	var hosts []string
	for host, state := range d.Runtime.States {
		if state.Status == StatusDeployed && strings.TrimSpace(host) != "" {
			hosts = append(hosts, host)
		}
	}
	slices.Sort(hosts)
	return hosts
}
