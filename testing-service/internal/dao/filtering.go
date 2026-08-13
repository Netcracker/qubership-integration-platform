package dao

import (
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

func AddSpecification(
	queryBuilder bun.QueryBuilder,
	selectSpecification *model.SelectionSpecification,
	configuration *model.FilterConfiguration,
) bun.QueryBuilder {
	if selectSpecification == nil {
		return queryBuilder
	}
	if selectSpecification.Ids != nil && len(*selectSpecification.Ids) > 0 {
		queryBuilder = queryBuilder.WhereGroup(" OR ", func(qb bun.QueryBuilder) bun.QueryBuilder {
			column := "id"
			if featureConfiguration, ok := (*configuration)["id"]; ok {
				column = featureConfiguration.Column
			}
			return qb.Where("? IN (?)", bun.Ident(column), bun.In(*selectSpecification.Ids))
		})
	}
	if selectSpecification.SearchText != nil && len(*selectSpecification.SearchText) > 0 {
		queryBuilder = AddSearchText(queryBuilder, *selectSpecification.SearchText, configuration)
	}
	if selectSpecification.Filters != nil {
		queryBuilder = AddFilters(queryBuilder, selectSpecification.Filters, configuration)
	}
	return queryBuilder
}

func AddSearchText(queryBuilder bun.QueryBuilder, searchText string, configuration *model.FilterConfiguration) bun.QueryBuilder {
	return queryBuilder.WhereGroup(" AND ", func(qb bun.QueryBuilder) bun.QueryBuilder {
		for _, feature := range sortedFeatures(configuration) {
			if !slices.Contains((*configuration)[feature].Conditions, model.ConditionContains) {
				continue
			}
			filter := model.Filter{
				Feature:   feature,
				Condition: model.ConditionContains,
				Values:    []string{searchText},
			}
			qb = qb.WhereGroup(" OR ", getFilterQueryBuilder(filter, configuration))
		}
		return qb
	})
}

func AddFilters(queryBuilder bun.QueryBuilder, filters *[]model.Filter, configuration *model.FilterConfiguration) bun.QueryBuilder {
	q := queryBuilder
	for _, filter := range *filters {
		q = q.WhereGroup(" AND ", getFilterQueryBuilder(filter, configuration))
	}
	return q
}

func ValidateFilters(filters *[]model.Filter, configuration *model.FilterConfiguration) error {
	if filters == nil {
		return nil
	}
	for _, filter := range *filters {
		if err := ValidateFilter(filter, configuration); err != nil {
			return err
		}
	}
	return nil
}

func ValidateFilter(filter model.Filter, configuration *model.FilterConfiguration) error {
	featureFilterConfiguration, ok := (*configuration)[filter.Feature]
	if !ok {
		return fmt.Errorf(
			"wrong filter feature %q, expected one of: %v",
			filter.Feature,
			strings.Join(sortedFeatures(configuration), ", "),
		)
	}
	if !slices.Contains(featureFilterConfiguration.Conditions, filter.Condition) {
		return fmt.Errorf(
			"wrong filter condition %q for feature %q, expected one of: %v",
			filter.Condition,
			filter.Feature,
			strings.Join(featureFilterConfiguration.Conditions, ", "),
		)
	}
	if err := validateFilterValueCount(filter.Condition, len(filter.Values)); err != nil {
		return err
	}
	if isSearchInsideValue(filter.Condition, featureFilterConfiguration.RequiresConversionToText) {
		return nil
	}
	for _, value := range filter.Values {
		if _, err := featureFilterConfiguration.Converter(value); err != nil {
			return fmt.Errorf("wrong filter value %q for feature %q", value, filter.Feature)
		}
	}
	return nil
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

func convertFilterValues(filter model.Filter, configuration *model.FilterConfiguration) *[]any {
	featureFilterConfiguration := (*configuration)[filter.Feature]
	return convertValues(filter.Values, featureFilterConfiguration.Converter)
}

func convertValues(values []string, converter func(s string) (any, error)) *[]any {
	var result []any
	for _, value := range values {
		v, _ := converter(value)
		result = append(result, v)
	}
	return &result
}

const valueCountOneOrMore = -1

func validateFilterValueCount(condition string, count int) error {
	expectedCount := getExpectedValueCount(condition)
	if expectedCount == valueCountOneOrMore {
		if count < 1 {
			return fmt.Errorf("filter condition %q requires one or more values, got %v", condition, count)
		}
		return nil
	}
	if expectedCount != count {
		return fmt.Errorf("filter condition %q requires %v value(s), got %v", condition, expectedCount, count)
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

func getFilterQueryBuilder(filter model.Filter, configuration *model.FilterConfiguration) func(builder bun.QueryBuilder) bun.QueryBuilder {
	featureFilterConfiguration := (*configuration)[filter.Feature]
	var values *[]any
	if isSearchInsideValue(filter.Condition, featureFilterConfiguration.RequiresConversionToText) {
		values = convertValues(filter.Values, func(s string) (any, error) { return s, nil })
	} else {
		values = convertFilterValues(filter, configuration)
	}
	column := bun.Ident(featureFilterConfiguration.Column)
	asText := featureFilterConfiguration.RequiresConversionToText
	switch filter.Condition {
	case model.ConditionIs:
		return func(q bun.QueryBuilder) bun.QueryBuilder {
			return q.Where("? = ?", column, (*values)[0])
		}
	case model.ConditionIsNot:
		return func(q bun.QueryBuilder) bun.QueryBuilder {
			return q.Where("? != ?", column, (*values)[0])
		}
	case model.ConditionContains:
		return likeBuilder(column, asText, false, fmt.Sprintf("%%%s%%", (*values)[0]))
	case model.ConditionDoesNotContain:
		return likeBuilder(column, asText, true, fmt.Sprintf("%%%s%%", (*values)[0]))
	case model.ConditionStartsWith:
		return likeBuilder(column, asText, false, fmt.Sprintf("%s%%", (*values)[0]))
	case model.ConditionEndsWith:
		return likeBuilder(column, asText, false, fmt.Sprintf("%%%s", (*values)[0]))
	case model.ConditionIn:
		return func(q bun.QueryBuilder) bun.QueryBuilder {
			return q.Where("? IN (?)", column, bun.In(*values))
		}
	case model.ConditionNotIn:
		return func(q bun.QueryBuilder) bun.QueryBuilder {
			return q.Where("? NOT IN (?)", column, bun.In(*values))
		}
	case model.ConditionEmpty:
		return func(q bun.QueryBuilder) bun.QueryBuilder {
			return q.Where("? IS NULL", column)
		}
	case model.ConditionNotEmpty:
		return func(q bun.QueryBuilder) bun.QueryBuilder {
			return q.Where("? IS NOT NULL", column)
		}
	case model.ConditionIsAfter, model.ConditionGreaterThan:
		return func(q bun.QueryBuilder) bun.QueryBuilder {
			return q.Where("? > ?", column, (*values)[0])
		}
	case model.ConditionIsBefore, model.ConditionLessThan:
		return func(q bun.QueryBuilder) bun.QueryBuilder {
			return q.Where("? < ?", column, (*values)[0])
		}
	case model.ConditionIsWithin:
		return func(q bun.QueryBuilder) bun.QueryBuilder {
			return q.Where("? BETWEEN ? AND ?", column, (*values)[0], (*values)[1])
		}
	default:
		return func(q bun.QueryBuilder) bun.QueryBuilder {
			return q.Where("true")
		}
	}
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

func convertStringToUuid(s string) (any, error) {
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
		Converter: convertStringToUuid,
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

func GetEnumFilterConfiguration(column string) model.FeatureFilterConfiguration {
	return model.FeatureFilterConfiguration{
		Column:     column,
		Conditions: []string{model.ConditionIn, model.ConditionNotIn},
		Converter:  func(s string) (any, error) { return s, nil },
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
