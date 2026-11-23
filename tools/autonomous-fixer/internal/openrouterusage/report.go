package openrouterusage

import (
	"sort"
	"sync"
)

const Filename = "provider-usage.json"

type Generation struct {
	IDSHA256       string   `json:"generation_sha256"`
	RequestedModel string   `json:"requested_model,omitempty"`
	ServedModel    string   `json:"served_model,omitempty"`
	InputTokens    *int64   `json:"input_tokens"`
	OutputTokens   *int64   `json:"output_tokens"`
	CachedTokens   *int64   `json:"cached_input_tokens"`
	Reasoning      *int64   `json:"reasoning_output_tokens"`
	CostUSD        *float64 `json:"cost_usd"`
	Conflict       bool     `json:"conflict,omitempty"`
}

type Report struct {
	Attempts           int          `json:"attempts"`
	UnknownGenerations int          `json:"unknown_generations"`
	DuplicateResponses int          `json:"duplicate_responses"`
	UnknownCosts       int          `json:"unknown_costs"`
	KnownCostUSD       float64      `json:"known_cost_usd"`
	ReportedCostUSD    *float64     `json:"reported_cost_usd"`
	Generations        []Generation `json:"generations"`
}

type Recorder struct {
	mu       sync.Mutex
	attempts []Generation
}

func (r *Recorder) Begin() func(Generation) {
	r.mu.Lock()
	index := len(r.attempts)
	r.attempts = append(r.attempts, Generation{})
	r.mu.Unlock()
	return func(g Generation) {
		r.mu.Lock()
		defer r.mu.Unlock()
		r.attempts[index] = g
	}
}

func (r *Recorder) Report() Report {
	r.mu.Lock()
	defer r.mu.Unlock()
	report := Report{Attempts: len(r.attempts), Generations: []Generation{}}
	generations := make(map[string]Generation)
	for _, g := range r.attempts {
		if g.IDSHA256 == "" {
			report.UnknownGenerations++
		} else if prior, ok := generations[g.IDSHA256]; ok {
			report.DuplicateResponses++
			prior.merge(g)
			generations[g.IDSHA256] = prior
		} else {
			generations[g.IDSHA256] = g
		}
	}
	report.UnknownCosts = report.UnknownGenerations
	for _, g := range generations {
		if g.Conflict {
			g = Generation{IDSHA256: g.IDSHA256, Conflict: true}
		}
		if g.CostUSD == nil {
			report.UnknownCosts++
		}
		report.Generations = append(report.Generations, g)
	}
	sort.Slice(report.Generations, func(i, j int) bool {
		return report.Generations[i].IDSHA256 < report.Generations[j].IDSHA256
	})
	for _, g := range report.Generations {
		if g.CostUSD != nil {
			report.KnownCostUSD += *g.CostUSD
		}
	}
	if report.Attempts > 0 && report.UnknownCosts == 0 {
		report.ReportedCostUSD = &report.KnownCostUSD
	}
	return report
}

func (g *Generation) merge(other Generation) {
	g.Conflict = g.Conflict || other.Conflict || differentText(&g.IDSHA256, other.IDSHA256)
	g.Conflict = differentText(&g.RequestedModel, other.RequestedModel) || g.Conflict
	g.Conflict = differentText(&g.ServedModel, other.ServedModel) || g.Conflict
	g.Conflict = differentNumber(&g.InputTokens, other.InputTokens) || g.Conflict
	g.Conflict = differentNumber(&g.OutputTokens, other.OutputTokens) || g.Conflict
	g.Conflict = differentNumber(&g.CachedTokens, other.CachedTokens) || g.Conflict
	g.Conflict = differentNumber(&g.Reasoning, other.Reasoning) || g.Conflict
	g.Conflict = differentNumber(&g.CostUSD, other.CostUSD) || g.Conflict
}

func differentText(first *string, next string) bool {
	if *first == "" {
		*first = next
	}
	return next != "" && *first != next
}

func differentNumber[T int64 | float64](first **T, next *T) bool {
	if next == nil || *next < 0 {
		return false
	}
	if *first == nil {
		*first = next
	}
	return **first != *next
}
