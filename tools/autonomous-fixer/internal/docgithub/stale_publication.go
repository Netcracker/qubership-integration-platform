package docgithub

import (
	"context"
	"errors"
	"fmt"
)

func (p *publisher) recoverStalePublication(ctx context.Context) (Publication, error) {
	if p.state.PRNumber > 0 {
		return p.reconcilePR(ctx, true, nil)
	}
	var state Publication
	var err error
	if p.state.Decision == "needed" {
		state, err = p.recoverInterruptedPR(ctx, true)
	} else {
		var current Current
		current, err = Revalidate(ctx, p.client, p.plan)
		state = p.state
		if errors.Is(err, errStaleBase) {
			state, err = p.retireStalePublication(ctx, current, true)
		}
	}
	if err == nil && (state.Status == "superseded" || state.Status == "publishing" || state.Status == "completing") {
		err = errStaleBase
	}
	return state, err
}

func (p *publisher) supersedeStale(ctx context.Context, notice string) (bool, error) {
	if !p.canSupersede() {
		return false, nil
	}
	head, err := p.client.head(ctx, p.plan.DefaultBranch)
	if err != nil || head == p.plan.BaseSHA {
		return false, err
	}
	current, err := p.revalidate(ctx, "")
	if !errors.Is(err, errStaleBase) {
		return false, err
	}
	state, err := p.retireStalePublication(ctx, current, false)
	if errors.Is(err, errPublicationEffects) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	if notice != "" {
		state.IssueComment = notice + " " + state.IssueComment
	}
	return true, p.saveState(ctx, state)
}

var errPublicationEffects = errors.New("documentation publication has existing or unresolved effects")

func (p *publisher) canSupersede() bool {
	state := p.state
	return (state.Status == "publishing" || state.Status == "completing") && state.PRNumber == 0 &&
		(state.Decision == "needed" || state.Decision == "no_change") &&
		!state.CloseAttempted && !state.ProjectDoneAttempted && !state.PRAttempted
}

func (p *publisher) retireStalePublication(ctx context.Context, current Current, apply bool) (Publication, error) {
	if p.state.Decision == "needed" {
		number, err := p.findDocumentationPR(ctx)
		if err != nil {
			return p.state, err
		}
		if number > 0 {
			return p.state, fmt.Errorf("pull request #%d already exists for this plan; reconcile it first: %w", number, errPublicationEffects)
		}
	}
	return p.retireStale(ctx, current, apply)
}

func (p *publisher) retireStale(ctx context.Context, current Current, apply bool) (Publication, error) {
	state := p.state
	if state.PRAttempted {
		return state, fmt.Errorf("pull request creation for branch %s is unresolved; if GitHub shows the pull request, run reconciliation again: %w", p.documentationBranch(), errPublicationEffects)
	}
	if !p.canSupersede() || current.IssueState != state.InitialIssueState || current.ProjectStatus != p.plan.Project.ReviewOptionID {
		return state, fmt.Errorf("reconcile the issue, project, and any attempted publication writes before starting another plan: %w", errPublicationEffects)
	}
	if state.Decision == "needed" {
		branch := p.documentationBranch()
		head, err := p.client.head(ctx, branch)
		if err == nil {
			if head != state.CommitSHA {
				return state, fmt.Errorf("branch %s has an unexpected head; check the recorded commit and the branch's GitHub history: %w", branch, errPublicationEffects)
			}
			if apply && state.BranchWrite != branchObserved && state.BranchWrite != legacyObserved {
				if state.BranchWrite == "" {
					state.BranchWrite = legacyObserved
				} else {
					state.BranchWrite = branchObserved
				}
				if err := p.saveState(ctx, state); err != nil {
					return p.state, err
				}
			}
			return state, fmt.Errorf("%w. Branch %s still exists for the stale assessment. Check its GitHub pull request history. If you confirm no pull request exists and decide to remove the branch, run reconciliation again after removing it", errPublicationEffects, branch)
		}
		if !notFound(err) {
			return state, err
		}
		if state.BranchWrite == branchAttempted || (state.BranchWrite == "" && state.CommitSHA != "") {
			return state, fmt.Errorf("creation of branch %s was not confirmed; inspect its GitHub history: %w", branch, errPublicationEffects)
		}
	}
	state.Status = "superseded"
	state.IssueComment = staleAssessment
	if apply {
		if err := p.saveState(ctx, state); err != nil {
			return p.state, err
		}
	}
	return state, nil
}

func (p *publisher) stalePublicationNotice(ctx context.Context) string {
	head, err := p.client.head(ctx, p.plan.DefaultBranch)
	if err != nil || head == p.plan.BaseSHA {
		return ""
	}
	base := "The default branch changed after documentation review. "
	const reconcile = "Run **QIP documentation agent** with `operation` set to `reconcile` and `apply` enabled."
	number, err := p.findDocumentationPR(ctx)
	if err == nil && number > 0 {
		return fmt.Sprintf("%sPull request #%d may belong to this publication. %s", base, number, reconcile)
	}
	branch := p.documentationBranch()
	if p.state.PRAttempted {
		return fmt.Sprintf("%sCheck the pull request history for branch `%s`. If GitHub shows the draft, use `reconcile` with `apply` enabled.", base, branch)
	}
	return fmt.Sprintf("%sCheck branch `%s` and its pull request history. %s", base, branch, reconcile)
}
