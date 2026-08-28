package services

import (
	"context"
	"fmt"
	"log/slog"
	"net/url"
	"slices"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/qip"
)

// engineAddressResolver answers where a chain is served, so that a test case
// activates the trigger on the engine actually running it.
//
// The configured address is a single one, and a chain is not: a micro domain
// gets a Kubernetes service of its own, so an installation running more than one
// domain has more than one engine to reach. The catalog is what knows which, and
// it reports the hosts of every deployment.
type engineAddressResolver struct {
	logger *slog.Logger
	// configuredAddress is what an installation set. Its scheme and port are
	// reused for a resolved host, and it is the whole answer when nothing else
	// is known.
	configuredAddress string
	catalogClient     qip.CatalogClient
}

func newEngineAddressResolver(
	logger *slog.Logger,
	configuredAddress string,
	catalogClient qip.CatalogClient,
) *engineAddressResolver {
	return &engineAddressResolver{
		logger:            logger,
		configuredAddress: configuredAddress,
		catalogClient:     catalogClient,
	}
}

// Resolve returns the base address of the engine to activate chainID on.
//
// A chain deployed nowhere the catalog can see falls back to the configured
// address rather than failing: the classic engine keeps a stable service, so a
// test still runs while the engine state cache of the catalog is cold.
//
// The fallback covers a catalog that answers, not one that is down. Resolving a
// trigger reads the chain element from the same catalog first, so an
// installation whose catalog is unreachable fails there, before this lookup.
func (r *engineAddressResolver) Resolve(ctx context.Context, chainID string) (string, error) {
	deployments, err := r.catalogClient.FindChainDeployments(ctx, chainID)
	if err != nil {
		// The lookup is an improvement on a fixed address, not a new dependency
		// of the run: a deployments endpoint that cannot answer leaves the test
		// where it was before, on the configured engine.
		r.logger.WarnContext(ctx, "Cannot read the deployments of the chain, activating on the configured engine",
			"chainId", chainID, "engineAddress", r.configuredAddress, "error", err)
		return r.configuredAddress, nil
	}

	domains := servingDomains(deployments)
	if len(domains) == 0 {
		r.logger.InfoContext(ctx, "The catalog reports no engine serving the chain, activating on the configured engine",
			"chainId", chainID, "engineAddress", r.configuredAddress)
		return r.configuredAddress, nil
	}

	chosen := domains[0]
	if len(domains) > 1 {
		// A chain is normally on one kind of engine or the other, so this is the
		// configuration nobody meant to make. The classic domain wins because it
		// is where such a chain was activated before this lookup existed, and the
		// choice is logged rather than silently made.
		r.logger.WarnContext(ctx, "The chain is served on several domains, activating on one of them",
			"chainId", chainID, "domain", chosen.name, "domains", domainNames(domains))
	}
	// The hosts are sorted, so a domain of several replicas resolves to the same
	// engine on every run rather than wandering between them.
	address, err := r.addressOf(chosen.hosts[0])
	if err != nil {
		return "", err
	}
	r.logger.DebugContext(ctx, "Resolved the engine serving the chain",
		"chainId", chainID, "domain", chosen.name, "engineAddress", address)
	return address, nil
}

// servingDomain is one domain serving the chain, with the engines behind it.
type servingDomain struct {
	name    string
	classic bool
	hosts   []string
}

// servingDomains collects the domains serving the chain, most preferred first: a
// classic domain before a micro one, and among equals by name, so that the same
// installation resolves the same way on every run. A domain whose deployment is
// not DEPLOYED anywhere is left out, since nothing there would answer.
func servingDomains(deployments []qip.Deployment) []servingDomain {
	hostsByDomain := map[string][]string{}
	classicByDomain := map[string]bool{}
	for _, deployment := range deployments {
		hosts := deployment.DeployedHosts()
		if len(hosts) == 0 {
			continue
		}
		hostsByDomain[deployment.Domain] = append(hostsByDomain[deployment.Domain], hosts...)
		// The classic engine is reported without a domain type, so an unset one
		// reads as classic.
		if deployment.DomainType == "" || deployment.DomainType == qip.DomainTypeClassic {
			classicByDomain[deployment.Domain] = true
		}
	}

	domains := make([]servingDomain, 0, len(hostsByDomain))
	for name, hosts := range hostsByDomain {
		slices.Sort(hosts)
		domains = append(domains, servingDomain{
			name:    name,
			classic: classicByDomain[name],
			hosts:   slices.Compact(hosts),
		})
	}
	slices.SortFunc(domains, func(a, b servingDomain) int {
		if a.classic != b.classic {
			if a.classic {
				return -1
			}
			return 1
		}
		return strings.Compare(a.name, b.name)
	})
	return domains
}

func domainNames(domains []servingDomain) []string {
	names := make([]string, 0, len(domains))
	for _, domain := range domains {
		names = append(names, domain.name)
	}
	return names
}

// addressOf puts host where the configured address names one, keeping the scheme
// and the port an installation chose. Engines of every domain listen on the same
// port, so only the host differs.
func (r *engineAddressResolver) addressOf(host string) (string, error) {
	configured, err := url.Parse(r.configuredAddress)
	if err != nil {
		return "", fmt.Errorf("engine address %q is not a URL: %w", r.configuredAddress, err)
	}
	if port := configured.Port(); port != "" {
		configured.Host = fmt.Sprintf("%s:%s", host, port)
	} else {
		configured.Host = host
	}
	return configured.String(), nil
}
