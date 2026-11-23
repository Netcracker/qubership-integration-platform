package localwork

import (
	"context"
	"errors"
	"fmt"
	"sync"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func (d *Driver) ReviewParallelism() int {
	return d.Config.ReviewParallelism
}

func (d *Driver) AssessReviewBatch(ctx context.Context, inputs []workcycle.AgentAssessmentRequest) (results []workcycle.AgentAssessment, err error) {
	d.reviewFailureArchive = ""
	if err = validateReviewParallelism(d.Config); err != nil {
		return nil, err
	}
	if d.ReviewParallelism() == 0 {
		return nil, fmt.Errorf("read-only review is disabled")
	}
	if len(inputs) == 0 {
		return nil, nil
	}
	candidate := inputs[0].Candidate
	for _, input := range inputs {
		if input.Stage != workcycle.ReviewStage || input.Candidate.ID != candidate.ID {
			return nil, fmt.Errorf("a read-only review batch requires one candidate and review-stage inputs")
		}
		if err = d.validateAgentContext(input.Request); err != nil {
			return nil, err
		}
	}
	view, err := d.prepareReadOnlyReview(ctx, candidate)
	if err != nil {
		return nil, err
	}
	defer func() {
		err = errors.Join(err, view.cleanup())
	}()
	type reviewRun struct {
		directory string
		evidence  string
		worker    Driver
		err       error
	}
	runs := make([]reviewRun, len(inputs))
	for i := range runs {
		run := &runs[i]
		run.directory, run.evidence, err = d.agentStep(string(workcycle.ReviewStage))
		if err != nil {
			return nil, err
		}
		run.worker = Driver{Config: d.Config, evidence: d.evidence, base: d.base}
	}
	results = make([]workcycle.AgentAssessment, len(inputs))
	var group sync.WaitGroup
	slots := make(chan struct{}, d.ReviewParallelism())
	for i, input := range inputs {
		group.Go(func() {
			run := &runs[i]
			select {
			case slots <- struct{}{}:
				defer func() { <-slots }()
			case <-ctx.Done():
				run.err = ctx.Err()
				return
			}
			results[i], run.err = run.worker.assessAgentStep(ctx, input, run.directory, run.evidence, view)
		})
	}
	group.Wait()
	if d.artifacts == nil {
		d.artifacts = make(map[string]string)
	}
	failed := -1
	for i, run := range runs {
		for path, digest := range run.worker.artifacts {
			d.artifacts[path] = digest
		}
		if run.err != nil {
			if failed < 0 {
				failed = i
			}
			err = errors.Join(err, fmt.Errorf("review %s: %w", inputs[i].Lens, run.err))
		}
	}
	if failed < 0 {
		for i, result := range results {
			if result.Decision == "verification-blocked" || result.Decision == "needs-human" {
				failed = i
				break
			}
		}
	}
	if failed >= 0 {
		d.reviewFailureArchive = runs[failed].directory
		d.failureKind = runs[failed].worker.failureKind
	}
	return results, err
}
