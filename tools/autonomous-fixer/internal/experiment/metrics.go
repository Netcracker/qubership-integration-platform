package experiment

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentcost"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentusage"
)

type RoleMetrics struct {
	RecordedAt       time.Time                 `json:"recorded_at"`
	JobID            string                    `json:"job_id"`
	Issue            int                       `json:"issue"`
	Role             string                    `json:"role"`
	Profile          Profile                   `json:"profile"`
	RuntimeOutcome   string                    `json:"runtime_outcome"`
	Usage            experimentusage.Report    `json:"usage"`
	TotalTokens      *int64                    `json:"total_tokens"`
	ModelCosts       []experimentcost.Estimate `json:"model_costs"`
	APIEquivalentUSD *float64                  `json:"api_equivalent_usd"`
	KnownCostUSD     float64                   `json:"priced_requests_usd"`
	PricingVersion   string                    `json:"pricing_version"`
	Notes            []string                  `json:"notes"`
}

type MetricTotal struct {
	CompletedRoles   int                  `json:"completed_roles"`
	Tokens           experimentcost.Usage `json:"tokens"`
	TotalTokens      *int64               `json:"total_tokens"`
	APIEquivalentUSD *float64             `json:"api_equivalent_usd"`
	KnownCostUSD     float64              `json:"priced_requests_usd"`
	UnknownCostRoles []string             `json:"unknown_cost_roles"`
}

type JobMetrics struct {
	JobID          string      `json:"job_id"`
	Issue          int         `json:"issue"`
	Executor       Profile     `json:"executor"`
	Implementation MetricTotal `json:"implementation"`
	Reviews        MetricTotal `json:"reviews"`
	AllRoles       MetricTotal `json:"all_completed_roles"`
}

type MetricsSummary struct {
	RecordedAt     time.Time              `json:"recorded_at"`
	PricingVersion string                 `json:"pricing_version"`
	Scope          string                 `json:"scope"`
	Jobs           []JobMetrics           `json:"jobs"`
	Models         map[string]MetricTotal `json:"models"`
	Total          MetricTotal            `json:"all_completed_roles"`
	Notes          []string               `json:"notes"`
}

// RefreshMetrics reads completed roles and writes derived reports without the execution lock.
func RefreshMetrics(root, id, role string) (MetricsSummary, error) {
	var summary MetricsSummary
	plan, err := Load(root)
	if err != nil {
		return summary, err
	}
	// Derived reports have a separate lock so an active model run can continue.
	unlock, err := Lock(filepath.Join(root, "metrics"))
	if err != nil {
		return summary, err
	}
	defer unlock()
	if id != "" {
		if _, err = plan.Job(id); err != nil {
			return summary, err
		}
		if _, err = recordRoleMetrics(root, id, role); err != nil {
			return summary, err
		}
	} else {
		for _, job := range plan.Jobs {
			for _, selected := range []string{"implement", "r1", "r2", "r3"} {
				dir, e := RunDirectory(root, job.ID, selected)
				if e != nil {
					return summary, e
				}
				if _, e = os.Stat(filepath.Join(dir, "execution", "result.json")); os.IsNotExist(e) {
					continue
				} else if e != nil {
					return summary, e
				}
				if _, e = recordRoleMetrics(root, job.ID, selected); e != nil {
					return summary, e
				}
			}
		}
	}
	summary = MetricsSummary{
		RecordedAt: time.Now().UTC(), PricingVersion: experimentcost.PricingVersion,
		Scope: "Observed completed implementation and review attempts, including failed and calibration attempts.",
		Jobs:  []JobMetrics{}, Models: map[string]MetricTotal{}, Total: emptyMetricTotal(),
		Notes: []string{
			"API equivalents use Standard token pricing; they are not subscription charges or invoices.",
			"Web search and other tool fees, infrastructure, tax, and subscription fees are excluded.",
			"Unstarted and active roles are excluded. No planned review cost is imputed.",
			"A null total means that some observed usage could not be priced; priced_requests_usd is a subtotal.",
			"Model token totals cover attributed requests; role totals also include auxiliary usage reported only in aggregate.",
		},
	}
	for _, job := range plan.Jobs {
		group := JobMetrics{JobID: job.ID, Issue: job.Issue, Executor: job.Executor,
			Implementation: emptyMetricTotal(), Reviews: emptyMetricTotal(), AllRoles: emptyMetricTotal()}
		for _, selected := range []string{"implement", "r1", "r2", "r3"} {
			dir, e := RunDirectory(root, job.ID, selected)
			if e != nil {
				return summary, e
			}
			var metric RoleMetrics
			e = ReadJSON(filepath.Join(dir, "execution", "usage-cost.json"), &metric)
			if os.IsNotExist(e) {
				continue
			}
			if e != nil {
				return summary, e
			}
			if metric.PricingVersion != experimentcost.PricingVersion {
				return summary, fmt.Errorf("recompute metrics for %s/%s before combining pricing snapshots", job.ID, selected)
			}
			key := job.ID + "/" + selected
			addMetric(&summary.Total, key, metric.APIEquivalentUSD, metric.KnownCostUSD, metric.Usage.Tokens)
			addMetric(&group.AllRoles, key, metric.APIEquivalentUSD, metric.KnownCostUSD, metric.Usage.Tokens)
			if selected == "implement" {
				addMetric(&group.Implementation, key, metric.APIEquivalentUSD, metric.KnownCostUSD, metric.Usage.Tokens)
			} else {
				addMetric(&group.Reviews, key, metric.APIEquivalentUSD, metric.KnownCostUSD, metric.Usage.Tokens)
			}
			for _, estimate := range metric.ModelCosts {
				var tokens experimentcost.Usage
				for _, model := range metric.Usage.Models {
					if model.Model == estimate.Model {
						tokens = model.Tokens
						break
					}
				}
				total, exists := summary.Models[estimate.Model]
				if !exists {
					total = emptyMetricTotal()
				}
				known := 0.0
				if estimate.TotalUSD != nil {
					known = *estimate.TotalUSD
				}
				cost := estimate.TotalUSD
				if metric.APIEquivalentUSD == nil {
					cost = nil
				}
				addMetric(&total, key, cost, known, tokens)
				summary.Models[estimate.Model] = total
			}
		}
		if group.AllRoles.CompletedRoles > 0 {
			summary.Jobs = append(summary.Jobs, group)
		}
	}
	return summary, replaceMetricsJSON(filepath.Join(root, "metrics-summary.json"), summary)
}

func recordRoleMetrics(root, id, role string) (RoleMetrics, error) {
	var metric RoleMetrics
	dir, err := RunDirectory(root, id, role)
	if err != nil {
		return metric, err
	}
	var prepared Prepared
	if err = ReadJSON(filepath.Join(dir, "prepared.json"), &prepared); err != nil {
		return metric, err
	}
	var result Execution
	executionDir := filepath.Join(dir, "execution")
	if err = ReadJSON(filepath.Join(executionDir, "result.json"), &result); err != nil {
		return metric, err
	}
	usage, err := experimentusage.Read(prepared.Profile.Runtime, prepared.Profile.Model, executionDir)
	if err != nil {
		usage = experimentusage.Report{Source: "unavailable", Warnings: []string{err.Error()}}
	}
	metric = roleCost(prepared, usage)
	metric.RecordedAt, metric.RuntimeOutcome = time.Now().UTC(), result.Outcome
	return metric, replaceMetricsJSON(filepath.Join(executionDir, "usage-cost.json"), metric)
}

func roleCost(prepared Prepared, usage experimentusage.Report) RoleMetrics {
	m := RoleMetrics{JobID: prepared.JobID, Issue: prepared.Issue, Role: prepared.Role,
		Profile: prepared.Profile, Usage: usage, PricingVersion: experimentcost.PricingVersion,
		ModelCosts: []experimentcost.Estimate{}, Notes: []string{
			"Token-only API equivalent at the pricing snapshot date; not an actual charge.",
			"Input includes cached tokens; output includes reasoning. Neither subset is added twice.",
		}}
	if usage.Tokens.Input != nil && usage.Tokens.Output != nil {
		n := *usage.Tokens.Input + *usage.Tokens.Output
		m.TotalTokens = &n
	}
	allPriced, allFree := true, true
	for _, model := range usage.Models {
		e := experimentcost.Calculate(model.Model, model.Requests)
		m.ModelCosts = append(m.ModelCosts, e)
		allFree = allFree && e.Free
		if e.TotalUSD == nil {
			allPriced = false
		} else {
			m.KnownCostUSD += *e.TotalUSD
		}
	}
	if len(m.ModelCosts) == 0 {
		e := experimentcost.Calculate(prepared.Profile.Model, nil)
		m.ModelCosts = append(m.ModelCosts, e)
		allFree, allPriced = e.Free, e.TotalUSD != nil
	}
	sort.Slice(m.ModelCosts, func(i, j int) bool { return m.ModelCosts[i].Model < m.ModelCosts[j].Model })
	declared := experimentcost.Calculate(prepared.Profile.Model, nil)
	if allPriced && (usage.Complete || (allFree && declared.Free)) {
		cost := m.KnownCostUSD
		m.APIEquivalentUSD = &cost
		if !usage.Complete {
			m.Notes = append(m.Notes, "Zero cost follows the free-model policy even where token attribution is incomplete.")
		}
	} else {
		m.Notes = append(m.Notes, "Usage or model pricing is incomplete; the full API equivalent is unknown.")
	}
	return m
}

func emptyMetricTotal() MetricTotal {
	zero := 0.0
	return MetricTotal{APIEquivalentUSD: &zero, UnknownCostRoles: []string{}, Tokens: experimentcost.Usage{Input: new(int64), CachedInput: new(int64), CacheWrite: new(int64), Output: new(int64), Reasoning: new(int64)}, TotalTokens: new(int64)}
}

func addMetric(total *MetricTotal, role string, cost *float64, known float64, tokens experimentcost.Usage) {
	total.CompletedRoles++
	total.KnownCostUSD += known
	total.Tokens = experimentcost.Usage{Input: addTokens(total.Tokens.Input, tokens.Input), CachedInput: addTokens(total.Tokens.CachedInput, tokens.CachedInput), CacheWrite: addTokens(total.Tokens.CacheWrite, tokens.CacheWrite), Output: addTokens(total.Tokens.Output, tokens.Output), Reasoning: addTokens(total.Tokens.Reasoning, tokens.Reasoning)}
	total.TotalTokens = addTokens(total.Tokens.Input, total.Tokens.Output)
	if cost == nil {
		total.APIEquivalentUSD = nil
		total.UnknownCostRoles = append(total.UnknownCostRoles, role)
	} else if total.APIEquivalentUSD != nil {
		*total.APIEquivalentUSD += *cost
	}
}

func addTokens(a, b *int64) *int64 {
	if a == nil || b == nil {
		return nil
	}
	n := *a + *b
	return &n
}

func replaceMetricsJSON(path string, value any) error {
	b, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	f, err := os.CreateTemp(filepath.Dir(path), ".usage-metrics-*.json")
	if err != nil {
		return err
	}
	defer os.Remove(f.Name())
	if _, err = f.Write(append(b, '\n')); err != nil {
		_ = f.Close()
		return err
	}
	if err = f.Close(); err != nil {
		return err
	}
	return os.Rename(f.Name(), path)
}
