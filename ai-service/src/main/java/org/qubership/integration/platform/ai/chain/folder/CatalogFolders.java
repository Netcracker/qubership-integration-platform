package org.qubership.integration.platform.ai.chain.folder;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.FolderItemDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.FolderWriteRequest;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.ListFolderRequest;

/**
 * Finds and creates folders in the catalog's chain tree by the name or path a person typed.
 *
 * <p>A bare name ({@code Salesforce}) matches a folder of that name anywhere in the tree. A path
 * ({@code Integrations/Salesforce}) or a name with a leading slash ({@code /Salesforce}) is read
 * from the root. Names compare without case, because people do not retype a folder's casing.
 * {@code /} or nothing at all is the root.
 */
@ApplicationScoped
public class CatalogFolders {

  public static final String FOLDER_ITEM_TYPE = "FOLDER";

  private static final String SEPARATOR = "/";

  private final CatalogRestClient catalog;

  @Inject
  public CatalogFolders(@RestClient CatalogRestClient catalog) {
    this.catalog = catalog;
  }

  /**
   * One catalog folder; {@code path} joins the names from the root with {@code /}.
   *
   * <p>Carries the description because the catalog's folder update overwrites it: a rename must
   * send it back unchanged.
   */
  public record Folder(String id, String name, String parentId, String path, String description) {

    public Folder(String id, String name, String parentId, String path) {
      this(id, name, parentId, path, null);
    }
  }

  /** What a typed folder reference points at in a given tree. */
  public sealed interface Resolution {

    /** The root of the tree, which has no folder id. */
    record Root() implements Resolution {}

    record Found(Folder folder) implements Resolution {}

    /** Nothing at this path yet; {@code path} is where creating it would put it. */
    record Missing(String path) implements Resolution {}

    /** A bare name that more than one folder carries. Never pick one of them silently. */
    record Ambiguous(List<Folder> candidates) implements Resolution {}
  }

  /**
   * Reads every folder, parents before children.
   *
   * <p>ponytail: one catalog call per folder, because the catalog has no whole-tree listing and its
   * name search is case-sensitive. Add a tree endpoint to the catalog if folder counts reach the
   * thousands.
   */
  public List<Folder> tree() {
    List<Folder> folders = new ArrayList<>();
    Deque<Folder> pending = new ArrayDeque<>();
    collectChildren(null, "", folders, pending);
    while (!pending.isEmpty()) {
      Folder parent = pending.removeFirst();
      collectChildren(parent.id(), parent.path(), folders, pending);
    }
    return List.copyOf(folders);
  }

  public static Resolution resolve(String reference, List<Folder> tree) {
    List<String> segments = segments(reference);
    if (segments.isEmpty()) {
      return new Resolution.Root();
    }
    boolean fromRoot = reference.strip().startsWith(SEPARATOR) || segments.size() > 1;
    if (!fromRoot) {
      List<Folder> named =
          tree.stream().filter(folder -> folder.name().equalsIgnoreCase(segments.get(0))).toList();
      if (named.size() > 1) {
        return new Resolution.Ambiguous(named);
      }
      return named.isEmpty()
          ? new Resolution.Missing(segments.get(0))
          : new Resolution.Found(named.get(0));
    }
    Folder current = null;
    for (int depth = 0; depth < segments.size(); depth++) {
      Folder child = child(tree, current, segments.get(depth));
      if (child == null) {
        String rest = String.join(SEPARATOR, segments.subList(depth, segments.size()));
        // Existing levels keep the catalog's casing; levels still to create keep the typed one.
        return new Resolution.Missing(current == null ? rest : current.path() + SEPARATOR + rest);
      }
      current = child;
    }
    return new Resolution.Found(current);
  }

  /** Creates each level of {@code path} that the tree lacks and returns the deepest folder. */
  public synchronized Folder create(String path) {
    List<Folder> tree = tree();
    Folder current = null;
    for (String segment : segments(path)) {
      Folder existing = child(tree, current, segment);
      if (existing != null) {
        current = existing;
        continue;
      }
      String parentId = current == null ? null : current.id();
      FolderItemDto created = catalog.createFolder(new FolderWriteRequest(parentId, segment, null));
      if (created == null || created.id() == null || created.id().isBlank()) {
        throw new IllegalStateException("Catalog did not return an id for new folder " + segment);
      }
      String createdPath = current == null ? segment : current.path() + SEPARATOR + segment;
      current = new Folder(created.id(), segment, parentId, createdPath);
    }
    return current;
  }

  /**
   * The id of the folder {@code reference} names, creating it when it does not exist yet.
   *
   * @return null for the root
   * @throws AmbiguousFolderException when a bare name matches more than one folder
   */
  // shortcut: Serialize this service instance; use catalog atomic creation before deploying multiple replicas.
  public synchronized String resolveOrCreateId(String reference) {
    List<Folder> tree = tree();
    return switch (resolve(reference, tree)) {
      case Resolution.Root root -> null;
      case Resolution.Found found -> found.folder().id();
      case Resolution.Missing missing -> create(missing.path()).id();
      case Resolution.Ambiguous ambiguous ->
          throw new AmbiguousFolderException(reference, paths(ambiguous.candidates()));
    };
  }

  public static List<String> paths(List<Folder> folders) {
    return folders.stream().map(Folder::path).toList();
  }

  private void collectChildren(
      String parentId, String parentPath, List<Folder> folders, Deque<Folder> pending) {
    List<FolderItemDto> items = catalog.listFolder(ListFolderRequest.of(parentId));
    if (items == null) {
      return;
    }
    for (FolderItemDto item : items) {
      if (item == null || !FOLDER_ITEM_TYPE.equals(item.itemType()) || item.id() == null) {
        continue;
      }
      String path = parentPath.isEmpty() ? item.name() : parentPath + SEPARATOR + item.name();
      Folder folder = new Folder(item.id(), item.name(), parentId, path, item.description());
      folders.add(folder);
      pending.addLast(folder);
    }
  }

  private static Folder child(List<Folder> tree, Folder parent, String name) {
    String parentId = parent == null ? null : parent.id();
    return tree.stream()
        .filter(folder -> Objects.equals(folder.parentId(), parentId))
        .filter(folder -> folder.name().equalsIgnoreCase(name))
        .findFirst()
        .orElse(null);
  }

  private static List<String> segments(String reference) {
    if (reference == null) {
      return List.of();
    }
    return Arrays.stream(reference.split(SEPARATOR))
        .map(String::strip)
        .filter(segment -> !segment.isEmpty())
        .toList();
  }
}
