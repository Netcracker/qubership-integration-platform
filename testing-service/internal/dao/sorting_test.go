package dao

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

func TestGetSqlSortingOrderPlacesNullsLast(t *testing.T) {
	assert.Equal(t, "ASC NULLS LAST", GetSqlSortingOrder(model.OrderAscending))
	assert.Equal(t, "DESC NULLS FIRST", GetSqlSortingOrder(model.OrderDescending))
}

func TestGetSqlSortingOrderPassesAnythingElseThrough(t *testing.T) {
	assert.Equal(t, "sideways", GetSqlSortingOrder("sideways"))
}

func TestValidateSortOptionsAcceptsAKnownField(t *testing.T) {
	fields := &[]string{"id", "name"}

	require.NoError(t, ValidateSortOptions(model.SortOptions{By: "name", Order: model.OrderAscending}, fields))
	require.NoError(t, ValidateSortOptions(model.SortOptions{By: "id", Order: model.OrderDescending}, fields))
}

func TestValidateSortOptionsAcceptsNoFieldAtAll(t *testing.T) {
	require.NoError(t, ValidateSortOptions(model.SortOptions{Order: model.OrderAscending}, &[]string{"id"}))
}

func TestValidateSortOptionsRejectsAnUnknownField(t *testing.T) {
	err := ValidateSortOptions(model.SortOptions{By: "secret", Order: model.OrderAscending}, &[]string{"id", "name"})

	require.Error(t, err)
	assert.ErrorContains(t, err, "secret")
	assert.ErrorContains(t, err, "id, name")
}

func TestValidateSortOptionsRejectsAnUnknownOrder(t *testing.T) {
	err := ValidateSortOptions(model.SortOptions{By: "id", Order: "sideways"}, &[]string{"id"})

	require.Error(t, err)
	assert.ErrorContains(t, err, "sideways")
}

func TestValidateSortOptionsRejectsAMissingOrder(t *testing.T) {
	require.Error(t, ValidateSortOptions(model.SortOptions{By: "id"}, &[]string{"id"}))
}

func TestEveryListingAcceptsItsOwnSortingFields(t *testing.T) {
	listings := map[string]*[]string{
		"test cases":     GetTestCasesSortingFields(),
		"endpoint mocks": GetEndpointMocksSortingFields(),
		"test runs":      GetTestsRunsSortingFields(),
		"test case runs": GetTestCaseRunsSortingFields(),
	}
	for name, fields := range listings {
		t.Run(name, func(t *testing.T) {
			require.NotEmpty(t, *fields)
			for _, field := range *fields {
				require.NoError(t, ValidateSortOptions(model.SortOptions{By: field, Order: model.OrderAscending}, fields))
			}
		})
	}
}
