package org.qubership.integration.platform.ai.llm.scenario;

import static java.util.stream.Stream.concat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chain.folder.CatalogFolders;
import org.qubership.integration.platform.ai.chain.folder.CatalogFolders.Folder;
import org.qubership.integration.platform.ai.chain.folder.CatalogFolders.Resolution;
import org.qubership.integration.platform.ai.chain.folder.ChainListAction;
import org.qubership.integration.platform.ai.chain.folder.ChainListCommand;
import org.qubership.integration.platform.ai.chain.folder.ChainListCommand.Grouping;
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
import org.qubership.integration.platform.ai.llm.agent.ChainListReplyAgent;
import org.qubership.integration.platform.ai.model.ScenarioType;

/**
 * The chains page from chat: find and count chains by table columns; move, copy, rename,
 * describe, label, export, or delete them; and create, rename, move, or delete folders.
 *
 * <p>The intent agent only reads the request. Every folder and chain it names is resolved here,
 * and a name that several folders or chains share is put back to the author as a question. A
 * change to one chain the author named runs at once. A change to several chains, or to chains
 * picked by a condition or a folder, every chain delete, and every folder delete wait for a
 * Decision card that lists exactly what changes.
 *
 * <p>Java writes no prose for the author. Each turn ends in facts, in English, that the reply
 * agent words in the author's language, and in data (the numbered list, the counts, the chains a
 * card changes) that Java puts under that text unchanged.
 */
@ApplicationScoped
@ForScenario(ScenarioType.MANAGE_CHAIN_LIST)
public class ChainListScenario implements ScenarioHandler {

  static final String CARD_GONE_FACTS =
      "That change is no longer pending. The user can ask again to see the current chains and"
          + " folders.";

  private static final Logger LOG = Logger.getLogger(ChainListScenario.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int LISTED_CHAINS = 30;
  private static final int CHAINS_FOR_THE_REPLY = 100;
  /** Marks an empty group in counts: no labels, no author, no date. */
  private static final String NONE = "—";

  private final ChainListIntentAgent agent;
  private final ChainListReplyAgent replies;
  private final CatalogFolders folders;
  private final ChainTable table;
  private final CatalogRestClient catalog;
  private final ConversationService conversations;
  private final ChainContextExtractor chainContext;

  /** ponytail: in memory like the deploy cards; a restart drops unanswered cards. */
  private final Map<String, Pending> pending = new ConcurrentHashMap<>();

  /**
   * Chain ids of the last list each chat showed, in the order shown. Only a search replaces it,
   * so the author can act on part of a list and then pick from the same numbers again.
   *
   * <p>ponytail: in memory like the cards; after a restart the author searches again.
   */
  private final Map<String, List<String>> listed = new ConcurrentHashMap<>();

  @Inject
  public ChainListScenario(
      ChainListIntentAgent agent,
      ChainListReplyAgent replies,
      CatalogFolders folders,
      ChainTable table,
      @RestClient CatalogRestClient catalog,
      ConversationService conversations,
      ChainContextExtractor chainContext) {
    this.agent = agent;
    this.replies = replies;
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

  /**
   * What a turn produced. {@code facts} tells the reply agent what happened, in English, and
   * stands in for its text when the agent fails. {@code shown} goes under that text unchanged, so
   * no model retells numbers or names, and {@code chains} lets the agent answer questions about
   * the listed chains. {@code event} wraps the final text as a message or a card.
   */
  private record Outcome(
      String facts, String shown, List<Row> chains, Function<String, ChatEvent> event) {

    static Outcome text(String facts) {
      return text(facts, "");
    }

    static Outcome text(String facts, String shown) {
      return new Outcome(facts, shown, List.of(), ChatEvent::token);
    }
  }

  /** Ends the turn early: facts for a question back to the author or a reason to stop. */
  private static final class Answer extends RuntimeException {
    private final String shown;

    Answer(String facts) {
      this(facts, "");
    }

    Answer(String facts, String shown) {
      super(facts, null, false, false);
      this.shown = shown;
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
    String transcript = TranscriptWindow.format(conversations.getMessages(conversationId));
    String userText = request == null ? "" : request.getEffectiveUserText();
    Outcome outcome;
    if (decision != null && ChatEvent.APPLY_CHAIN_LIST_CHANGE_ACTION.equals(decision.getAction())) {
      outcome = apply(conversationId, decision);
    } else if (decision != null
        && ChatEvent.CANCEL_CHAIN_LIST_CHANGE_ACTION.equals(decision.getAction())) {
      outcome =
          take(conversationId, decision) == null
              ? Outcome.text(CARD_GONE_FACTS)
              : Outcome.text("The user cancelled the change. Nothing changed.");
    } else {
      ChainListCommand command =
          agent.resolve(
              LocalDate.now(ZoneOffset.UTC).toString(),
              chainContext.resolveChainId(request, conversationId).orElse(""),
              transcript,
              userText);
      LOG.infof("MANAGE_CHAIN_LIST conversationId=%s action=%s", conversationId, command.action());
      if (command.action() == ChainListAction.CLARIFY && command.question() != null) {
        // The intent agent already wrote it in the author's language.
        return ChatEvent.token(command.question());
      }
      outcome = answer(conversationId, command);
    }
    return say(outcome, transcript, userText);
  }

  private Outcome answer(String conversationId, ChainListCommand command) {
    try {
      return switch (command.action()) {
        case FIND_CHAINS -> findChains(conversationId, command);
        case MOVE_CHAINS -> moveChains(conversationId, command);
        case COPY_CHAINS -> copyChains(conversationId, command);
        case RENAME_CHAIN -> renameChain(conversationId, command);
        case SET_DESCRIPTION -> describeChains(conversationId, command);
        case EDIT_LABELS -> labelChains(conversationId, command);
        case DELETE_CHAINS -> deleteChains(conversationId, command);
        case EXPORT_CHAINS -> exportChains(conversationId, command);
        case CREATE_FOLDER -> createFolder(command);
        case RENAME_FOLDER -> renameFolder(command);
        case MOVE_FOLDER -> moveFolder(command);
        case DELETE_FOLDER -> deleteFolder(conversationId, command);
        case CLARIFY ->
            Outcome.text(
                "The request does not say which chains or folders it means, or what should"
                    + " happen to them. Ask.");
      };
    } catch (Answer answer) {
      return Outcome.text(answer.getMessage(), answer.shown);
    } catch (IllegalArgumentException invalid) {
      return Outcome.text(invalid.getMessage());
    } catch (RuntimeException failure) {
      LOG.warnf(failure, "MANAGE_CHAIN_LIST catalog call failed conversationId=%s", conversationId);
      return Outcome.text("The catalog request failed: " + failure.getMessage());
    }
  }

  /**
   * Has the reply agent word the outcome in the author's language and puts Java's data under it.
   * When the agent fails, the facts go out as they are: a change may already have run, and its
   * result must reach the author.
   */
  private ChatEvent say(Outcome outcome, String transcript, String userText) {
    String text = null;
    try {
      text = replies.reply(transcript, userText, facts(outcome));
    } catch (RuntimeException failure) {
      LOG.warnf(failure, "MANAGE_CHAIN_LIST reply agent failed; sending the facts as they are");
    }
    String lead = text == null || text.isBlank() ? outcome.facts() : text.strip();
    return outcome.event().apply(outcome.shown().isEmpty() ? lead : lead + "\n" + outcome.shown());
  }

  // ── Chains ────────────────────────────────────────────────────────────

  /**
   * Answers with a numbered list, or with counts per group, and keeps the chains as this chat's
   * list for the next request to pick from.
   */
  private Outcome findChains(String conversationId, ChainListCommand command) {
    Selection selection = select(conversationId, command, sourceTree(command));
    List<Row> rows = selection.rows();
    listed.put(conversationId, rows.stream().map(Row::id).toList());
    if (rows.isEmpty()) {
      return Outcome.text("No chains match." + notFound(selection));
    }
    String found = "Found " + count(rows.size(), "chain") + ".";
    Grouping grouping = command.groupBy();
    if (grouping == null) {
      String more =
          rows.size() > LISTED_CHAINS
              ? " The list shows the first " + LISTED_CHAINS + "; a narrower search shows the rest."
              : "";
      return new Outcome(
          found + more + notFound(selection), numbered(rows), rows, ChatEvent::token);
    }
    boolean overlapping =
        rows.stream().mapToInt(row -> groupKeys(row, grouping).size()).sum() > rows.size();
    String counted =
        " Counted by "
            + groupName(grouping)
            + (overlapping
                ? "; a chain can count under more than one " + groupName(grouping) + "."
                : ".");
    return new Outcome(
        found + counted + notFound(selection), groups(rows, grouping), rows, ChatEvent::token);
  }

  private Outcome moveChains(String conversationId, ChainListCommand command) {
    List<Folder> tree = folders.tree();
    Selection selection = select(conversationId, command, tree);
    Target target = destination(command.targetFolder(), tree);
    List<Row> moving =
        selection.rows().stream().filter(row -> !row.folderPath().equals(target.path())).toList();
    if (moving.isEmpty()) {
      return Outcome.text(
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
        "Confirm: move " + count(moving.size(), "chain") + " to " + display(target.path()) + ".",
        target);
  }

  private Outcome copyChains(String conversationId, ChainListCommand command) {
    List<Folder> tree = folders.tree();
    Selection selection = requireChains(select(conversationId, command, tree));
    if (command.targetFolder() == null) {
      return runOrOffer(
          conversationId,
          command,
          selection,
          new Change.CopyChains(selection.rows(), null),
          "Confirm: duplicate " + count(selection.rows().size(), "chain") + ", each in its folder.",
          null);
    }
    Target target = destination(command.targetFolder(), tree);
    return runOrOffer(
        conversationId,
        command,
        selection,
        new Change.CopyChains(selection.rows(), target.path()),
        "Confirm: copy "
            + count(selection.rows().size(), "chain")
            + " to "
            + display(target.path())
            + ".",
        target);
  }

  private Outcome renameChain(String conversationId, ChainListCommand command) {
    Selection selection = requireChains(select(conversationId, command, sourceTree(command)));
    if (selection.rows().size() > 1) {
      throw new Answer(
          "Several chains match. Ask which one should be renamed.", list(selection.rows()));
    }
    if (command.newName() == null) {
      throw new Answer("Ask what the chain should be called.");
    }
    Row chain = selection.rows().get(0);
    catalog.updateChain(chain.id(), new ChainUpdateRequest(command.newName(), null, null));
    return Outcome.text("Renamed chain " + chain.display() + " to " + command.newName() + ".");
  }

  private Outcome describeChains(String conversationId, ChainListCommand command) {
    if (command.description() == null) {
      throw new Answer("Ask what the description should say.");
    }
    Selection selection = requireChains(select(conversationId, command, sourceTree(command)));
    return runOrOffer(
        conversationId,
        command,
        selection,
        new Change.DescribeChains(selection.rows(), command.description()),
        "Confirm: set the description of "
            + count(selection.rows().size(), "chain")
            + " to \""
            + command.description()
            + "\".",
        null);
  }

  private Outcome labelChains(String conversationId, ChainListCommand command) {
    if (command.addLabels().isEmpty() && command.removeLabels().isEmpty()) {
      throw new Answer("Ask which labels to add or remove.");
    }
    Selection selection = requireChains(select(conversationId, command, sourceTree(command)));
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
        "Confirm: on "
            + count(selection.rows().size(), "chain")
            + ", "
            + String.join(" and ", edits)
            + ".",
        null);
  }

  private Outcome deleteChains(String conversationId, ChainListCommand command) {
    Selection selection = requireChains(select(conversationId, command, sourceTree(command)));
    return offer(
        conversationId,
        new Change.DeleteChains(selection.rows()),
        "Confirm: delete "
            + count(selection.rows().size(), "chain")
            + ". Their deployments are removed too, and this cannot be undone."
            + notFound(selection),
        list(selection.rows()));
  }

  /**
   * Offers the chains for download. Nothing changes in the catalog, so there is nothing to confirm
   * here: the card's button opens the chains page export dialog in the browser.
   */
  private Outcome exportChains(String conversationId, ChainListCommand command) {
    Selection selection = requireChains(select(conversationId, command, sourceTree(command)));
    List<String> ids = selection.rows().stream().map(Row::id).toList();
    String operationId = UUID.randomUUID().toString();
    return new Outcome(
        "Offer "
            + count(ids.size(), "chain")
            + " for download. The card's button opens the export dialog in the browser."
            + notFound(selection),
        list(selection.rows()),
        List.of(),
        text -> ChatEvent.chainExportDecision(operationId, text, ids));
  }

  /**
   * Runs a change to one chain the author named, and puts anything wider on a card.
   *
   * <p>"Wider" means more than one chain, or chains picked by a condition or a folder: the author
   * has not seen that list yet, so the card is the first time they do.
   */
  private Outcome runOrOffer(
      String conversationId,
      ChainListCommand command,
      Selection selection,
      Change change,
      String confirm,
      Target target) {
    boolean creates = target != null && !target.exists();
    if (command.namesChainsExplicitly() && selection.rows().size() == 1) {
      String created = creates ? "Created folder " + target.path() + ". " : "";
      return Outcome.text(created + run(change) + notFound(selection));
    }
    String note =
        creates ? " Folder " + target.path() + " does not exist yet and will be created." : "";
    return offer(conversationId, change, confirm + note + notFound(selection), list(selection.rows()));
  }

  private record Selection(List<Row> rows, List<String> missingNames) {
    Selection withRows(List<Row> narrowed) {
      return new Selection(narrowed, missingNames);
    }
  }

  /**
   * The chains the command points at. A search with no selectors takes every chain; a change
   * needs at least one. {@code tree} is read only when the command limits the chains to a folder.
   */
  private Selection select(String conversationId, ChainListCommand command, List<Folder> tree) {
    if (command.action() != ChainListAction.FIND_CHAINS
        && !command.fromList()
        && command.chainIds().isEmpty()
        && command.chainNames().isEmpty()
        && command.filters().isEmpty()
        && command.folder() == null) {
      throw new Answer(
          "The request does not say which chains. Ask the user to name them or describe them by"
              + " a column such as name, label, or creation date.");
    }
    List<Row> rows = table.find(command.filters());
    if (command.fromList()) {
      rows = fromList(conversationId, command, rows);
    }
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

  /**
   * The chains of this chat's last list that the command picks by number, in list order, among
   * {@code rows}. A chain deleted since the list was shown drops out.
   */
  private List<Row> fromList(String conversationId, ChainListCommand command, List<Row> rows) {
    List<String> ids = listed.get(conversationId);
    if (ids == null) {
      throw new Answer(
          "This chat has no chain list to pick from yet. Ask which chains the user means, by name"
              + " or by a column.");
    }
    for (int position :
        concat(command.positions().stream(), command.excludePositions().stream()).toList()) {
      if (position < 1 || position > ids.size()) {
        throw new Answer(
            "The last list has " + count(ids.size(), "chain") + ", so there is no number "
                + position + ".");
      }
    }
    Map<String, Row> current = new HashMap<>();
    rows.forEach(row -> current.put(row.id(), row));
    List<Row> picked = new ArrayList<>();
    for (int position = 1; position <= ids.size(); position++) {
      Row row = current.get(ids.get(position - 1));
      if (row != null
          && (command.positions().isEmpty() || command.positions().contains(position))
          && !command.excludePositions().contains(position)) {
        picked.add(row);
      }
    }
    return picked;
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

  private Outcome createFolder(ChainListCommand command) {
    if (command.folder() == null) {
      throw new Answer("Ask what the new folder should be called.");
    }
    List<Folder> tree = folders.tree();
    // A bare name is a new top-level folder here, not a reference to one somewhere in the tree.
    String fromRoot = command.folder().startsWith("/") ? command.folder() : "/" + command.folder();
    return switch (CatalogFolders.resolve(fromRoot, tree)) {
      case Resolution.Found found ->
          Outcome.text("Folder " + found.folder().path() + " already exists.");
      case Resolution.Missing missing ->
          Outcome.text("Created folder " + folders.create(missing.path()).path() + ".");
      default -> throw new Answer("Ask what the new folder should be called.");
    };
  }

  private Outcome renameFolder(ChainListCommand command) {
    List<Folder> tree = folders.tree();
    Folder folder = existingFolder(command.folder(), tree);
    String name = command.newName();
    if (name == null) {
      throw new Answer("Ask what folder " + folder.path() + " should be called.");
    }
    if (name.contains("/")) {
      throw new Answer(
          "A folder name cannot contain /. Moving the folder is what changes where it is.");
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
    return Outcome.text("Renamed folder " + folder.path() + " to " + name + ".");
  }

  private Outcome moveFolder(ChainListCommand command) {
    List<Folder> tree = folders.tree();
    Folder folder = existingFolder(command.folder(), tree);
    String target = destination(command.targetFolder(), tree).path();
    if (target.equals(folder.path()) || target.startsWith(folder.path() + "/")) {
      throw new Answer("Folder " + folder.path() + " cannot move into itself.");
    }
    catalog.moveFolder(new MoveFolderRequest(folder.id(), targetId(target)));
    return Outcome.text("Moved folder " + folder.path() + " to " + display(target) + ".");
  }

  private Outcome deleteFolder(String conversationId, ChainListCommand command) {
    Folder folder = existingFolder(command.folder(), folders.tree());
    List<Row> chains = ChainTable.inFolder(table.find(List.of()), folder.path());
    String confirm =
        chains.isEmpty()
            ? "Confirm: delete folder " + folder.path() + " and the folders inside it. It holds no"
                + " chains."
            : "Confirm: delete folder "
                + folder.path()
                + ". This also deletes and undeploys "
                + count(chains.size(), "chain")
                + ", and cannot be undone.";
    return offer(conversationId, new Change.DeleteFolder(folder, chains), confirm, list(chains));
  }

  private static Folder existingFolder(String reference, List<Folder> tree) {
    if (reference == null) {
      throw new Answer("Ask which folder the user means.");
    }
    return switch (CatalogFolders.resolve(reference, tree)) {
      case Resolution.Found found -> found.folder();
      case Resolution.Missing missing -> throw new Answer("There is no folder " + missing.path() + ".");
      case Resolution.Ambiguous ambiguous -> throw new Answer(ambiguous(reference, ambiguous));
      case Resolution.Root root -> throw new Answer("Ask which folder the user means.");
    };
  }

  /** Where a move or copy lands: a full path, empty for the top level, that may not exist yet. */
  private record Target(String path, boolean exists) {}

  private static Target destination(String reference, List<Folder> tree) {
    if (reference == null) {
      throw new Answer("Ask where the chains should go: a folder, or the top level.");
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

  private Outcome offer(String conversationId, Change change, String confirm, String shown) {
    String operationId = UUID.randomUUID().toString();
    pending.put(conversationId, new Pending(operationId, change));
    return new Outcome(
        confirm,
        shown,
        List.of(),
        text -> ChatEvent.chainListChangeDecision(operationId, text));
  }

  private Outcome apply(String conversationId, ChatDecisionCommand decision) {
    Pending answered = take(conversationId, decision);
    if (answered == null) {
      return Outcome.text(CARD_GONE_FACTS);
    }
    try {
      return Outcome.text(run(answered.change()));
    } catch (RuntimeException failure) {
      LOG.warnf(failure, "MANAGE_CHAIN_LIST apply failed conversationId=%s", conversationId);
      return Outcome.text("The catalog did not accept the change: " + failure.getMessage());
    }
  }

  /** The pending change this card was issued for, removed so the card cannot be answered twice. */
  private Pending take(String conversationId, ChatDecisionCommand decision) {
    Pending current = pending.get(conversationId);
    if (current == null || !current.operationId().equals(decision.getArtifactHash())) {
      return null;
    }
    return pending.remove(conversationId, current) ? current : null;
  }

  // ── Facts and data ────────────────────────────────────────────────────

  /** The reply agent's input: the facts, what the author sees under its text, and the chains. */
  private static String facts(Outcome outcome) {
    List<Map<String, Object>> chains = new ArrayList<>();
    for (int i = 0; i < Math.min(outcome.chains().size(), CHAINS_FOR_THE_REPLY); i++) {
      chains.add(columns(i + 1, outcome.chains().get(i)));
    }
    Map<String, Object> facts = new LinkedHashMap<>();
    facts.put("facts", outcome.facts());
    facts.put("shown", outcome.shown());
    facts.put("chains", chains);
    facts.put("chainsLeftOut", Math.max(0, outcome.chains().size() - CHAINS_FOR_THE_REPLY));
    return JSON.valueToTree(facts).toString();
  }

  private static Map<String, Object> columns(int number, Row row) {
    Map<String, Object> columns = new LinkedHashMap<>();
    columns.put("number", number);
    columns.put("name", row.name());
    columns.put("folder", row.folderPath().isEmpty() ? "/" : row.folderPath());
    columns.put("created", row.createdWhen() == null ? null : day(row.createdWhen()));
    columns.put("createdBy", row.createdBy());
    columns.put("changed", row.modifiedWhen() == null ? null : day(row.modifiedWhen()));
    columns.put("changedBy", row.modifiedBy());
    columns.put("labels", row.labels());
    columns.put("deploymentStatuses", row.statuses());
    return columns;
  }

  private static String ambiguous(String reference, Resolution.Ambiguous ambiguous) {
    return "Several folders are named "
        + reference
        + ": "
        + String.join(", ", CatalogFolders.paths(ambiguous.candidates()))
        + ". Ask which one; the user replies with its full path.";
  }

  /** The chains a card changes, one per line. */
  private static String list(List<Row> rows) {
    StringBuilder text = new StringBuilder();
    rows.stream()
        .limit(LISTED_CHAINS)
        .forEach(row -> text.append("- ").append(row.display()).append('\n'));
    if (rows.size() > LISTED_CHAINS) {
      text.append("- …\n");
    }
    return text.toString().stripTrailing();
  }

  /**
   * A search answer, numbered so the next request can pick chains by number. A line reads: name
   * (folder) · creation day · author · labels · deployment states other than DRAFT.
   */
  private static String numbered(List<Row> rows) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < Math.min(rows.size(), LISTED_CHAINS); i++) {
      Row row = rows.get(i);
      List<String> parts = new ArrayList<>(List.of(row.display()));
      if (row.createdWhen() != null) {
        parts.add(day(row.createdWhen()));
      }
      if (row.createdBy() != null && !row.createdBy().isEmpty()) {
        parts.add(row.createdBy());
      }
      if (!row.labels().isEmpty()) {
        parts.add(String.join(", ", row.labels()));
      }
      if (!row.statuses().isEmpty() && !row.statuses().equals(List.of("DRAFT"))) {
        parts.add(String.join(", ", row.statuses()));
      }
      text.append(i + 1).append(". ").append(String.join(" · ", parts)).append('\n');
    }
    if (rows.size() > LISTED_CHAINS) {
      text.append("…\n");
    }
    return text.toString().stripTrailing();
  }

  /** Counts per group, largest first, or in date order for a date grouping. */
  private static String groups(List<Row> rows, Grouping grouping) {
    Map<String, Integer> counts = new HashMap<>();
    rows.forEach(row -> groupKeys(row, grouping).forEach(key -> counts.merge(key, 1, Integer::sum)));
    boolean dated = grouping.name().endsWith("_DAY") || grouping.name().endsWith("_MONTH");
    Comparator<Map.Entry<String, Integer>> order =
        dated
            ? Map.Entry.comparingByKey()
            : Map.Entry.<String, Integer>comparingByValue()
                .reversed()
                .thenComparing(Map.Entry.comparingByKey());
    List<String> lines =
        counts.entrySet().stream()
            .sorted(order)
            .map(group -> "- " + group.getKey() + ": " + group.getValue())
            .toList();
    StringBuilder text = new StringBuilder();
    lines.stream().limit(LISTED_CHAINS).forEach(line -> text.append(line).append('\n'));
    if (lines.size() > LISTED_CHAINS) {
      text.append("- …\n");
    }
    return text.toString().stripTrailing();
  }

  private static List<String> groupKeys(Row row, Grouping grouping) {
    return switch (grouping) {
      case FOLDER -> List.of(row.folderPath().isEmpty() ? "/" : row.folderPath());
      case LABEL -> row.labels().isEmpty() ? List.of(NONE) : row.labels();
      case STATUS -> row.statuses().isEmpty() ? List.of(NONE) : row.statuses();
      case CREATED_BY -> List.of(orNone(row.createdBy()));
      case MODIFIED_BY -> List.of(orNone(row.modifiedBy()));
      case CREATED_DAY -> List.of(date(row.createdWhen(), 10));
      case CREATED_MONTH -> List.of(date(row.createdWhen(), 7));
      case MODIFIED_DAY -> List.of(date(row.modifiedWhen(), 10));
      case MODIFIED_MONTH -> List.of(date(row.modifiedWhen(), 7));
    };
  }

  private static String groupName(Grouping grouping) {
    return switch (grouping) {
      case FOLDER -> "folder";
      case LABEL -> "label";
      case STATUS -> "deployment status";
      case CREATED_BY -> "author";
      case MODIFIED_BY -> "last editor";
      case CREATED_DAY -> "creation day";
      case CREATED_MONTH -> "creation month";
      case MODIFIED_DAY -> "day of the last change";
      case MODIFIED_MONTH -> "month of the last change";
    };
  }

  /** The UTC day, or month for {@code length} 7, of a catalog timestamp. */
  private static String date(Long when, int length) {
    return when == null ? NONE : day(when).substring(0, length);
  }

  private static String day(long when) {
    return LocalDate.ofInstant(Instant.ofEpochMilli(when), ZoneOffset.UTC).toString();
  }

  private static String orNone(String author) {
    return author == null || author.isEmpty() ? NONE : author;
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
