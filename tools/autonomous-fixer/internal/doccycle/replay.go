package doccycle

import (
	"context"
	"errors"
	"reflect"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func ReplayNoChangeAssessment(ctx context.Context, req workcycle.Request, d workcycle.Driver, seed workcycle.Report) (workcycle.Report, error) {
	reject := func(e error) (workcycle.Report, error) {
		r := clone(seed)
		r.Status = "rejected"
		r.Error = e.Error()
		if se := d.Save(context.WithoutCancel(ctx), r); se != nil {
			e = errors.Join(e, se)
			r.Error = e.Error()
		}
		return r, e
	}
	route, e := entryRoute(req.DocumentationEntry)
	if e != nil {
		return reject(e)
	}
	if route != string(AssessStage) {
		return reject(errors.New("assessment replay requires an assessor entry"))
	}
	if !reflect.DeepEqual(req, seed.Request) || len(seed.Observations) != 1 {
		return reject(errors.New("assessment replay requires the same request and exactly one observation"))
	}
	o := seed.Observations[0]
	if o.Stage != AssessStage || o.Before.ID == "" || o.Before.ID != o.After.ID || len(o.Before.ChangedPaths) != 0 || len(o.After.ChangedPaths) != 0 || seed.Candidate.ID != o.After.ID || len(seed.Candidate.ChangedPaths) != 0 {
		return reject(errors.New("assessment replay requires an unchanged clean assessment snapshot"))
	}
	doc := o.Declaration.Documentation
	if e := validateDecision(doc); e != nil {
		return reject(e)
	}
	switch doc.Decision {
	case "no_change":
	default:
		return reject(errors.New("assessment replay requires a no-change decision"))
	}
	current, e := d.Snapshot(ctx)
	if e != nil {
		return reject(e)
	}
	if current.ID != o.After.ID || len(current.ChangedPaths) != 0 {
		return reject(errors.New("assessment replay workspace differs from checkpoint"))
	}
	return Run(ctx, req, &assessmentReplay{Driver: d, response: clone(o.Declaration)})
}

type assessmentReplay struct {
	workcycle.Driver
	response workcycle.Response
	used     bool
}

func (d *assessmentReplay) Run(_ context.Context, i workcycle.Invocation) (workcycle.Response, error) {
	if d.used || i.Stage != AssessStage {
		return workcycle.Response{}, errors.New("assessment replay requested an additional stage")
	}
	d.used = true
	return clone(d.response), nil
}
