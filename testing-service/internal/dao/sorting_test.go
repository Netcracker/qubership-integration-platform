package dao

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"github.com/uptrace/bun"
	"github.com/uptrace/bun/dialect/pgdialect"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

// renderSorted returns the SQL AddSorting produced for one set of options.
func renderSorted(t *testing.T, sorting model.SortOptions, sortingFields *[]string) string {
	t.Helper()
	db := bun.NewDB(nil, pgdialect.New())
	query, err := AddSorting(db.NewSelect().Table("t"), sorting, sortingFields)
	require.NoError(t, err)
	sql, err := query.AppendQuery(db.Formatter(), nil)
	require.NoError(t, err)
	return string(sql)
}

func TestGetSqlSortingOrderPlacesNullsLast(t *testing.T) {
	assert.Equal(t, "ASC NULLS LAST", GetSqlSortingOrder(model.OrderAscending))
	assert.Equal(t, "DESC NULLS FIRST", GetSqlSortingOrder(model.OrderDescending))
}

func TestGetSqlSortingOrderReadsTheOrderInAnyCase(t *testing.T) {
	// The order arrives straight off the query string, where "asc" is as common
	// as "ASC".
	assert.Equal(t, "ASC NULLS LAST", GetSqlSortingOrder("asc"))
	assert.Equal(t, "DESC NULLS FIRST", GetSqlSortingOrder("desc"))
	assert.Equal(t, "DESC NULLS FIRST", GetSqlSortingOrder(" Desc "))
}

func TestGetSqlSortingOrderFallsBackToAscending(t *testing.T) {
	// The rendered order is interpolated into the clause, so an order nothing
	// validated must not reach it verbatim.
	assert.Equal(t, "ASC NULLS LAST", GetSqlSortingOrder("sideways"))
	assert.Equal(t, "ASC NULLS LAST", GetSqlSortingOrder("ASC, (select 1)"))
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

func TestValidateSortOptionsAcceptsALowercaseOrder(t *testing.T) {
	// The API took the order verbatim before it was validated, so a client that
	// sends "asc" has to keep working.
	require.NoError(t, ValidateSortOptions(model.SortOptions{By: "id", Order: "asc"}, &[]string{"id"}))
	require.NoError(t, ValidateSortOptions(model.SortOptions{By: "id", Order: "desc"}, &[]string{"id"}))
}

func TestSortOptionRejectionsAreReportedAsABadRequest(t *testing.T) {
	fields := &[]string{"id"}

	assert.ErrorIs(t, ValidateSortOptions(model.SortOptions{By: "secret", Order: "ASC"}, fields),
		ErrInvalidSelection)
	assert.ErrorIs(t, ValidateSortOptions(model.SortOptions{By: "id", Order: "sideways"}, fields),
		ErrInvalidSelection)
}

func TestAddSortingReadsALowercaseOrder(t *testing.T) {
	assert.Equal(t, `SELECT * FROM "t" ORDER BY "id" DESC NULLS FIRST`,
		renderSorted(t, model.SortOptions{By: "id", Order: "desc"}, &[]string{"id"}))
}

func TestValidateSortOptionsRejectsAnUnknownOrder(t *testing.T) {
	err := ValidateSortOptions(model.SortOptions{By: "id", Order: "sideways"}, &[]string{"id"})

	require.Error(t, err)
	assert.ErrorContains(t, err, "sideways")
}

func TestValidateSortOptionsRejectsAMissingOrder(t *testing.T) {
	require.Error(t, ValidateSortOptions(model.SortOptions{By: "id"}, &[]string{"id"}))
}

func TestAddSortingOrdersByTheRequestedField(t *testing.T) {
	fields := &[]string{"id", "name"}

	assert.Equal(t, `SELECT * FROM "t" ORDER BY "name" ASC NULLS LAST`,
		renderSorted(t, model.SortOptions{By: "name", Order: model.OrderAscending}, fields))
	assert.Equal(t, `SELECT * FROM "t" ORDER BY "id" DESC NULLS FIRST`,
		renderSorted(t, model.SortOptions{By: "id", Order: model.OrderDescending}, fields))
}

func TestAddSortingAddsNoOrderWithoutAField(t *testing.T) {
	assert.Equal(t, `SELECT * FROM "t"`,
		renderSorted(t, model.SortOptions{Order: model.OrderAscending}, &[]string{"id"}))
}

func TestAddSortingRejectsAFieldTheListingDoesNotDeclare(t *testing.T) {
	// The field is interpolated into the ORDER BY clause rather than bound as an
	// argument, so the listing's own field list is what keeps a value straight off
	// the query string out of the statement.
	db := bun.NewDB(nil, pgdialect.New())

	query, err := AddSorting(
		db.NewSelect().Table("t"),
		model.SortOptions{By: "id; drop table test_cases", Order: model.OrderAscending},
		&[]string{"id", "name"},
	)

	require.Error(t, err)
	assert.Nil(t, query)
	assert.ErrorContains(t, err, "drop table test_cases")
}

func TestAddSortingRejectsAnOrderItCannotTranslate(t *testing.T) {
	// The order reaches the ORDER BY clause as text, the same way the field does,
	// so the validation is what keeps a value off it.
	db := bun.NewDB(nil, pgdialect.New())

	query, err := AddSorting(
		db.NewSelect().Table("t"),
		model.SortOptions{By: "id", Order: "ASC, (select 1)"},
		&[]string{"id"},
	)

	require.Error(t, err)
	assert.Nil(t, query)
}

// Every listing has to declare fields to sort by; what the published spec says
// about them is checked against these tables in the controllers package.
func TestEveryListingDeclaresSortingFields(t *testing.T) {
	listings := map[string]*[]string{
		"test cases":     GetTestCasesSortingFields(),
		"endpoint mocks": GetEndpointMocksSortingFields(),
		"test runs":      GetTestsRunsSortingFields(),
		"test case runs": GetTestCaseRunsSortingFields(),
	}
	for name, fields := range listings {
		t.Run(name, func(t *testing.T) {
			assert.NotEmpty(t, *fields)
		})
	}
}
