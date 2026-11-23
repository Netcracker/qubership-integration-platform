package agentsignature

import (
	"maps"
	"slices"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/jev"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/openrouterusage"
)

func FromJev(reports ...jev.Response) Signature {
	s := Signature{Output: new(int64), USD: new(float64)}
	seen := make(map[string]jev.Response)
	for _, response := range reports {
		if !identifier.MatchString(response.Model) || response.ID == "" {
			s.Output, s.USD = nil, nil
			continue
		}
		s.Model = response.Model
		if previous, ok := seen[response.ID]; ok {
			if previous.Model != response.Model || !sameJevUsage(previous.Usage, response.Usage) {
				s.Output, s.USD = nil, nil
			}
			continue
		}
		seen[response.ID] = response
		if response.Usage == nil {
			s.Output, s.USD = nil, nil
		} else {
			s.Output = add(s.Output, response.Usage.OutputTokens)
			s.USD = addUSD(s.USD, response.Usage.Cost)
		}
	}
	if s.Model == "" {
		return Signature{}
	}
	// Decisions usage does not identify cached input.
	return s
}

func sameJevUsage(a, b *jev.Usage) bool {
	if a == nil || b == nil {
		return a == b
	}
	return equal(a.InputTokens, b.InputTokens) && equal(a.OutputTokens, b.OutputTokens) && equal(a.Cost, b.Cost)
}

func equal[T comparable](a, b *T) bool {
	if a == nil || b == nil {
		return a == b
	}
	return *a == *b
}

func signatureFromOpenRouter(report openrouterusage.Report, author, observed, observedEffort string) Signature {
	requested := strings.TrimPrefix(author, "openrouter/")
	if observed != "" {
		requested = strings.TrimPrefix(observed, "openrouter/")
	}
	s := Signature{Effort: observedEffort, Input: new(int64), Cached: new(int64), Output: new(int64), USD: new(float64)}
	seen := make(map[string]openrouterusage.Generation)
	served := make(map[string]bool)
	for _, g := range report.Generations {
		if g.RequestedModel == requested && !g.Conflict && identifier.MatchString(g.ServedModel) {
			served[g.ServedModel] = true
		}
		if g.IDSHA256 == "" {
			s.Input, s.Cached, s.Output, s.USD = nil, nil, nil, nil
			continue
		}
		if previous, ok := seen[g.IDSHA256]; ok {
			if previous.Conflict != g.Conflict || !equal(previous.InputTokens, g.InputTokens) ||
				!equal(previous.CachedTokens, g.CachedTokens) || !equal(previous.OutputTokens, g.OutputTokens) ||
				!equal(previous.CostUSD, g.CostUSD) {
				s.Input, s.Cached, s.Output, s.USD = nil, nil, nil, nil
			}
			continue
		}
		seen[g.IDSHA256] = g
		if g.Conflict {
			s.Input, s.Cached, s.Output, s.USD = nil, nil, nil, nil
			continue
		}
		s.Input = add(s.Input, subtract(g.InputTokens, g.CachedTokens))
		s.Cached = add(s.Cached, g.CachedTokens)
		s.Output = add(s.Output, g.OutputTokens)
		s.USD = addUSD(s.USD, g.CostUSD)
	}
	if report.UnknownGenerations != 0 || report.Attempts == 0 || len(report.Generations)+report.DuplicateResponses != report.Attempts {
		s.Input, s.Cached, s.Output, s.USD = nil, nil, nil, nil
	}
	models := slices.Collect(maps.Keys(served))
	if len(models) == 1 {
		s.Model = models[0]
	} else {
		s.Model = observed
	}
	if s.Model == "" {
		return Signature{}
	}
	return s
}
