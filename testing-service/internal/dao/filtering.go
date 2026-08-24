package dao

import (
	"errors"
	"fmt"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/uptrace/bun"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

// timestampLayout is the format the API accepts for timestamp filter values.
const timestampLayout = "2006-01-02 15:04:05.000 Z0700"

// ErrInvalidSelection marks a filter, search or sorting value the caller sent
// wrong. It is what tells such a failure from a database one, so the HTTP layer
// answers 400 with the explanation instead of 500 with none.
var ErrInvalidSelection = errors.New("invalid selection")

// invalidSelectionError carries the explanation on its own: the sentinel is
// matched with errors.Is, never printed, so the message the caller reads is not
// prefixed with it.
type invalidSelectionError struct {
	message string
}

func (e *invalidSelectionError) Error() string { return e.message }

func (e *invalidSelectionError) Unwrap() error { return ErrInvalidSelection }

func invalidSelection(format string, args ...any) error {
	return &invalidSelectionError{message: fmt.Sprintf(format, args...)}
}

// ApplySpecification narrows query by specification. bun's ApplyQueryBuilder
// callback cannot report an error, so the failure is carried out of the closure
// instead of being swallowed into a predicate that matches every row.
func ApplySpecification(
	query *bun.SelectQuery,
	specification *model.SelectionSpecification,
	configuration *model.FilterConfiguration,
) (*bun.SelectQuery, error) {
	var failure error
	query = query.ApplyQueryBuilder(func(builder bun.QueryBuilder) bun.QueryBuilder {
		narrowed, err := AddSpecification(builder, specification, configuration)
		if err != nil {
			failure = err
			return builder
		}
		return narrowed
	})
	if failure != nil {
		return nil, failure
	}
	return query, nil
}

func AddSpecification(
	queryBuilder bun.QueryBuilder,
	selectSpecification *model.SelectionSpecification,
	configuration *model.FilterConfiguration,
) (bun.QueryBuilder, error) {
	if selectSpecification == nil {
		return queryBuilder, nil
	}
	if selectSpecification.Ids != nil {
		queryBuilder = queryBuilder.WhereGroup(" OR ", func(qb bun.QueryBuilder) bun.QueryBuilder {
			// A list that is present but empty selects nothing. Leaving the predicate
			// out instead would widen the request to the whole table, which is how an
			// empty selection came to mean every row: an export dumped the catalog and
			// a rerun of no cases ran every case there is.
			if len(*selectSpecification.Ids) == 0 {
				return qb.Where("FALSE")
			}
			column := "id"
			if featureConfiguration, ok := (*configuration)["id"]; ok {
				column = featureConfiguration.Column
			}
			return qb.Where("? IN (?)", bun.Ident(column), bun.In(*selectSpecification.Ids))
		})
	}
	if selectSpecification.SearchText != nil && len(*selectSpecification.SearchText) > 0 {
		narrowed, err := AddSearchText(queryBuilder, *selectSpecification.SearchText, configuration)
		if err != nil {
			return nil, err
		}
		queryBuilder = narrowed
	}
	if selectSpecification.Filters != nil {
		narrowed, err := AddFilters(queryBuilder, selectSpecification.Filters, configuration)
		if err != nil {
			return nil, err
		}
		queryBuilder = narrowed
	}
	return queryBuilder, nil
}

func AddSearchText(
	queryBuilder bun.QueryBuilder,
	searchText string,
	configuration *model.FilterConfiguration,
) (bun.QueryBuilder, error) {
	// The predicates are built before the group is opened, because the callback
	// WhereGroup takes cannot report a feature it failed to translate.
	var predicates []func(bun.QueryBuilder) bun.QueryBuilder
	for _, feature := range sortedFeatures(configuration) {
		if !slices.Contains((*configuration)[feature].Conditions, model.ConditionContains) {
			continue
		}
		predicate, err := getFilterQueryBuilder(model.Filter{
			Feature:   feature,
			Condition: model.ConditionContains,
			Values:    []string{searchText},
		}, configuration)
		if err != nil {
			return nil, err
		}
		predicates = append(predicates, predicate)
	}
	if len(predicates) == 0 {
		return queryBuilder, nil
	}
	return queryBuilder.WhereGroup(" AND ", func(qb bun.QueryBuilder) bun.QueryBuilder {
		for _, predicate := range predicates {
			qb = qb.WhereGroup(" OR ", predicate)
		}
		return qb
	}), nil
}

func AddFilters(
	queryBuilder bun.QueryBuilder,
	filters *[]model.Filter,
	configuration *model.FilterConfiguration,
) (bun.QueryBuilder, error) {
	q := queryBuilder
	for _, filter := range *filters {
		predicate, err := getFilterQueryBuilder(filter, configuration)
		if err != nil {
			return nil, err
		}
		q = q.WhereGroup(" AND ", predicate)
	}
	return q, nil
}

// sortedFeatures keeps the features a message lists in a stable order.
func sortedFeatures(configuration *model.FilterConfiguration) []string {
	features := make([]string, 0, len(*configuration))
	for feature := range *configuration {
		features = append(features, feature)
	}
	slices.Sort(features)
	return features
}

func convertFilterValues(filter model.Filter, configuration *model.FilterConfiguration) ([]any, error) {
	featureFilterConfiguration := (*configuration)[filter.Feature]
	values, err := convertValues(filter.Values, featureFilterConfiguration.Converter)
	if err != nil {
		return nil, fmt.Errorf("%w for feature %q", err, filter.Feature)
	}
	return values, nil
}

// convertValues reports a value the column type rejects rather than dropping a
// nil into the WHERE clause, where it would match nothing and look like an empty
// result instead of a bad request.
func convertValues(values []string, converter func(s string) (any, error)) ([]any, error) {
	var result []any
	for _, value := range values {
		v, err := converter(value)
		if err != nil {
			return nil, invalidSelection("wrong filter value %q", value)
		}
		result = append(result, v)
	}
	return result, nil
}

const valueCountOneOrMore = -1

func validateFilterValueCount(condition string, count int) error {
	expectedCount := getExpectedValueCount(condition)
	if expectedCount == valueCountOneOrMore {
		if count < 1 {
			return invalidSelection("filter condition %q requires one or more values, got %v", condition, count)
		}
		return nil
	}
	if expectedCount != count {
		return invalidSelection("filter condition %q requires %v value(s), got %v", condition, expectedCount, count)
	}
	return nil
}

// validateFilterValues holds a feature that declares a closed set to it. An
// enum-backed column is the case that needs it: a value outside the type reaches
// PostgreSQL as an enum literal it cannot parse, and the listing answers 500
// where the request was simply wrong.
func validateFilterValues(filter model.Filter, configuration model.FeatureFilterConfiguration) error {
	if len(configuration.AllowedValues) == 0 {
		return nil
	}
	for _, value := range filter.Values {
		if !slices.Contains(configuration.AllowedValues, value) {
			return invalidSelection(
				"wrong filter value %q for feature %q, expected one of: %v",
				value,
				filter.Feature,
				strings.Join(configuration.AllowedValues, ", "),
			)
		}
	}
	return nil
}

func getExpectedValueCount(condition string) int {
	switch condition {
	case model.ConditionIn, model.ConditionNotIn:
		return valueCountOneOrMore
	case model.ConditionEmpty, model.ConditionNotEmpty:
		return 0
	case model.ConditionIsWithin:
		return 2
	default:
		return 1
	}
}

// getFilterQueryBuilder turns one filter into the predicate it stands for, and
// reports a filter it cannot translate. This is the only gate a filter passes:
// every listing reaches it through AddSpecification, so a listing added later
// cannot skip a check by forgetting to call one. Answering a filter it does not
// understand with a predicate that matches everything would widen the result set
// to the whole table instead of failing the request.
func getFilterQueryBuilder(
	filter model.Filter,
	configuration *model.FilterConfiguration,
) (func(builder bun.QueryBuilder) bun.QueryBuilder, error) {
	featureFilterConfiguration, ok := (*configuration)[filter.Feature]
	if !ok {
		return nil, invalidSelection(
			"wrong filter feature %q, expected one of: %v",
			filter.Feature,
			strings.Join(sortedFeatures(configuration), ", "),
		)
	}
	// A condition this file knows how to render is still wrong when the feature
	// does not declare it: ILIKE over an enum column is a database error, not an
	// empty result.
	if !slices.Contains(featureFilterConfiguration.Conditions, filter.Condition) {
		return nil, invalidSelection(
			"wrong filter condition %q for feature %q, expected one of: %v",
			filter.Condition,
			filter.Feature,
			strings.Join(featureFilterConfiguration.Conditions, ", "),
		)
	}
	if err := validateFilterValueCount(filter.Condition, len(filter.Values)); err != nil {
		return nil, err
	}
	if err := validateFilterValues(filter, featureFilterConfiguration); err != nil {
		return nil, err
	}
	var values []any
	var err error
	if isSearchInsideValue(filter.Condition, featureFilterConfiguration.RequiresConversionToText) {
		values, err = convertValues(filter.Values, func(s string) (any, error) { return s, nil })
	} else {
		values, err = convertFilterValues(filter, configuration)
	}
	if err != nil {
		return nil, err
	}
	column := bun.Ident(featureFilterConfiguration.Column)
	asText := featureFilterConfiguration.RequiresConversionToText
	switch filter.Condition {
	case model.ConditionIs:
		return func(q bun.QueryBuilder) bun.QueryBuilder {
			return q.Where("? = ?", column, values[0])
		}, nil
	case model.ConditionIsNot:
		return func(q bun.QueryBuilder) bun.QueryBuilder {
			return q.Where("? != ?", column, values[0])
		}, nil
	case model.ConditionContains:
		return likeBuilder(column, asText, false, "%"+likeLiteral(values[0])+"%"), nil
	case model.ConditionDoesNotContain:
		return likeBuilder(column, asText, true, "%"+likeLiteral(values[0])+"%"), nil
	case model.ConditionStartsWith:
		return likeBuilder(column, asText, false, likeLiteral(values[0])+"%"), nil
	case model.ConditionEndsWith:
		return likeBuilder(column, asText, false, "%"+likeLiteral(values[0])), nil
	case model.ConditionIn:
		return func(q bun.QueryBuilder) bun.QueryBuilder {
			return q.Where("? IN (?)", column, bun.In(values))
		}, nil
	case model.ConditionNotIn:
		return func(q bun.QueryBuilder) bun.QueryBuilder {
			return q.Where("? NOT IN (?)", column, bun.In(values))
		}, nil
	case model.ConditionEmpty:
		return func(q bun.QueryBuilder) bun.QueryBuilder {
			return q.Where("? IS NULL", column)
		}, nil
	case model.ConditionNotEmpty:
		return func(q bun.QueryBuilder) bun.QueryBuilder {
			return q.Where("? IS NOT NULL", column)
		}, nil
	case model.ConditionIsAfter, model.ConditionGreaterThan:
		return func(q bun.QueryBuilder) bun.QueryBuilder {
			return q.Where("? > ?", column, values[0])
		}, nil
	case model.ConditionIsBefore, model.ConditionLessThan:
		return func(q bun.QueryBuilder) bun.QueryBuilder {
			return q.Where("? < ?", column, values[0])
		}, nil
	case model.ConditionIsWithin:
		return func(q bun.QueryBuilder) bun.QueryBuilder {
			return q.Where("? BETWEEN ? AND ?", column, values[0], values[1])
		}, nil
	default:
		return nil, invalidSelection(
			"wrong filter condition %q for feature %q",
			filter.Condition,
			filter.Feature,
		)
	}
}

// likeEscaper neutralizes the characters ILIKE reads as syntax, so that a value
// the user typed matches itself. PostgreSQL escapes them with a backslash unless
// the pattern names another escape character, so the backslash has to be escaped
// first. strings.Replacer applies each rule once, left to right, which is what
// keeps that from cascading over its own output.
var likeEscaper = strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`)

// likeLiteral renders a filter value as the literal part of an ILIKE pattern.
func likeLiteral(value any) string {
	return likeEscaper.Replace(fmt.Sprint(value))
}

func likeBuilder(column bun.Ident, asText, negated bool, pattern string) func(bun.QueryBuilder) bun.QueryBuilder {
	expression := "? ILIKE ?"
	switch {
	case asText && negated:
		expression = "?::text NOT ILIKE ?"
	case asText:
		expression = "?::text ILIKE ?"
	case negated:
		expression = "? NOT ILIKE ?"
	}
	return func(q bun.QueryBuilder) bun.QueryBuilder {
		return q.Where(expression, column, pattern)
	}
}

func isSearchInsideValue(condition string, convertableToText bool) bool {
	return convertableToText && (condition == model.ConditionContains ||
		condition == model.ConditionDoesNotContain ||
		condition == model.ConditionStartsWith ||
		condition == model.ConditionEndsWith)
}

func convertStringToUUID(s string) (any, error) {
	return uuid.Parse(s)
}

func convertStringToTime(s string) (any, error) {
	return time.Parse(timestampLayout, s)
}

func convertStringToInteger(s string) (any, error) {
	return strconv.Atoi(s)
}

func convertStringToBoolean(s string) (any, error) {
	return strconv.ParseBool(s)
}

func GetIdFilterConfiguration(column string) model.FeatureFilterConfiguration {
	return model.FeatureFilterConfiguration{
		Column:                   column,
		RequiresConversionToText: true,
		Conditions: []string{
			model.ConditionIs,
			model.ConditionIsNot,
			model.ConditionIn,
			model.ConditionNotIn,
			model.ConditionContains,
			model.ConditionDoesNotContain,
			model.ConditionStartsWith,
			model.ConditionEndsWith,
		},
		Converter: convertStringToUUID,
	}
}

func GetStringFilterConfiguration(column string) model.FeatureFilterConfiguration {
	return model.FeatureFilterConfiguration{
		Column: column,
		Conditions: []string{
			model.ConditionContains,
			model.ConditionDoesNotContain,
			model.ConditionStartsWith,
			model.ConditionEndsWith,
			model.ConditionIs,
			model.ConditionIsNot,
			model.ConditionIn,
			model.ConditionNotIn,
		},
		Converter: func(s string) (any, error) { return s, nil },
	}
}

func GetTimestampFilterConfiguration(column string) model.FeatureFilterConfiguration {
	return model.FeatureFilterConfiguration{
		Column:     column,
		Conditions: []string{model.ConditionIsAfter, model.ConditionIsBefore, model.ConditionIsWithin},
		Converter:  convertStringToTime,
	}
}

func GetIntegerFilterConfiguration(column string) model.FeatureFilterConfiguration {
	return model.FeatureFilterConfiguration{
		Column:     column,
		Conditions: []string{model.ConditionIs, model.ConditionLessThan, model.ConditionGreaterThan},
		Converter:  convertStringToInteger,
	}
}

func GetIntegerWithSubstringFilterConfiguration(column string) model.FeatureFilterConfiguration {
	return model.FeatureFilterConfiguration{
		Column:                   column,
		RequiresConversionToText: true,
		Conditions: []string{
			model.ConditionContains,
			model.ConditionDoesNotContain,
			model.ConditionStartsWith,
			model.ConditionEndsWith,
			model.ConditionIs,
			model.ConditionIsNot,
			model.ConditionIn,
			model.ConditionNotIn,
			model.ConditionLessThan,
			model.ConditionGreaterThan,
		},
		Converter: convertStringToInteger,
	}
}

// GetEnumFilterConfiguration describes a filter over an enum column. The allowed
// values are named rather than optional: the database is the only other thing
// that knows them, and it reports a value outside the set as a query failure.
func GetEnumFilterConfiguration(column string, allowedValues []string) model.FeatureFilterConfiguration {
	return model.FeatureFilterConfiguration{
		Column:        column,
		Conditions:    []string{model.ConditionIn, model.ConditionNotIn},
		Converter:     func(s string) (any, error) { return s, nil },
		AllowedValues: allowedValues,
	}
}

func GetBooleanFilterConfiguration(column string) model.FeatureFilterConfiguration {
	return model.FeatureFilterConfiguration{
		Column:     column,
		Conditions: []string{model.ConditionIs, model.ConditionIsNot},
		Converter:  convertStringToBoolean,
	}
}

func HasFeatureFilter(specification *model.SelectionSpecification, feature string) bool {
	return specification != nil &&
		specification.Filters != nil &&
		slices.ContainsFunc(*specification.Filters, func(filter model.Filter) bool { return filter.Feature == feature })
}
