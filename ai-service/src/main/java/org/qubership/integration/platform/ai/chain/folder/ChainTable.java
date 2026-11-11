package org.qubership.integration.platform.ai.chain.folder;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.qubership.integration.platform.ai.chain.folder.ChainFilter.Condition;
import org.qubership.integration.platform.ai.chain.folder.ChainFilter.Field;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.DeploymentDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.FilterDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.FolderItemDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.UserDto;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogChainLabel;

/**
 * The chains page as data: every chain with its folder path, narrowed by table-column filters.
 *
 * <p>Name, id, description, labels, and status go to the catalog filter the page itself uses.
 * The catalog cannot filter on who created or changed a chain or when, so those columns are
 * applied here to the rows it returns.
 */
@ApplicationScoped
public class ChainTable {

  private static final String CHAIN_ITEM_TYPE = "CHAIN";
  private static final Set<String> STATUSES = Set.of("DEPLOYED", "FAILED", "PROCESSING", "DRAFT");
  private static final Set<Condition> TEXT_CONDITIONS =
      Set.of(
          Condition.IS,
          Condition.IS_NOT,
          Condition.CONTAINS,
          Condition.DOES_NOT_CONTAIN,
          Condition.STARTS_WITH,
          Condition.ENDS_WITH,
          Condition.EMPTY,
          Condition.NOT_EMPTY);
  private static final Set<Condition> AUTHOR_CONDITIONS =
      Set.of(Condition.IS, Condition.IS_NOT, Condition.CONTAINS, Condition.DOES_NOT_CONTAIN);

  private static final Comparator<Row> BY_NAME =
      Comparator.comparing(Row::name, String.CASE_INSENSITIVE_ORDER)
          .thenComparing(Row::folderPath)
          .thenComparing(Row::id);

  private final CatalogRestClient catalog;

  @Inject
  public ChainTable(@RestClient CatalogRestClient catalog) {
    this.catalog = catalog;
  }

  /**
   * One chain as the page lists it. {@code folderPath} is empty for a chain at the root, {@code
   * labels} holds only the labels people set, and {@code statuses} has one entry per distinct
   * deployment state, DRAFT for a chain with no deployments.
   */
  public record Row(
      String id,
      String name,
      String folderPath,
      Long createdWhen,
      String createdBy,
      Long modifiedWhen,
      String modifiedBy,
      List<String> labels,
      List<String> statuses) {

    public Row {
      labels = labels == null ? List.of() : List.copyOf(labels);
      statuses = statuses == null ? List.of() : List.copyOf(statuses);
    }

    /** Keeps existing test call sites on the seven-field constructor. */
    public Row(
        String id,
        String name,
        String folderPath,
        Long createdWhen,
        String createdBy,
        Long modifiedWhen,
        String modifiedBy) {
      this(id, name, folderPath, createdWhen, createdBy, modifiedWhen, modifiedBy, null, null);
    }

    public String display() {
      return folderPath.isEmpty() ? name : name + " (" + folderPath + ")";
    }
  }

  /**
   * Chains that meet every filter, sorted by name.
   *
   * @throws IllegalArgumentException with a reason a person can act on, for a filter the catalog
   *     would reject
   */
  public List<Row> find(List<ChainFilter> filters) {
    List<FilterDto> catalogFilters = new ArrayList<>();
    List<ChainFilter> localFilters = new ArrayList<>();
    for (ChainFilter filter : filters) {
      validate(filter);
      if (isLocal(filter.field())) {
        localFilters.add(filter);
      } else {
        catalogFilters.add(
            new FilterDto(filter.field().name(), filter.condition().name(), catalogValue(filter)));
      }
    }
    return rows(catalog.filterFolderItems(catalogFilters)).stream()
        .filter(row -> localFilters.stream().allMatch(filter -> matches(row, filter)))
        .sorted(BY_NAME)
        .toList();
  }

  /** Rows inside {@code folderPath} or any folder below it. */
  public static List<Row> inFolder(List<Row> rows, String folderPath) {
    return rows.stream()
        .filter(
            row ->
                row.folderPath().equals(folderPath)
                    || row.folderPath().startsWith(folderPath + "/"))
        .toList();
  }

  private static List<Row> rows(List<FolderItemDto> items) {
    if (items == null) {
      return List.of();
    }
    Map<String, FolderItemDto> folders = new HashMap<>();
    for (FolderItemDto item : items) {
      if (item != null && CatalogFolders.FOLDER_ITEM_TYPE.equals(item.itemType())) {
        folders.put(item.id(), item);
      }
    }
    List<Row> rows = new ArrayList<>();
    for (FolderItemDto item : items) {
      if (item == null || !CHAIN_ITEM_TYPE.equals(item.itemType())) {
        continue;
      }
      rows.add(
          new Row(
              item.id(),
              item.name(),
              path(item.parentId(), folders),
              item.createdWhen(),
              username(item.createdBy()),
              item.modifiedWhen(),
              username(item.modifiedBy()),
              item.labels() == null
                  ? List.of()
                  : item.labels().stream()
                      .filter(label -> !label.technical())
                      .map(CatalogChainLabel::name)
                      .toList(),
              deploymentStatuses(item.deployments())));
    }
    return List.copyOf(rows);
  }

  private static String path(String folderId, Map<String, FolderItemDto> folders) {
    List<String> names = new ArrayList<>();
    // Bounded by the folder count, so a parent cycle in a bad response cannot loop forever.
    for (FolderItemDto folder = folders.get(folderId);
        folder != null && names.size() <= folders.size();
        folder = folders.get(folder.parentId())) {
      names.add(0, folder.name());
    }
    return String.join("/", names);
  }

  /**
   * The states the chains page shows for a chain. A deployment the engines have not reported on
   * yet counts as PROCESSING, as it does in the catalog's status filter.
   */
  private static List<String> deploymentStatuses(List<DeploymentDto> deployments) {
    if (deployments == null || deployments.isEmpty()) {
      return List.of("DRAFT");
    }
    return deployments.stream()
        .flatMap(
            deployment ->
                deployment.runtime() == null
                        || deployment.runtime().states() == null
                        || deployment.runtime().states().isEmpty()
                    ? Stream.of("PROCESSING")
                    : deployment.runtime().states().values().stream()
                        .map(state -> state.status() == null ? "PROCESSING" : state.status()))
        .distinct()
        .toList();
  }

  private static String username(UserDto user) {
    return user == null || user.username() == null ? "" : user.username();
  }

  private static boolean isLocal(Field field) {
    return switch (field) {
      case CREATED_BY, CREATED_AT, MODIFIED_BY, MODIFIED_AT -> true;
      default -> false;
    };
  }

  private static void validate(ChainFilter filter) {
    if (filter == null || filter.field() == null || filter.condition() == null) {
      throw new IllegalArgumentException("A chain filter needs a column and a condition.");
    }
    Set<Condition> allowed =
        switch (filter.field()) {
          case STATUS -> Set.of(Condition.IN, Condition.NOT_IN);
          case CREATED_BY, MODIFIED_BY -> AUTHOR_CONDITIONS;
          case CREATED_AT, MODIFIED_AT -> Set.of(Condition.IS_AFTER, Condition.IS_BEFORE);
          default -> TEXT_CONDITIONS;
        };
    if (!allowed.contains(filter.condition())) {
      throw new IllegalArgumentException(
          "Column " + filter.field() + " cannot be filtered with " + filter.condition() + ".");
    }
    if (filter.field() == Field.STATUS) {
      for (String status : statuses(filter.value())) {
        if (!STATUSES.contains(status)) {
          throw new IllegalArgumentException(
              "Status " + status + " is unknown. Use DEPLOYED, FAILED, PROCESSING, or DRAFT.");
        }
      }
    }
    if (filter.field() == Field.CREATED_AT || filter.field() == Field.MODIFIED_AT) {
      boundary(filter);
    }
  }

  private static String catalogValue(ChainFilter filter) {
    if (filter.field() == Field.STATUS) {
      return String.join(",", statuses(filter.value()));
    }
    return filter.value() == null ? "" : filter.value().strip();
  }

  private static List<String> statuses(String value) {
    return Arrays.stream((value == null ? "" : value).split(","))
        .map(status -> status.strip().toUpperCase(Locale.ROOT))
        .filter(status -> !status.isEmpty())
        .toList();
  }

  private static boolean matches(Row row, ChainFilter filter) {
    return switch (filter.field()) {
      case CREATED_BY -> matchesAuthor(row.createdBy(), filter);
      case MODIFIED_BY -> matchesAuthor(row.modifiedBy(), filter);
      case CREATED_AT -> matchesTime(row.createdWhen(), filter);
      case MODIFIED_AT -> matchesTime(row.modifiedWhen(), filter);
      default -> true;
    };
  }

  private static boolean matchesAuthor(String author, ChainFilter filter) {
    String actual = author.toLowerCase(Locale.ROOT);
    String expected = filter.value() == null ? "" : filter.value().strip().toLowerCase(Locale.ROOT);
    return switch (filter.condition()) {
      case IS -> actual.equals(expected);
      case IS_NOT -> !actual.equals(expected);
      case CONTAINS -> actual.contains(expected);
      default -> !actual.contains(expected);
    };
  }

  private static boolean matchesTime(Long when, ChainFilter filter) {
    if (when == null) {
      return false;
    }
    long limit = boundary(filter);
    return filter.condition() == Condition.IS_AFTER ? when >= limit : when < limit;
  }

  /**
   * The instant a date condition compares against. A bare date counts as a whole day: "after
   * September 30" starts on October 1, "before September 30" ends on September 29.
   *
   * <p>ponytail: dates are UTC days; take the author's time zone if people notice off-by-one days.
   */
  private static long boundary(ChainFilter filter) {
    String value = filter.value() == null ? "" : filter.value().strip();
    try {
      LocalDate day = LocalDate.parse(value);
      LocalDate start = filter.condition() == Condition.IS_AFTER ? day.plusDays(1) : day;
      return start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    } catch (DateTimeParseException notADate) {
      try {
        return OffsetDateTime.parse(value).toInstant().toEpochMilli();
      } catch (DateTimeParseException notADateTime) {
        throw new IllegalArgumentException(
            "Date '" + value + "' is not an ISO date such as 2026-09-30.");
      }
    }
  }
}
