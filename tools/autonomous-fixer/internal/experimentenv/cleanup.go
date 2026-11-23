package experimentenv

import (
	"fmt"
	"regexp"
	"strings"
)

type Artifact struct {
	Kind, ID, Name string
	Scope          string `json:",omitempty"`
}
type Registry struct {
	Context, Namespace, NamespaceUID, RunToken string
	Artifacts                                  []Artifact
}
type Plan struct {
	Context, Namespace, NamespaceUID, RunToken string
	Artifacts                                  []Artifact
	Steps                                      []string
	Executes                                   bool
}

func CleanupPlan(registry Registry) (Plan, error) {
	if registry.Context != "docker-desktop" || registry.Namespace != "cip-e2e" || registry.NamespaceUID == "" {
		return Plan{}, fmt.Errorf("cleanup registry must identify the allocated environment")
	}
	if !regexp.MustCompile(`^[a-z][a-z0-9]{5}$`).MatchString(registry.RunToken) {
		return Plan{}, fmt.Errorf("cleanup requires a six-character run token: a lowercase letter followed by lowercase letters or digits")
	}
	supported := map[string]bool{"folder": true, "chain": true, "micro-domain": true, "logging-properties": true, "service": true, "specification-group": true, "environment": true, "context-system": true, "context-service": true, "mcp-system": true, "common-variable": true, "secured-variable": true, "design-template": true, "sessions": true, "endpoint-mock": true}
	owned := map[string]bool{}
	for _, a := range registry.Artifacts {
		if strings.Contains(a.Name, "e2e-"+registry.RunToken) {
			owned[a.ID] = true
		}
	}
	for pass := 0; pass < len(registry.Artifacts); pass++ {
		for _, a := range registry.Artifacts {
			if a.Scope != "" && owned[a.Scope] {
				owned[a.ID] = true
			}
		}
	}
	for _, a := range registry.Artifacts {
		if !supported[a.Kind] || a.ID == "" || !owned[a.ID] {
			return Plan{}, fmt.Errorf("artifact %s/%s lacks supported kind, ID, or run ownership marker", a.Kind, a.ID)
		}
	}

	return Plan{Context: registry.Context, Namespace: registry.Namespace, NamespaceUID: registry.NamespaceUID, RunToken: registry.RunToken, Artifacts: append([]Artifact(nil), registry.Artifacts...), Steps: []string{
		"Discover the environment again and verify namespace UID and baseline images before cleanup.",
		"Use findResidue(catalog, runToken, OUTSIDE_CATALOG) from e2e/support/fixtures.ts and compare every returned artifact with this registry. Stop on unregistered artifacts.",
		"Use sweepRunToken(catalog, runToken, OUTSIDE_CATALOG) from e2e/support/fixtures.ts for the registered run only; preserve failure evidence and stop on cleanup failures.",
		"Repeat findResidue and require an empty result, then verify the environment baseline again.",
		"This plan does not reset unregistered product data, databases, broker queues, deployment resources, or images. A complete baseline reset requires a separately reviewed concrete inventory.",
	}}, nil
}
