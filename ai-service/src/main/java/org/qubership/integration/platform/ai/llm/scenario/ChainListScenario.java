package org.qubership.integration.platform.ai.llm.scenario;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chain.folder.CatalogFolders;
import org.qubership.integration.platform.ai.chain.folder.CatalogFolders.Folder;
import org.qubership.integration.platform.ai.chain.folder.CatalogFolders.Resolution;
import org.qubership.integration.platform.ai.chain.folder.ChainListCommand;
import org.qubership.integration.platform.ai.chain.folder.ChainTable;
import org.qubership.integration.platform.ai.chain.folder.ChainTable.Row;
import org.qubership.integration.platform.ai.chain.presentation.ChainContextExtractor;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.TranscriptWindow;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.ChainDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.ChainUpdateRequest;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.FolderWriteRequest;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.MoveFolderRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogChainLabel;
import org.qubership.integration.platform.ai.llm.agent.ChainListIntentAgent;
import org.qubership.integration.platform.ai.model.ScenarioType;

/**
 * The chains page from chat: find chains by table columns; move, copy, rename, describe, label,
 * export, or delete them; and create, rename, move, or delete folders.
 *
 * <p>The agent only reads the request. Every folder and chain it names is resolved here, and a
 * name that several folders or chains share is put back to the author as a question. A change to
 * one chain the author named runs at once. A change to several chains, or to chains picked by a
 * condition or a folder, every chain delete, and every folder delete wait for a Decision card that
 * lists exactly what changes.
 */
@ApplicationScoped
@ForScenario(ScenarioType.MANAGE_CHAIN_LIST)
public class ChainListScenario implements ScenarioHandler {

  static final String CARD_GONE_MESSAGE =
      "That change is no longer pending. Ask again to see the current chains and folders.";

  private static final Logger LOG = Logger.getLogger(ChainListScenario.class);
  private static final int LISTED_CHAINS = 30;

  private final ChainListIntentAgent agent;
  private final CatalogFolders folders;
  private final ChainTable table;
  private final CatalogRestClient catalog;
  private final ConversationService conversations;
  private final ChainContextExtractor chainContext;

  /** ponytail: in memory like the deploy cards; a restart drops unanswered cards. */
  private final Map<String, Pending> pending = new ConcurrentHashMap<>();

  @Inject
  public ChainListScenario(
      ChainListIntentAgent agent,
      CatalogFolders folders,
      ChainTable table,
      @RestClient CatalogRestClient catalog,
      ConversationService conversations,
      ChainContextExtractor chainContext) {
    this.agent = agent;
    this.folders = folders;
    this.table = table;
    this.catalog = catalog;
    this.conversations = conversations;
    this.chainContext = chainContext;
  }

  private record Pending(String operationId, Change change) {}

  /**
   * A catalog change, run at once or after its card. Target folders are full paths, resolved again
   * when the change runs; a null copy target copies each chain into its own folder.
   */
  private sealed interface Change {
    record MoveChains(List<Row> chains, String targetPath) implements Change {}

    record CopyChains(List<Row> chains, String targetPath) implements Change {}

    record DescribeChains(List<Row> chains, String description) implements Change {}

    record LabelChains(List<Row> chains, List<String> add, List<String> remove)
        implements Change {}

    record DeleteChains(List<Row> chains) implements Change {}

    record DeleteFolder(Folder folder, List<Row> chains) implements Change {}
  }

  /** A reply that ends the turn early: a question back to the author or a reason to stop. */
  private static final class Answer extends RuntimeException {
    Answer(String text) {
      super(text, null, false, false);
    }
  }

  @Override
  public Multi<ChatEvent> handle(
      ChatRequest request, String conversationId, ScenarioType scenarioType) {
    return Multi.createFrom()
        .item(() -> dispatch(request, conversationId))
        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
  }

  ChatEvent dispatch(ChatRequest request, String conversationId) {
    ChatDecisionCommand decision = request == null ? null : request.getDecision();
    if (decision != null && ChatEvent.APPLY_CHAIN_LIST_CHANGE_ACTION.equals(decision.getAction())) {
      return apply(conversationId, decision);
    }
    if (decision != null
        && ChatEvent.CANCEL_CHAIN_LIST_CHANGE_ACTION.equals(decision.getAction())) {
      return cancel(conversationId, decision);
    }
    String userText = request == null ? "" : request.getEffectiveUserText();
    ChainListCommand command =
        agent.resolve(
            chainContext.resolveChainId(request, conversationId).orElse(""),
            TranscriptWindow.format(conversations.getMessages(conversationId)),
            userText);
    LOG.infof("MANAGE_CHAIN_LIST conversationId=%s action=%s", conversationId, command.action());
    try {
      return switch (command.action()) {
        case FIND_CHAINS -> findChains(command);
        case MOVE_CHAINS -> moveChains(conversationId, command);
        case COPY_CHAINS -> copyChains(conversationId, command);
        case RENAME_CHAIN -> renameChain(command);
        case SET_DESCRIPTION -> describeChains(conversationId, command);
        case EDIT_LABELS -> labelChains(conversationId, command);
        case DELETE_CHAINS -> deleteChains(conversationId, command);
        case EXPORT_CHAINS -> exportChains(command);
        case CREATE_FOLDER -> createFolder(command);
        case RENAME_FOLDER -> renameFolder(command);
        case MOVE_FOLDER -> moveFolder(command);
        case DELETE_FOLDER -> deleteFolder(conversationId, command);
        case CLARIFY ->
            ChatEvent.token(
                command.question() != null
                    ? command.question()
                    : "Which chains or folders do you mean, and what should happen to them?");
      };
    } catch (Answer answer) {
      return ChatEvent.token(answer.getMessage());
    } catch (IllegalArgumentException invalid) {
      return ChatEvent.token(invalid.getMessage());
    } catch (RuntimeException failure) {
      LOG.warnf(failure, "MANAGE_CHAIN_LIST catalog call failed conversationId=%s", conversationId);
      return ChatEvent.token("The catalog did not accept the change: " + failure.getMessage());
    }
  }

  // ── Chains ────────────────────────────────────────────────────────────

  private ChatEvent findChains(ChainListCommand command) {
    Selection selection = select(command, sourceTree(command));
    if (selection.rows().isEmpty()) {
      return ChatEvent.token("No chains match." + notFound(selection));
    }
    return ChatEvent.token(
        count(selection.rows().size(), "chain") + " match:\n" + list(selection.rows())
            + notFound(selection));
  }

  private ChatEvent moveChains(String conversationId, ChainListCommand command) {
    List<Folder> tree = folders.tree();
    Selection selection = select(command, tree);
    Target target = destination(command.targetFolder(), tree);
    List<Row> moving =
        selection.rows().stream().filter(row -> !row.folderPath().equals(target.path())).toList();
    if (moving.isEmpty()) {
      return ChatEvent.token(
          (selection.rows().isEmpty()
                  ? "No chains match."
                  : "Those chains are already in " + display(target.path()) + ".")
              + notFound(selection));
    }
    return runOrOffer(
        conversationId,
        command,
        selection.withRows(moving),
        new Change.MoveChains(moving, target.path()),
        "Move " + count(moving.size(), "chain") + " to " + display(target.path()) + "?",
        target);
  }

  private ChatEvent copyChains(String conversationId, ChainListCommand command) {
    List<Folder> tree = folders.tree();
    Selection selection = requireChains(select(command, tree));
    if (command.targetFolder() == null) {
      return runOrOffer(
          conversationId,
          command,
          selection,
          new Change.CopyChains(selection.rows(), null),
          "Duplicate " + count(selection.rows().size(), "chain") + " in their folders?",
          null);
    }
    Target target = destination(command.targetFolder(), tree);
    return runOrOffer(
        conversationId,
        command,
        selection,
        new Change.CopyChains(selection.rows(), target.path()),
        "Copy " + count(selection.rows().size(), "chain") + " to " + display(target.path()) + "?",
        target);
  }

  private ChatEvent renameChain(ChainListCommand command) {
    Selection selection = requireChains(select(command, sourceTree(command)));
    if (selection.rows().size() > 1) {
      throw new Answer(
          "Several chains match:\n" + list(selection.rows()) + "\nWhich one should be renamed?");
    }
    if (command.newName() == null) {
      throw new Answer("What should the chain be called?");
    }
    Row chain = selection.rows().get(0);
    catalog.updateChain(chain.id(), new ChainUpdateRequest(command.newName(), null, null));
    return ChatEvent.token("Renamed chain " + chain.display() + " to " + command.newName() + ".");
  }

  private ChatEvent describeChains(String conversationId, ChainListCommand command) {
    if (command.description() == null) {
      throw new Answer("What should the description say?");
    }
    Selection selection = requireChains(select(command, sourceTree(command)));
    return runOrOffer(
        conversationId,
        command,
        selection,
        new Change.DescribeChains(selection.rows(), command.description()),
        "Set the description of "
            + count(selection.rows().size(), "chain")
            + " to \""
            + command.description()
            + "\"?",
        null);
  }

  private ChatEvent labelChains(String conversationId, ChainListCommand command) {
    if (command.addLabels().isEmpty() && command.removeLabels().isEmpty()) {
      throw new Answer("Which labels should be added or removed?");
    }
    Selection selection = requireChains(select(command, sourceTree(command)));
    List<String> edits = new ArrayList<>();
    if (!command.addLabels().isEmpty()) {
      edits.add("add " + String.join(", ", command.addLabels()));
    }
    if (!command.removeLabels().isEmpty()) {
      edits.add("remove " + String.join(", ", command.removeLabels()));
    }
    return runOrOffer(
        conversationId,
        command,
        selection,
        new Change.LabelChains(selection.rows(), command.addLabels(), command.removeLabels()),
        "On " + count(selection.rows().size(), "chain") + ", " + String.join(" and ", edits) + "?",
        null);
  }

  private ChatEvent deleteChains(String conversationId, ChainListCommand command) {
    Selection selection = requireChains(select(command, sourceTree(command)));
    String question =
        "Delete "
            + count(selection.rows().size(), "chain")
            + "? Their deployments are removed too, and this cannot be undone.\n"
            + list(selection.rows())
            + notFound(selection);
    return offer(conversationId, new Change.DeleteChains(selection.rows()), question);
  }

  /**
   * Offers the chains for download. Nothing changes in the catalog, so there is nothing to confirm
   * here: the card's button opens the chains page export dialog in the browser.
   */
  private ChatEvent exportChains(ChainListCommand command) {
    Selection selection = requireChains(select(command, sourceTree(command)));
    return ChatEvent.chainExportDecision(
        UUID.randomUUID().toString(),
        "Export " + count(selection.rows().size(), "chain") + "?\n" + list(selection.rows())
            + notFound(selection),
        selection.rows().stream().map(Row::id).toList());
  }

  /**
   * Runs a change to one chain the author named, and puts anything wider on a card.
   *
   * <p>"Wider" means more than one chain, or chains picked by a condition or a folder: the author
   * has not seen that list yet, so the card is the first time they do.
   */
  private ChatEvent runOrOffer(
      String conversationId,
      ChainListCommand command,
      Selection selection,
      Change change,
      String question,
      Target target) {
    boolean creates = target != null && !target.exists();
    if (command.namesChainsExplicitly() && selection.rows().size() == 1) {
      String created = creates ? "Created folder " + target.path() + ". " : "";
      return ChatEvent.token(created + run(change) + notFound(selection));
    }
    String note =
        creates ? "\nFolder " + target.path() + " does not exist yet and will be created." : "";
    return offer(
        conversationId,
        change,
        question + "\n" + list(selection.rows()) + note + notFound(selection));
  }

  private record Selection(List<Row> rows, List<String> missingNames) {
    Selection withRows(List<Row> narrowed) {
      return new Selection(narrowed, missingNames);
    }
  }

  /** {@code tree} is read only when the command limits the chains to a folder. */
  private Selection select(ChainListCommand command, List<Folder> tree) {
    if (command.chainIds().isEmpty()
        && command.chainNames().isEmpty()
        && command.filters().isEmpty()
        && command.folder() == null) {
      throw new Answer("Which chains do you mean? Name them, or describe them by a column.");
    }
    List<Row> rows = table.find(command.filters());
    if (command.folder() != null) {
      rows = ChainTable.inFolder(rows, existingFolder(command.folder(), tree).path());
    }
    if (!command.chainIds().isEmpty() || !command.chainNames().isEmpty()) {
      rows = rows.stream().filter(row -> named(command, row)).toList();
    }
    List<Row> found = rows;
    List<String> missing =
        command.chainNames().stream()
            .filter(name -> found.stream().noneMatch(row -> row.name().equalsIgnoreCase(name)))
            .toList();
    return new Selection(rows, missing);
  }

  private List<Folder> sourceTree(ChainListCommand command) {
    return command.folder() == null ? List.of() : folders.tree();
  }

  private static Selection requireChains(Selection selection) {
    if (selection.rows().isEmpty()) {
      throw new Answer("No chains match." + notFound(selection));
    }
    return selection;
  }

  private static boolean named(ChainListCommand command, Row row) {
    return command.chainIds().contains(row.id())
        || command.chainNames().stream().anyMatch(name -> name.equalsIgnoreCase(row.name()));
  }

  // ── Folders ───────────────────────────────────────────────────────────

  private ChatEvent createFolder(ChainListCommand command) {
    if (command.folder() == null) {
      throw new Answer("What should the new folder be called?");
    }
    List<Folder> tree = folders.tree();
    // A bare name is a new top-level folder here, not a reference to one somewhere in the tree.
    String fromRoot = command.folder().startsWith("/") ? command.folder() : "/" + command.folder();
    return switch (CatalogFolders.resolve(fromRoot, tree)) {
      case Resolution.Found found ->
          ChatEvent.token("Folder " + found.folder().path() + " already exists.");
      case Resolution.Missing missing ->
          ChatEvent.token("Created folder " + folders.create(missing.path(), tree).path() + ".");
      default -> throw new Answer("What should the new folder be called?");
    };
  }

  private ChatEvent renameFolder(ChainListCommand command) {
    List<Folder> tree = folders.tree();
    Folder folder = existingFolder(command.folder(), tree);
    String name = command.newName();
    if (name == null) {
      throw new Answer("What should folder " + folder.path() + " be called?");
    }
    if (name.contains("/")) {
      throw new Answer("A folder name cannot contain /. Move the folder to change where it is.");
    }
    boolean taken =
        tree.stream()
            .anyMatch(
                other ->
                    !other.id().equals(folder.id())
                        && Objects.equals(other.parentId(), folder.parentId())
                        && other.name().equalsIgnoreCase(name));
    if (taken) {
      throw new Answer("A folder named " + name + " already exists next to " + folder.path() + ".");
    }
    catalog.updateFolder(folder.id(), new FolderWriteRequest(null, name, folder.description()));
    return ChatEvent.token("Renamed folder " + folder.path() + " to " + name + ".");
  }

  private ChatEvent moveFolder(ChainListCommand command) {
    List<Folder> tree = folders.tree();
    Folder folder = existingFolder(command.folder(), tree);
    String target = destination(command.targetFolder(), tree).path();
    if (target.equals(folder.path()) || target.startsWith(folder.path() + "/")) {
      throw new Answer("Folder " + folder.path() + " cannot move into itself.");
    }
    catalog.moveFolder(new MoveFolderRequest(folder.id(), targetId(target)));
    return ChatEvent.token("Moved folder " + folder.path() + " to " + display(target) + ".");
  }

  private ChatEvent deleteFolder(String conversationId, ChainListCommand command) {
    Folder folder = existingFolder(command.folder(), folders.tree());
    List<Row> chains = ChainTable.inFolder(table.find(List.of()), folder.path());
    String question =
        chains.isEmpty()
            ? "Delete folder " + folder.path() + " and the folders inside it? It holds no chains."
            : "Delete folder "
                + folder.path()
                + "? This also deletes and undeploys "
                + count(chains.size(), "chain")
                + ":\n"
                + list(chains);
    return offer(conversationId, new Change.DeleteFolder(folder, chains), question);
  }

  private static Folder existingFolder(String reference, List<Folder> tree) {
    if (reference == null) {
      throw new Answer("Which folder do you mean?");
    }
    return switch (CatalogFolders.resolve(reference, tree)) {
      case Resolution.Found found -> found.folder();
      case Resolution.Missing missing ->
          throw new Answer("There is no folder " + missing.path() + ".");
      case Resolution.Ambiguous ambiguous -> throw new Answer(ambiguous(reference, ambiguous));
      case Resolution.Root root -> throw new Answer("Which folder do you mean?");
    };
  }

  /** Where a move or copy lands: a full path, empty for the top level, that may not exist yet. */
  private record Target(String path, boolean exists) {}

  private static Target destination(String reference, List<Folder> tree) {
    if (reference == null) {
      throw new Answer("Where should they go? Name a folder, or say the top level.");
    }
    return switch (CatalogFolders.resolve(reference, tree)) {
      case Resolution.Root root -> new Target("", true);
      case Resolution.Found found -> new Target(found.folder().path(), true);
      case Resolution.Missing missing -> new Target(missing.path(), false);
      case Resolution.Ambiguous ambiguous -> throw new Answer(ambiguous(reference, ambiguous));
    };
  }

  /** The folder id for a full path, creating it when it is missing; null for the top level. */
  private String targetId(String path) {
    return path.isEmpty() ? null : folders.resolveOrCreateId("/" + path);
  }

  // ── Running changes ───────────────────────────────────────────────────

  private String run(Change change) {
    return switch (change) {
      case Change.MoveChains move -> {
        String targetId = targetId(move.targetPath());
        yield eachChain(
            move.chains(),
            chain -> catalog.moveChain(chain.id(), targetId),
            "Moved %s to " + display(move.targetPath()) + ".");
      }
      case Change.CopyChains copy when copy.targetPath() == null ->
          eachChain(
              copy.chains(),
              chain -> catalog.duplicateChain(chain.id()),
              "Duplicated %s in their folders.");
      case Change.CopyChains copy -> {
        String targetId = targetId(copy.targetPath());
        yield eachChain(
            copy.chains(),
            chain -> catalog.copyChain(chain.id(), targetId),
            "Copied %s to " + display(copy.targetPath()) + ".");
      }
      case Change.DescribeChains describe ->
          eachChain(
              describe.chains(),
              chain ->
                  catalog.updateChain(
                      chain.id(), new ChainUpdateRequest(null, describe.description(), null)),
              "Updated the description of %s.");
      case Change.LabelChains label ->
          eachChain(label.chains(), chain -> relabel(chain, label), "Updated the labels of %s.");
      case Change.DeleteChains delete ->
          eachChain(delete.chains(), chain -> catalog.deleteChain(chain.id()), "Deleted %s.");
      case Change.DeleteFolder delete -> {
        catalog.deleteFolder(delete.folder().id());
        yield "Deleted folder "
            + delete.folder().path()
            + (delete.chains().isEmpty()
                ? "."
                : " and " + count(delete.chains().size(), "chain") + ".");
      }
    };
  }

  /**
   * Applies the label edit to the chain's labels as they are now, not as they were when the card
   * was shown: the catalog replaces the whole set, and someone may have changed it since.
   */
  private void relabel(Row chain, Change.LabelChains edit) {
    ChainDto current = catalog.getChain(chain.id());
    List<String> names = new ArrayList<>();
    if (current.labels() != null) {
      current.labels().stream()
          .filter(label -> !label.technical())
          .forEach(label -> names.add(label.name()));
    }
    List<String> before = List.copyOf(names);
    names.removeIf(name -> edit.remove().stream().anyMatch(name::equalsIgnoreCase));
    for (String added : edit.add()) {
      if (names.stream().noneMatch(added::equalsIgnoreCase)) {
        names.add(added);
      }
    }
    if (!names.equals(before)) {
      List<CatalogChainLabel> labels =
          names.stream().map(name -> new CatalogChainLabel(name, false)).toList();
      catalog.updateChain(chain.id(), new ChainUpdateRequest(null, null, labels));
    }
  }

  /**
   * Runs {@code action} on each chain and reports what landed. One chain failing does not stop
   * the rest, because each catalog call is its own transaction and the earlier ones have landed.
   */
  private static String eachChain(List<Row> chains, Consumer<Row> action, String summary) {
    List<String> failed = new ArrayList<>();
    for (Row chain : chains) {
      try {
        action.accept(chain);
      } catch (RuntimeException e) {
        failed.add(chain.display() + ": " + e.getMessage());
      }
    }
    String done = summary.formatted(count(chains.size() - failed.size(), "chain"));
    return failed.isEmpty() ? done : done + "\nFailed:\n- " + String.join("\n- ", failed);
  }

  // ── Cards ─────────────────────────────────────────────────────────────

  private ChatEvent offer(String conversationId, Change change, String question) {
    String operationId = UUID.randomUUID().toString();
    pending.put(conversationId, new Pending(operationId, change));
    return ChatEvent.chainListChangeDecision(operationId, question);
  }

  private ChatEvent apply(String conversationId, ChatDecisionCommand decision) {
    Pending answered = take(conversationId, decision);
    if (answered == null) {
      return ChatEvent.token(CARD_GONE_MESSAGE);
    }
    try {
      return ChatEvent.token(run(answered.change()));
    } catch (RuntimeException failure) {
      LOG.warnf(failure, "MANAGE_CHAIN_LIST apply failed conversationId=%s", conversationId);
      return ChatEvent.token("The catalog did not accept the change: " + failure.getMessage());
    }
  }

  private ChatEvent cancel(String conversationId, ChatDecisionCommand decision) {
    return take(conversationId, decision) == null
        ? ChatEvent.token(CARD_GONE_MESSAGE)
        : ChatEvent.token("Nothing changed.");
  }

  /** The pending change this card was issued for, removed so the card cannot be answered twice. */
  private Pending take(String conversationId, ChatDecisionCommand decision) {
    Pending current = pending.get(conversationId);
    if (current == null || !current.operationId().equals(decision.getArtifactHash())) {
      return null;
    }
    return pending.remove(conversationId, current) ? current : null;
  }

  // ── Text ──────────────────────────────────────────────────────────────

  private static String ambiguous(String reference, Resolution.Ambiguous ambiguous) {
    return "Several folders are named "
        + reference
        + ": "
        + String.join(", ", CatalogFolders.paths(ambiguous.candidates()))
        + ". Which one do you mean? Reply with its full path.";
  }

  private static String list(List<Row> rows) {
    StringBuilder text = new StringBuilder();
    rows.stream()
        .limit(LISTED_CHAINS)
        .forEach(row -> text.append("- ").append(row.display()).append('\n'));
    if (rows.size() > LISTED_CHAINS) {
      text.append("- and ").append(rows.size() - LISTED_CHAINS).append(" more\n");
    }
    return text.toString().stripTrailing();
  }

  private static String notFound(Selection selection) {
    return selection.missingNames().isEmpty()
        ? ""
        : "\nNo chain is named " + String.join(", ", selection.missingNames()) + ".";
  }

  private static String count(int count, String noun) {
    return count + " " + noun + (count == 1 ? "" : "s");
  }

  private static String display(String path) {
    return path.isEmpty() ? "the top level" : path;
  }
}
