package docgithub

import "errors"

type PublicationFailure string

const (
	PublicationFailedCandidate   PublicationFailure = "validate-candidate"
	PublicationFailedAccess      PublicationFailure = "authorize-publisher"
	PublicationFailedReadState   PublicationFailure = "read-state"
	PublicationFailedSaveState   PublicationFailure = "save-state"
	PublicationFailedDraftPR     PublicationFailure = "publish-draft-pr"
	PublicationFailedIssueClose  PublicationFailure = "close-issue"
	PublicationFailedProjectDone PublicationFailure = "set-project-done"
)

type publicationError struct {
	operation PublicationFailure
	cause     error
}

func (e *publicationError) Error() string { return e.cause.Error() }
func (e *publicationError) Unwrap() error { return e.cause }

func publicationFailed(operation PublicationFailure, err error) error {
	if err == nil {
		return nil
	}
	return &publicationError{operation: operation, cause: err}
}

func PublicationFailureOf(err error) PublicationFailure {
	var failure *publicationError
	if errors.As(err, &failure) {
		return failure.operation
	}
	return ""
}
