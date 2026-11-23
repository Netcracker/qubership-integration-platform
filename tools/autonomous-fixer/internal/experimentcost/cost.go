package experimentcost

import "fmt"

const PricingVersion = "2026-11-08-standard"
const PricingDate = "2026-11-08"

// Input includes cache reads and writes; Output includes reasoning tokens.
type Usage struct {
	Input       *int64 `json:"input"`
	CachedInput *int64 `json:"cached_input"`
	CacheWrite  *int64 `json:"cache_write"`
	Output      *int64 `json:"output"`
	Reasoning   *int64 `json:"reasoning"`
}

type Components struct {
	UncachedInputUSD float64 `json:"uncached_input_usd"`
	CachedInputUSD   float64 `json:"cached_input_usd"`
	CacheWriteUSD    float64 `json:"cache_write_usd"`
	OutputUSD        float64 `json:"output_usd"`
}

type Rates struct {
	Input       float64 `json:"input"`
	CachedInput float64 `json:"cached_input"`
	CacheWrite  float64 `json:"cache_write"`
	Output      float64 `json:"output"`
}

type Estimate struct {
	Model           string      `json:"model"`
	TotalUSD        *float64    `json:"total_usd"`
	Components      *Components `json:"components"`
	LongRequests    int         `json:"long_requests"`
	Reasons         []string    `json:"reasons"`
	PricingVersion  string      `json:"pricing_version"`
	PricingDate     string      `json:"pricing_date"`
	Sources         []string    `json:"sources"`
	Assumptions     []string    `json:"assumptions"`
	Free            bool        `json:"free"`
	RatesPerMillion *Rates      `json:"usd_per_million_tokens"`
}

type rates struct{ input, cached, write, output float64 }

func Calculate(model string, requests []Usage) Estimate {
	e := Estimate{Model: model, PricingVersion: PricingVersion, PricingDate: PricingDate,
		Reasons: []string{}, Sources: []string{}, Assumptions: []string{
			"Standard processing in USD; token charges only.",
			"Cache writes use 30-minute retention.",
			"Input includes cache reads and writes; output includes reasoning.",
			"Each request above 272000 input tokens uses twice the input rates and 1.5 times the output rate.",
		}}
	var r rates
	switch model {
	case "gpt-6.2-sol":
		r = rates{2, .10, 2.50, 10}
		e.PricingDate = "2026-11-17"
		e.PricingVersion = "2026-11-17-standard-short"
		e.Assumptions[len(e.Assumptions)-1] = "Only requests within the experiment's short-context range have a confirmed rate."
		e.Sources = append(e.Sources, "https://developers.openai.com/api/docs/pricing")
	case "gpt-6.1-sol":
		r = rates{2, .10, 2.50, 10}
	case "gpt-6.1-terra":
		r = rates{1, .05, 1.25, 5}
	case "gpt-6-luna":
		r = rates{.10, .01, .125, .50}
	case "opencode/deepseek-v4.1-flash-free", "opencode/glm-5.3-flash-free", "opencode/mimo-v2.6-flash-free", "opencode/beam-free":
		e.Free = true
		e.Assumptions = []string{"This exact model has zero cost under the experiment's free-model policy."}
	default:
		e.Reasons = append(e.Reasons, "model has no pricing snapshot")
	}
	if !e.Free && len(e.Reasons) == 0 {
		e.Sources = append(e.Sources, "https://developers.openai.com/api/docs/models/"+model)
		e.RatesPerMillion = &Rates{Input: r.input, CachedInput: r.cached, CacheWrite: r.write, Output: r.output}
	}
	if len(requests) == 0 {
		e.Reasons = append(e.Reasons, "request usage is missing")
	}
	c := Components{}
	for i, u := range requests {
		problems := validate(u)
		for _, problem := range problems {
			e.Reasons = append(e.Reasons, fmt.Sprintf("request %d: %s", i+1, problem))
		}
		if len(problems) > 0 || e.Free {
			continue
		}
		inputMultiplier, outputMultiplier := 1.0, 1.0
		if *u.Input > 272000 {
			if model == "gpt-6.2-sol" {
				e.Reasons = append(e.Reasons, "long-context rate for gpt-6.2-sol is unconfirmed")
				continue
			}
			inputMultiplier, outputMultiplier = 2, 1.5
			e.LongRequests++
		}
		c.UncachedInputUSD += float64(*u.Input-*u.CachedInput-*u.CacheWrite) * r.input * inputMultiplier / 1e6
		c.CachedInputUSD += float64(*u.CachedInput) * r.cached * inputMultiplier / 1e6
		c.CacheWriteUSD += float64(*u.CacheWrite) * r.write * inputMultiplier / 1e6
		c.OutputUSD += float64(*u.Output) * r.output * outputMultiplier / 1e6
	}
	if e.Free || len(e.Reasons) == 0 {
		total := c.UncachedInputUSD + c.CachedInputUSD + c.CacheWriteUSD + c.OutputUSD
		e.TotalUSD, e.Components = &total, &c
	}
	return e
}

func validate(u Usage) []string {
	problems := []string{}
	fields := []struct {
		name     string
		value    *int64
		required bool
	}{
		{"input", u.Input, true}, {"cached_input", u.CachedInput, true}, {"cache_write", u.CacheWrite, true}, {"output", u.Output, true}, {"reasoning", u.Reasoning, false},
	}
	for _, field := range fields {
		if field.value == nil {
			if field.required {
				problems = append(problems, field.name+" is unknown")
			}
			continue
		}
		if *field.value < 0 {
			problems = append(problems, field.name+" is negative")
		}
	}
	if len(problems) > 0 {
		return problems
	}
	// Subtraction avoids overflow when cache buckets contain malformed counts.
	if *u.CachedInput > *u.Input || *u.CacheWrite > *u.Input-*u.CachedInput {
		problems = append(problems, "cache reads and writes exceed input")
	}
	if u.Reasoning != nil && *u.Reasoning > *u.Output {
		problems = append(problems, "reasoning exceeds output")
	}
	return problems
}
