package checkrunner

import (
	"context"
	"encoding/json"
	"os"
	"reflect"
	"testing"
)

func TestProductObservationIDsSurviveRecordSerialization(t *testing.T) {
	record, err := RunProduct(context.Background(), productOptions(t), productEnvironment("pass"))
	if err != nil {
		t.Fatal(err)
	}
	before, err := ProductObservations(record)
	if err != nil || len(before) != 1 {
		t.Fatal(before, err)
	}
	data, err := os.ReadFile(record.Path)
	if err != nil {
		t.Fatal(err)
	}
	var archived ProductRecord
	if err = json.Unmarshal(data, &archived); err != nil {
		t.Fatal(err)
	}
	after, err := ProductObservations(archived)
	if err != nil || !reflect.DeepEqual(before, after) {
		t.Fatal("record serialization changed observation IDs", before, after, err)
	}
	cli, err := ProductResultJSON(record)
	if err != nil {
		t.Fatal(err)
	}
	var summary struct {
		Observations []ProductObservation `json:"observations"`
	}
	if err = json.Unmarshal(cli, &summary); err != nil || !reflect.DeepEqual(before, summary.Observations) {
		t.Fatal("CLI observations differ from captured measurements", string(cli), err)
	}
}

func TestRejectedCaptureHasNoCLIObservations(t *testing.T) {
	record, err := RunProduct(context.Background(), productOptions(t), productEnvironment("timeout"))
	if err == nil {
		t.Fatal("timeout accepted")
	}
	cli, err := ProductResultJSON(record)
	if err != nil {
		t.Fatal(err)
	}
	var summary struct {
		Observations []ProductObservation `json:"observations"`
	}
	if err = json.Unmarshal(cli, &summary); err != nil || len(summary.Observations) != 0 {
		t.Fatal("rejected capture produced selectable observations", string(cli), err)
	}
}
