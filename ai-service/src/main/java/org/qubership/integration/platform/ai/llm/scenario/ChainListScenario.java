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
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.FolderWriteRequest;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.MoveFolderRequest;
import org.qubership.integration.platform.ai.llm.agent.ChainListIntentAgent;
import org.qubership.integration.platform.ai.model.ScenarioType;

/**
 * Folders and chain placement on the chains page: find chains by table columns, move them, and
 * create, rename, move, or delete folders.
 *
 * <p>The agent only reads the request. Every folder and chain it names is resolved here, and a
 * name that several folders share is put back to the author as a question. A single named chain
 * moves at once; a move of chains picked by a condition or a folder, any move of more than one
 * chain, and every folder delete wait for a Decision card that lists exactly what changes.
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

  /** A change that waits for its card. Targets are full paths, resolved again when applied. */
  private sealed interface Change {
    record MoveChains(List<Row> chains, String targetPath) implements Change {}

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
    Selection selection = select(command, command.folder() == null ? List.of() : folders.tree());
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
    if (command.namesChainsExplicitly() && moving.size() == 1) {
      String created = target.exists() ? "" : "Created folder " + target.path() + ". ";
      return ChatEvent.token(created + move(moving, target.path()) + notFound(selection));
    }
    String question =
        "Move "
            + count(moving.size(), "chain")
            + " to "
            + display(target.path())
            + "?\n"
            + list(moving)
            + (target.exists()
                ? ""
                : "\nFolder " + target.path() + " does not exist yet and will be created.")
            + notFound(selection);
    return offer(conversationId, new Change.MoveChains(moving, target.path()), question);
  }

  private String move(List<Row> chains, String targetPath) {
    String targetId = targetPath.isEmpty() ? null : folders.resolveOrCreateId("/" + targetPath);
    List<String> failed = new ArrayList<>();
    for (Row chain : chains) {
      try {
        catalog.moveChain(chain.id(), targetId);
      } catch (RuntimeException e) {
        failed.add(chain.display() + ": " + e.getMessage());
      }
    }
    int moved = chains.size() - failed.size();
    String summary = "Moved " + count(moved, "chain") + " to " + display(targetPath) + ".";
    return failed.isEmpty() ? summary : summary + "\nNot moved:\n- " + String.join("\n- ", failed);
  }

  private record Selection(List<Row> rows, List<String> missingNames) {}

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
    String targetId = target.isEmpty() ? null : folders.resolveOrCreateId("/" + target);
    catalog.moveFolder(new MoveFolderRequest(folder.id(), targetId));
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

  /** Where a move lands: a full path, empty for the top level, that may not exist yet. */
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
      return switch (answered.change()) {
        case Change.MoveChains move -> ChatEvent.token(move(move.chains(), move.targetPath()));
        case Change.DeleteFolder delete -> {
          catalog.deleteFolder(delete.folder().id());
          yield ChatEvent.token(
              "Deleted folder "
                  + delete.folder().path()
                  + (delete.chains().isEmpty()
                      ? "."
                      : " and " + count(delete.chains().size(), "chain") + "."));
        }
      };
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
