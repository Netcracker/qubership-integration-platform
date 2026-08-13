package dao

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

func testConfiguration() *model.FilterConfiguration {
	return &model.FilterConfiguration{
		"id":         GetIdFilterConfiguration("t.id"),
		"name":       GetStringFilterConfiguration("t.name"),
		"enabled":    GetBooleanFilterConfiguration("t.enabled"),
		"status":     GetEnumFilterConfiguration("t.status"),
		"delay":      GetIntegerFilterConfiguration("t.delay"),
		"created_at": GetTimestampFilterConfiguration("t.created_at"),
	}
}

func filter(feature, condition string, values ...string) model.Filter {
	return model.Filter{Feature: feature, Condition: condition, Values: values}
}

func TestValidateFiltersAcceptsNothingToValidate(t *testing.T) {
	require.NoError(t, ValidateFilters(nil, testConfiguration()))
	require.NoError(t, ValidateFilters(&[]model.Filter{}, testConfiguration()))
}

func TestValidateFiltersReportsTheFirstBadFilter(t *testing.T) {
	filters := []model.Filter{
		filter("name", model.ConditionContains, "order"),
		filter("delay", model.ConditionIs, "soon"),
		filter("unknown", model.ConditionIs, "x"),
	}

	err := ValidateFilters(&filters, testConfiguration())

	require.Error(t, err)
	assert.ErrorContains(t, err, "soon")
}

func TestValidateFilterRejectsAnUnknownFeature(t *testing.T) {
	err := ValidateFilter(filter("secret", model.ConditionIs, "x"), testConfiguration())

	require.Error(t, err)
	assert.ErrorContains(t, err, "secret")
	// The features are listed in a stable order, so the message does not change
	// between runs.
	assert.ErrorContains(t, err, "created_at, delay, enabled, id, name, status")
}

func TestValidateFilterRejectsAConditionTheFeatureDoesNotSupport(t *testing.T) {
	err := ValidateFilter(filter("status", model.ConditionContains, "fin"), testConfiguration())

	require.Error(t, err)
	assert.ErrorContains(t, err, model.ConditionContains)
	assert.ErrorContains(t, err, "status")
}

func TestValidateFilterChecksTheValueCount(t *testing.T) {
	cases := []struct {
		name    string
		filter  model.Filter
		wantErr bool
	}{
		{"is takes one value", filter("name", model.ConditionIs, "orders"), false},
		{"is rejects two values", filter("name", model.ConditionIs, "orders", "invoices"), true},
		{"is rejects no value", filter("name", model.ConditionIs), true},
		{"in takes one value", filter("status", model.ConditionIn, "finished"), false},
		{"in takes several values", filter("status", model.ConditionIn, "finished", "skipped"), false},
		{"in rejects no value", filter("status", model.ConditionIn), true},
		{"is_within takes two values", filter("created_at", model.ConditionIsWithin,
			"2026-08-13 10:00:00.000 +0000", "2026-08-13 11:00:00.000 +0000"), false},
		{"is_within rejects one value", filter("created_at", model.ConditionIsWithin,
			"2026-08-13 10:00:00.000 +0000"), true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			err := ValidateFilter(tc.filter, testConfiguration())
			if tc.wantErr {
				require.Error(t, err)
				return
			}
			require.NoError(t, err)
		})
	}
}

func TestValidateFilterConvertsTheValueToTheColumnType(t *testing.T) {
	cases := []struct {
		name    string
		filter  model.Filter
		wantErr bool
	}{
		{"a uuid is accepted", filter("id", model.ConditionIs, "6ba7b810-9dad-11d1-80b4-00c04fd430c8"), false},
		{"a non-uuid is rejected", filter("id", model.ConditionIs, "not-a-uuid"), true},
		{"an integer is accepted", filter("delay", model.ConditionIs, "500"), false},
		{"a non-integer is rejected", filter("delay", model.ConditionIs, "soon"), true},
		{"a boolean is accepted", filter("enabled", model.ConditionIs, "true"), false},
		{"a non-boolean is rejected", filter("enabled", model.ConditionIs, "maybe"), true},
		{"a timestamp is accepted", filter("created_at", model.ConditionIsAfter, "2026-08-13 10:00:00.000 +0000"), false},
		{"a non-timestamp is rejected", filter("created_at", model.ConditionIsAfter, "yesterday"), true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			err := ValidateFilter(tc.filter, testConfiguration())
			if tc.wantErr {
				require.Error(t, err)
				assert.ErrorContains(t, err, tc.filter.Values[0])
				return
			}
			require.NoError(t, err)
		})
	}
}

func TestValidateFilterSkipsConversionForASubstringSearch(t *testing.T) {
	// A substring search runs against the column cast to text, so a fragment of
	// a uuid is a legitimate value even though it does not parse as one.
	for _, condition := range []string{
		model.ConditionContains,
		model.ConditionDoesNotContain,
		model.ConditionStartsWith,
		model.ConditionEndsWith,
	} {
		t.Run(condition, func(t *testing.T) {
			require.NoError(t, ValidateFilter(filter("id", condition, "6ba7b8"), testConfiguration()))
		})
	}
}

func TestValidateFilterStillConvertsForAnExactMatchOnATextSearchableColumn(t *testing.T) {
	require.Error(t, ValidateFilter(filter("id", model.ConditionIs, "6ba7b8"), testConfiguration()))
}

func TestHasFeatureFilterFindsTheFeature(t *testing.T) {
	filters := []model.Filter{filter("chain_id", model.ConditionIs, "chain-1")}
	specification := &model.SelectionSpecification{Filters: &filters}

	assert.True(t, HasFeatureFilter(specification, "chain_id"))
	assert.False(t, HasFeatureFilter(specification, "element_id"))
}

func TestHasFeatureFilterToleratesAnAbsentSpecification(t *testing.T) {
	assert.False(t, HasFeatureFilter(nil, "chain_id"))
	assert.False(t, HasFeatureFilter(&model.SelectionSpecification{}, "chain_id"))
}

func TestGetExpectedValueCountCoversEveryCondition(t *testing.T) {
	cases := map[string]int{
		model.ConditionIs:             1,
		model.ConditionIsNot:          1,
		model.ConditionContains:       1,
		model.ConditionDoesNotContain: 1,
		model.ConditionStartsWith:     1,
		model.ConditionEndsWith:       1,
		model.ConditionIn:             valueCountOneOrMore,
		model.ConditionNotIn:          valueCountOneOrMore,
		model.ConditionEmpty:          0,
		model.ConditionNotEmpty:       0,
		model.ConditionIsAfter:        1,
		model.ConditionIsBefore:       1,
		model.ConditionIsWithin:       2,
		model.ConditionLessThan:       1,
		model.ConditionGreaterThan:    1,
	}
	for condition, want := range cases {
		assert.Equal(t, want, getExpectedValueCount(condition), condition)
	}
}

func TestSortedFeaturesIsStable(t *testing.T) {
	want := []string{"created_at", "delay", "enabled", "id", "name", "status"}

	for range 5 {
		assert.Equal(t, want, sortedFeatures(testConfiguration()))
	}
}

func TestEveryListingValidatesItsOwnFilterFeatures(t *testing.T) {
	configurations := map[string]*model.FilterConfiguration{
		"test cases":     GetTestCasesFilterConfiguration(),
		"endpoint mocks": GetEndpointMocksFilterConfiguration(),
		"test runs":      GetTestsRunsFilterConfiguration(),
		"test case runs": GetTestCaseRunsFilterConfiguration(),
	}
	for name, configuration := range configurations {
		t.Run(name, func(t *testing.T) {
			for feature, featureConfiguration := range *configuration {
				require.NotEmpty(t, featureConfiguration.Column, feature)
				require.NotEmpty(t, featureConfiguration.Conditions, feature)
				require.NotNil(t, featureConfiguration.Converter, feature)
			}
		})
	}
}
