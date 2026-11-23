package fixer

import "fmt"

func RevisionEligible(state *State, issue Issue) bool {
	return state != nil && state.HostedPublication != nil && state.HostedCandidate != nil &&
		state.Desired == "running" && state.Phase == "waiting-ci" && issue.Number == state.IssueNumber &&
		issue.State == "open" && issue.ReadyForAgent() && state.Contract.matchesRequirements(issue.Requirements())
}

func RevisionCheckpoint(state *State, issue Issue, head string, run RunIdentity, command int64, assignmentSHA string) (Checkpoint, error) {
	if !RevisionEligible(state, issue) {
		return Checkpoint{}, fmt.Errorf("the original engineering assignment no longer authorizes work")
	}
	if !commitPattern.MatchString(head) || !digestPattern.MatchString(assignmentSHA) || run.RunID <= 0 || run.RunAttempt != 1 || command <= 0 {
		return Checkpoint{}, fmt.Errorf("invalid revision checkpoint identity")
	}
	contract := state.Contract
	contract.BaseSHA = head
	attempt := fmt.Sprintf("revision-%d", command)
	return Checkpoint{
		SchemaVersion: SchemaVersion, Mode: "hosted", Repository: state.Repository,
		TaskID: fmt.Sprintf("%s-revise-%d", state.TaskID, command), IssueNumber: state.IssueNumber,
		AttemptID: attempt, ExecutionID: attempt, Generation: state.Generation, ContractVersion: contract.Version,
		Run: run, TrustedSHA: state.Contract.TrustedSHA, Contract: contract, Answers: hostedAnswers(state),
		RevisionSHA256: assignmentSHA,
	}, nil
}
