package dao

import (
	"strings"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"github.com/uptrace/bun"
	"github.com/uptrace/bun/dialect/pgdialect"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

func testConfiguration() *model.FilterConfiguration {
	return &model.FilterConfiguration{
		"id":         GetIdFilterConfiguration("t.id"),
		"name":       GetStringFilterConfiguration("t.name"),
		"enabled":    GetBooleanFilterConfiguration("t.enabled"),
		"status":     GetEnumFilterConfiguration("t.status", RunStatuses),
		"delay":      GetIntegerFilterConfiguration("t.delay"),
		"created_at": GetTimestampFilterConfiguration("t.created_at"),
	}
}

func filter(feature, condition string, values ...string) model.Filter {
	return model.Filter{Feature: feature, Condition: condition, Values: values}
}

// renderSelect applies build to a real bun select over table "t" and returns the
// SQL it produced. bun renders arguments inline, so the assertions can name the
// literal values that reached the statement.
func renderSelect(t *testing.T, build func(bun.QueryBuilder) (bun.QueryBuilder, error)) string {
	t.Helper()
	db := bun.NewDB(nil, pgdialect.New())
	var failure error
	query := db.NewSelect().Table("t").ApplyQueryBuilder(func(builder bun.QueryBuilder) bun.QueryBuilder {
		narrowed, err := build(builder)
		if err != nil {
			failure = err
			return builder
		}
		return narrowed
	})
	require.NoError(t, failure)
	sql, err := query.AppendQuery(db.Formatter(), nil)
	require.NoError(t, err)
	return string(sql)
}

// renderFilter is renderSelect for a single filter.
func renderFilter(t *testing.T, f model.Filter) string {
	t.Helper()
	return renderFilterWith(t, f, testConfiguration())
}

// renderFilterWith is renderFilter over a configuration of the caller's choosing.
func renderFilterWith(t *testing.T, f model.Filter, configuration *model.FilterConfiguration) string {
	t.Helper()
	return renderSelect(t, func(builder bun.QueryBuilder) (bun.QueryBuilder, error) {
		return AddFilters(builder, &[]model.Filter{f}, configuration)
	})
}

// nullableConfiguration declares the two conditions no shipped listing declares,
// so the predicates behind them stay covered.
func nullableConfiguration() *model.FilterConfiguration {
	configuration := model.FilterConfiguration{
		"name": {
			Column:     "t.name",
			Conditions: []string{model.ConditionEmpty, model.ConditionNotEmpty},
			Converter:  func(s string) (any, error) { return s, nil },
		},
	}
	return &configuration
}

func TestAddFiltersBuildsThePredicateOfEveryCondition(t *testing.T) {
	// A feature is used here for the column type it carries, and every pairing
	// below is one the feature declares: getFilterQueryBuilder refuses the rest.
	cases := []struct {
		filter model.Filter
		want   string
	}{
		{filter("name", model.ConditionIs, "orders"), `"t"."name" = 'orders'`},
		{filter("name", model.ConditionIsNot, "orders"), `"t"."name" != 'orders'`},
		{filter("name", model.ConditionContains, "ord"), `"t"."name" ILIKE '%ord%'`},
		{filter("name", model.ConditionDoesNotContain, "ord"), `"t"."name" NOT ILIKE '%ord%'`},
		{filter("name", model.ConditionStartsWith, "ord"), `"t"."name" ILIKE 'ord%'`},
		{filter("name", model.ConditionEndsWith, "ord"), `"t"."name" ILIKE '%ord'`},
		{filter("status", model.ConditionIn, "finished", "skipped"), `"t"."status" IN ('finished', 'skipped')`},
		{filter("status", model.ConditionNotIn, "finished"), `"t"."status" NOT IN ('finished')`},
		{filter("created_at", model.ConditionIsAfter, "2026-08-13 10:00:00.000 +0000"),
			`"t"."created_at" > '2026-08-13 10:00:00+00:00'`},
		{filter("created_at", model.ConditionIsBefore, "2026-08-13 10:00:00.000 +0000"),
			`"t"."created_at" < '2026-08-13 10:00:00+00:00'`},
		{filter("created_at", model.ConditionIsWithin,
			"2026-08-13 10:00:00.000 +0000", "2026-08-13 11:00:00.000 +0000"),
			`"t"."created_at" BETWEEN '2026-08-13 10:00:00+00:00' AND '2026-08-13 11:00:00+00:00'`},
		{filter("delay", model.ConditionGreaterThan, "500"), `"t"."delay" > 500`},
		{filter("delay", model.ConditionLessThan, "500"), `"t"."delay" < 500`},
	}
	for _, tc := range cases {
		t.Run(tc.filter.Condition+" on "+tc.filter.Feature, func(t *testing.T) {
			assert.Contains(t, renderFilter(t, tc.filter), tc.want)
		})
	}
}

// No shipped listing declares empty or not_empty, and a feature that does not
// declare a condition is now refused, so the predicates behind the two are
// rendered over a configuration that declares them.
func TestAddFiltersBuildsTheNullPredicates(t *testing.T) {
	assert.Contains(t, renderFilterWith(t, filter("name", model.ConditionEmpty), nullableConfiguration()),
		`"t"."name" IS NULL`)
	assert.Contains(t, renderFilterWith(t, filter("name", model.ConditionNotEmpty), nullableConfiguration()),
		`"t"."name" IS NOT NULL`)
}

func TestAddFiltersConvertsTheValueToTheColumnType(t *testing.T) {
	// A uuid and a boolean reach the statement as themselves, not as the strings
	// the request carried them in.
	assert.Contains(t,
		renderFilter(t, filter("id", model.ConditionIs, "6ba7b810-9dad-11d1-80b4-00c04fd430c8")),
		`"t"."id" = '6ba7b810-9dad-11d1-80b4-00c04fd430c8'`)
	assert.Contains(t, renderFilter(t, filter("enabled", model.ConditionIs, "true")), `"t"."enabled" = TRUE`)
}

func TestAddFiltersCastsATextSearchableColumnToText(t *testing.T) {
	// A uuid column has no ILIKE operator of its own, so a substring search runs
	// against its text rendering.
	assert.Contains(t, renderFilter(t, filter("id", model.ConditionContains, "6ba7b8")),
		`"t"."id"::text ILIKE '%6ba7b8%'`)
	assert.Contains(t, renderFilter(t, filter("id", model.ConditionDoesNotContain, "6ba7b8")),
		`"t"."id"::text NOT ILIKE '%6ba7b8%'`)
	assert.Contains(t, renderFilter(t, filter("id", model.ConditionStartsWith, "6ba7b8")),
		`"t"."id"::text ILIKE '6ba7b8%'`)

	// A text column is compared as it is.
	assert.NotContains(t, renderFilter(t, filter("name", model.ConditionContains, "ord")), "::text")
}

func TestAddFiltersEscapesLikeMetacharactersInTheValue(t *testing.T) {
	// Without escaping, a % or an _ the user typed would widen the search to
	// anything, and a trailing backslash would swallow the closing wildcard.
	cases := []struct {
		name   string
		filter model.Filter
		want   string
	}{
		{"percent", filter("name", model.ConditionContains, "50% off"), `ILIKE '%50\% off%'`},
		{"underscore", filter("name", model.ConditionContains, "a_b"), `ILIKE '%a\_b%'`},
		{"backslash", filter("name", model.ConditionContains, `a\b`), `ILIKE '%a\\b%'`},
		{"starts_with", filter("name", model.ConditionStartsWith, "100%"), `ILIKE '100\%%'`},
		{"ends_with", filter("name", model.ConditionEndsWith, "100%"), `ILIKE '%100\%'`},
		{"does_not_contain", filter("name", model.ConditionDoesNotContain, "_"), `NOT ILIKE '%\_%'`},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			assert.Contains(t, renderFilter(t, tc.filter), tc.want)
		})
	}
}

func TestAddFiltersCombinesEveryFilterWithAnd(t *testing.T) {
	sql := renderSelect(t, func(builder bun.QueryBuilder) (bun.QueryBuilder, error) {
		return AddFilters(builder, &[]model.Filter{
			filter("name", model.ConditionContains, "ord"),
			filter("enabled", model.ConditionIs, "true"),
		}, testConfiguration())
	})

	assert.Equal(t, `SELECT * FROM "t" WHERE (("t"."name" ILIKE '%ord%')) AND (("t"."enabled" = TRUE))`, sql)
}

func TestAddFiltersReportsAnUnknownCondition(t *testing.T) {
	// A no-op predicate here would drop the filter and answer with every row in
	// the table, which reads as a successful, much wider search.
	_, err := AddFilters(nil, &[]model.Filter{filter("name", "sounds_like", "ord")}, testConfiguration())

	require.Error(t, err)
	assert.ErrorContains(t, err, "sounds_like")
	assert.ErrorContains(t, err, "name")
}

func TestAddFiltersReportsAnUnknownFeature(t *testing.T) {
	_, err := AddFilters(nil, &[]model.Filter{filter("secret", model.ConditionIs, "x")}, testConfiguration())

	require.Error(t, err)
	assert.ErrorContains(t, err, "secret")
}

func TestAddFiltersReportsAValueTheColumnTypeRejects(t *testing.T) {
	// A discarded conversion error would leave a nil in the WHERE clause, and the
	// request would come back empty instead of wrong.
	_, err := AddFilters(nil, &[]model.Filter{filter("delay", model.ConditionIs, "soon")}, testConfiguration())

	require.Error(t, err)
	assert.ErrorContains(t, err, "soon")
	assert.ErrorContains(t, err, "delay")
}

func TestAddFiltersReportsTheWrongValueCount(t *testing.T) {
	// The predicates index into the converted values, so the count is checked
	// before the switch rather than trusted.
	_, err := AddFilters(nil, &[]model.Filter{filter("created_at", model.ConditionIsWithin,
		"2026-08-13 10:00:00.000 +0000")}, testConfiguration())

	require.Error(t, err)
	assert.ErrorContains(t, err, model.ConditionIsWithin)
}

func TestAddSearchTextSearchesEveryFeatureThatAcceptsContains(t *testing.T) {
	sql := renderSelect(t, func(builder bun.QueryBuilder) (bun.QueryBuilder, error) {
		return AddSearchText(builder, "ord", testConfiguration())
	})

	// id and name accept contains; enabled, status, delay and created_at do not.
	assert.Equal(t,
		`SELECT * FROM "t" WHERE ((("t"."id"::text ILIKE '%ord%')) OR (("t"."name" ILIKE '%ord%')))`,
		sql)
}

func TestAddSearchTextEscapesLikeMetacharacters(t *testing.T) {
	sql := renderSelect(t, func(builder bun.QueryBuilder) (bun.QueryBuilder, error) {
		return AddSearchText(builder, "100%", testConfiguration())
	})

	assert.Contains(t, sql, `'%100\%%'`)
	assert.NotContains(t, sql, `'%100%%'`)
}

func TestAddSearchTextAddsNothingWhenNoFeatureAcceptsContains(t *testing.T) {
	configuration := &model.FilterConfiguration{"status": GetEnumFilterConfiguration("t.status", RunStatuses)}

	sql := renderSelect(t, func(builder bun.QueryBuilder) (bun.QueryBuilder, error) {
		return AddSearchText(builder, "ord", configuration)
	})

	assert.Equal(t, `SELECT * FROM "t"`, sql)
}

func TestAddSpecificationSelectsTheListedIds(t *testing.T) {
	first := uuid.MustParse("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
	second := uuid.MustParse("6ba7b811-9dad-11d1-80b4-00c04fd430c8")
	ids := []uuid.UUID{first, second}

	sql := renderSelect(t, func(builder bun.QueryBuilder) (bun.QueryBuilder, error) {
		return AddSpecification(builder, &model.SelectionSpecification{Ids: &ids}, testConfiguration())
	})

	// The id column comes from the configuration, so the predicate is qualified
	// and survives a join.
	assert.Contains(t, sql, `"t"."id" IN ('`+first.String()+`', '`+second.String()+`')`)
}

// A present but empty id list is a selection of nothing. Leaving the predicate
// out would answer it with every row of the table, which is how an export came
// to dump the catalog and a rerun of no cases came to run every case there is.
func TestAddSpecificationSelectsNothingForAnEmptyIdList(t *testing.T) {
	ids := []uuid.UUID{}

	sql := renderSelect(t, func(builder bun.QueryBuilder) (bun.QueryBuilder, error) {
		return AddSpecification(builder, &model.SelectionSpecification{Ids: &ids}, testConfiguration())
	})

	assert.Equal(t, `SELECT * FROM "t" WHERE ((FALSE))`, sql)
}

// An absent list is the one that puts no id predicate on the query.
func TestAddSpecificationSelectsEverythingWithoutAnIdList(t *testing.T) {
	sql := renderSelect(t, func(builder bun.QueryBuilder) (bun.QueryBuilder, error) {
		return AddSpecification(builder, &model.SelectionSpecification{}, testConfiguration())
	})

	assert.Equal(t, `SELECT * FROM "t"`, sql)
}

// The empty list narrows the query rather than replacing it, so the filters
// alongside it are still translated and a bad one is still reported.
func TestAddSpecificationReportsABadFilterAlongsideAnEmptyIdList(t *testing.T) {
	ids := []uuid.UUID{}
	filters := []model.Filter{filter("name", "sounds_like", "ord")}
	db := bun.NewDB(nil, pgdialect.New())

	_, err := ApplySpecification(
		db.NewSelect().Table("t"),
		&model.SelectionSpecification{Ids: &ids, Filters: &filters},
		testConfiguration(),
	)

	require.Error(t, err)
	assert.ErrorContains(t, err, "sounds_like")
}

func TestAddSpecificationCombinesIdsSearchTextAndFilters(t *testing.T) {
	ids := []uuid.UUID{uuid.MustParse("6ba7b810-9dad-11d1-80b4-00c04fd430c8")}
	searchText := "ord"
	filters := []model.Filter{filter("enabled", model.ConditionIs, "true")}

	sql := renderSelect(t, func(builder bun.QueryBuilder) (bun.QueryBuilder, error) {
		return AddSpecification(builder, &model.SelectionSpecification{
			Ids:        &ids,
			SearchText: &searchText,
			Filters:    &filters,
		}, testConfiguration())
	})

	assert.Contains(t, sql, `"t"."id" IN (`)
	assert.Contains(t, sql, `"t"."name" ILIKE '%ord%'`)
	assert.Contains(t, sql, `"t"."enabled" = TRUE`)
	// The three parts narrow the result together.
	assert.Equal(t, 2, strings.Count(sql, ") AND ("))
}

func TestAddSpecificationAddsNothingWithoutASpecification(t *testing.T) {
	sql := renderSelect(t, func(builder bun.QueryBuilder) (bun.QueryBuilder, error) {
		return AddSpecification(builder, nil, testConfiguration())
	})

	assert.Equal(t, `SELECT * FROM "t"`, sql)
}

func TestAddSpecificationReportsAFilterItCannotBuild(t *testing.T) {
	filters := []model.Filter{filter("name", "sounds_like", "ord")}

	_, err := AddSpecification(nil, &model.SelectionSpecification{Filters: &filters}, testConfiguration())

	require.Error(t, err)
	assert.ErrorContains(t, err, "sounds_like")
}

func TestApplySpecificationCarriesTheFailureOutOfTheCallback(t *testing.T) {
	// bun's ApplyQueryBuilder callback cannot return an error, and a query built
	// from a filter that failed to translate would run unnarrowed.
	db := bun.NewDB(nil, pgdialect.New())
	filters := []model.Filter{filter("delay", model.ConditionIs, "soon")}

	query, err := ApplySpecification(
		db.NewSelect().Table("t"),
		&model.SelectionSpecification{Filters: &filters},
		testConfiguration(),
	)

	require.Error(t, err)
	assert.Nil(t, query)
	assert.ErrorContains(t, err, "soon")
}

func TestApplySpecificationNarrowsTheQuery(t *testing.T) {
	db := bun.NewDB(nil, pgdialect.New())
	filters := []model.Filter{filter("name", model.ConditionIs, "orders")}

	query, err := ApplySpecification(
		db.NewSelect().Table("t"),
		&model.SelectionSpecification{Filters: &filters},
		testConfiguration(),
	)
	require.NoError(t, err)

	sql, err := query.AppendQuery(db.Formatter(), nil)
	require.NoError(t, err)
	assert.Equal(t, `SELECT * FROM "t" WHERE (("t"."name" = 'orders'))`, string(sql))
}

// validateFilter runs one filter through the builder every listing reaches, so
// the tests below check the refusals where they are made rather than in a
// validation pass of their own.
func validateFilter(f model.Filter, configuration *model.FilterConfiguration) error {
	_, err := getFilterQueryBuilder(f, configuration)
	return err
}

func TestAddFiltersAcceptsNothingToFilterBy(t *testing.T) {
	db := bun.NewDB(nil, pgdialect.New())

	query, err := AddFilters(db.NewSelect().Table("t").QueryBuilder(), &[]model.Filter{}, testConfiguration())

	require.NoError(t, err)
	assert.NotNil(t, query)
}

func TestAddFiltersReportsTheFirstBadFilter(t *testing.T) {
	db := bun.NewDB(nil, pgdialect.New())
	filters := []model.Filter{
		filter("name", model.ConditionContains, "order"),
		filter("delay", model.ConditionIs, "soon"),
		filter("unknown", model.ConditionIs, "x"),
	}

	_, err := AddFilters(db.NewSelect().Table("t").QueryBuilder(), &filters, testConfiguration())

	require.Error(t, err)
	assert.ErrorContains(t, err, "soon")
}

func TestFilterRejectsAnUnknownFeature(t *testing.T) {
	err := validateFilter(filter("secret", model.ConditionIs, "x"), testConfiguration())

	require.Error(t, err)
	assert.ErrorContains(t, err, "secret")
	// The features are listed in a stable order, so the message does not change
	// between runs.
	assert.ErrorContains(t, err, "created_at, delay, enabled, id, name, status")
}

func TestFilterRejectsAConditionTheFeatureDoesNotSupport(t *testing.T) {
	err := validateFilter(filter("status", model.ConditionContains, "fin"), testConfiguration())

	require.Error(t, err)
	assert.ErrorContains(t, err, model.ConditionContains)
	assert.ErrorContains(t, err, "status")
}

// An enum column takes the value as a literal of its own type, so a value the
// type does not have fails the statement. Catching it here is what turns that
// 500 into the 400 the request earned.
func TestFilterRejectsAValueOutsideTheEnum(t *testing.T) {
	err := validateFilter(filter("status", model.ConditionIn, "finished", "not-a-status"), testConfiguration())

	require.ErrorIs(t, err, ErrInvalidSelection)
	assert.Equal(t,
		`wrong filter value "not-a-status" for feature "status", `+
			`expected one of: pending, running, canceled, finished, skipped`,
		err.Error())
}

func TestFilterAcceptsEveryValueOfTheEnum(t *testing.T) {
	for _, status := range RunStatuses {
		t.Run(status, func(t *testing.T) {
			require.NoError(t, validateFilter(filter("status", model.ConditionIn, status), testConfiguration()))
		})
	}
}

// A feature that names no closed set keeps taking whatever its converter accepts.
func TestFilterLeavesAFeatureWithoutAllowedValuesUnchecked(t *testing.T) {
	require.NoError(t, validateFilter(filter("name", model.ConditionIs, "anything"), testConfiguration()))
}

func TestFilterChecksTheValueCount(t *testing.T) {
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
			err := validateFilter(tc.filter, testConfiguration())
			if tc.wantErr {
				require.Error(t, err)
				return
			}
			require.NoError(t, err)
		})
	}
}

func TestFilterConvertsTheValueToTheColumnType(t *testing.T) {
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
			err := validateFilter(tc.filter, testConfiguration())
			if tc.wantErr {
				require.Error(t, err)
				assert.ErrorContains(t, err, tc.filter.Values[0])
				return
			}
			require.NoError(t, err)
		})
	}
}

func TestFilterSkipsConversionForASubstringSearch(t *testing.T) {
	// A substring search runs against the column cast to text, so a fragment of
	// a uuid is a legitimate value even though it does not parse as one.
	for _, condition := range []string{
		model.ConditionContains,
		model.ConditionDoesNotContain,
		model.ConditionStartsWith,
		model.ConditionEndsWith,
	} {
		t.Run(condition, func(t *testing.T) {
			require.NoError(t, validateFilter(filter("id", condition, "6ba7b8"), testConfiguration()))
		})
	}
}

func TestFilterStillConvertsForAnExactMatchOnATextSearchableColumn(t *testing.T) {
	require.Error(t, validateFilter(filter("id", model.ConditionIs, "6ba7b8"), testConfiguration()))
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

// The listings over the run_status column have to name the values they accept.
// The database is the only other place that knows them, and it reports a value
// outside the set as a query failure the HTTP layer answers 500 to.
func TestEveryEnumBackedListingHoldsItsFilterToTheEnum(t *testing.T) {
	configurations := map[string]*model.FilterConfiguration{
		"test runs":      GetTestsRunsFilterConfiguration(),
		"test case runs": GetTestCaseRunsFilterConfiguration(),
	}
	for name, configuration := range configurations {
		t.Run(name, func(t *testing.T) {
			assert.Equal(t, RunStatuses, (*configuration)["status"].AllowedValues)

			err := validateFilter(filter("status", model.ConditionIn, "not-a-status"), configuration)

			require.ErrorIs(t, err, ErrInvalidSelection)
		})
	}
}

// A filter the caller got wrong is a bad request, and the HTTP layer tells it
// from a database failure by this sentinel alone.
func TestFilterRejectionsAreReportedAsABadRequest(t *testing.T) {
	configuration := testConfiguration()
	cases := map[string]model.Filter{
		"unknown feature":        filter("secret", model.ConditionIs, "1"),
		"unknown condition":      filter("name", "sounds_like", "order"),
		"wrong value":            filter("delay", model.ConditionIs, "soon"),
		"wrong value count":      filter("created_at", model.ConditionIsWithin, "1"),
		"value outside the enum": filter("status", model.ConditionIn, "not-a-status"),
	}
	for name, f := range cases {
		t.Run(name, func(t *testing.T) {
			err := validateFilter(f, configuration)
			require.Error(t, err)
			assert.ErrorIs(t, err, ErrInvalidSelection)

			_, err = getFilterQueryBuilder(f, configuration)
			require.Error(t, err)
			assert.ErrorIs(t, err, ErrInvalidSelection)
		})
	}
}

// The sentinel is matched, not printed: the message the caller reads has to stay
// the explanation on its own.
func TestAnInvalidSelectionCarriesOnlyItsOwnMessage(t *testing.T) {
	err := validateFilter(filter("secret", model.ConditionIs, "1"), testConfiguration())

	require.Error(t, err)
	assert.Equal(t, `wrong filter feature "secret", expected one of: created_at, delay, enabled, id, name, status`,
		err.Error())
}
