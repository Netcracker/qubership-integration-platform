package checkrunner

import (
	"encoding/json"
	"fmt"
	"path/filepath"
)

type ProductObservation struct {
	ID        string   `json:"id"`
	Outcome   string   `json:"outcome"`
	Name      string   `json:"name"`
	FullName  string   `json:"full_name"`
	Project   string   `json:"project,omitempty"`
	File      string   `json:"file,omitempty"`
	Capture   string   `json:"capture"`
	Evidence  []string `json:"evidence"`
	TestIndex int      `json:"test_index"`
}

type ProductObservationError struct {
	Cause error
	ids   map[string]bool
}

func (e *ProductObservationError) Error() string             { return e.Cause.Error() }
func (e *ProductObservationError) Unwrap() error             { return e.Cause }
func (e *ProductObservationError) References(id string) bool { return e.ids[id] }

func ProductObservations(record ProductRecord) ([]ProductObservation, error) {
	identity, err := json.Marshal(record)
	if err != nil {
		return nil, err
	}
	ids := map[string]bool{}
	if record.Summary != nil {
		for index := range record.Summary.Tests {
			ids[productObservationID(identity, index)] = true
		}
	}
	if err := ValidateProductCapture(record, record.Options.OutputDirectory); err != nil {
		return nil, &ProductObservationError{Cause: err, ids: ids}
	}
	capture := filepath.Join(record.Options.OutputDirectory, "result.json")
	observations := make([]ProductObservation, 0, len(record.Summary.Tests))
	for index, test := range record.Summary.Tests {
		observations = append(observations, ProductObservation{
			ID: productObservationID(identity, index), Outcome: test.Outcome, Name: test.Name,
			FullName: test.FullName, Project: test.Project, File: test.File,
			Capture: capture, Evidence: []string{capture, record.ReportCopy}, TestIndex: index,
		})
	}
	return observations, nil
}

func productObservationID(identity []byte, index int) string {
	id := digest(append(append([]byte(nil), identity...), []byte(fmt.Sprintf("\x00%d", index))...))
	return "product-observation-v1:" + id
}
