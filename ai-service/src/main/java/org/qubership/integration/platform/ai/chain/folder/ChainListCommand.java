package org.qubership.integration.platform.ai.chain.folder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;
import java.util.List;

/**
 * Typed reading of a request about the chains page, returned by the intent agent.
 *
 * <p>Java resolves every name in it against the catalog and decides what runs at once and what
 * needs a confirmation card. The agent never chooses between folders that share a name.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChainListCommand(
    @Description("Required. Use CLARIFY when the request lacks something the action needs.")
        ChainListAction action,
    @Description(
            "Folder the action is about, as the user wrote it: a name, or a path from the root"
                + " such as Integrations/Salesforce. For CREATE_FOLDER, RENAME_FOLDER, MOVE_FOLDER,"
                + " and DELETE_FOLDER, the folder itself. For the chain actions, the folder whose"
                + " chains to take; empty when the user did not limit them to one.")
        String folder,
    @Description("New name for RENAME_FOLDER or RENAME_CHAIN. A name, not a path.")
        String newName,
    @Description(
            "Destination of MOVE_CHAINS, COPY_CHAINS, and MOVE_FOLDER: a folder name or a path"
                + " from the root. Use / for the root. Empty for COPY_CHAINS copies each chain"
                + " into the folder it is in.")
        String targetFolder,
    @Description("Ids of chains the user named, including the open chain when they mean it.")
        List<String> chainIds,
    @Description("Names of chains the user named, exactly as written.") List<String> chainNames,
    @Description("Conditions on chains table columns; a chain must meet all of them.")
        List<ChainFilter> filters,
    @Description("For CLARIFY: the question to ask the user, in the language they wrote in.")
        String question,
    @Description("New description for SET_DESCRIPTION.") String description,
    @Description("Labels EDIT_LABELS adds to each chain.") List<String> addLabels,
    @Description("Labels EDIT_LABELS removes from each chain.") List<String> removeLabels) {

  /** Folder and move commands, which carry no description or labels. */
  public ChainListCommand(
      ChainListAction action,
      String folder,
      String newName,
      String targetFolder,
      List<String> chainIds,
      List<String> chainNames,
      List<ChainFilter> filters,
      String question) {
    this(
        action, folder, newName, targetFolder, chainIds, chainNames, filters, question, null, null,
        null);
  }

  public ChainListCommand {
    action = action == null ? ChainListAction.CLARIFY : action;
    folder = blankToNull(folder);
    newName = blankToNull(newName);
    targetFolder = blankToNull(targetFolder);
    chainIds = chainIds == null ? List.of() : List.copyOf(chainIds);
    chainNames = chainNames == null ? List.of() : List.copyOf(chainNames);
    filters = filters == null ? List.of() : List.copyOf(filters);
    question = blankToNull(question);
    description = blankToNull(description);
    addLabels = addLabels == null ? List.of() : List.copyOf(addLabels);
    removeLabels = removeLabels == null ? List.of() : List.copyOf(removeLabels);
  }

  /** True when the user pointed at chains one by one rather than by a condition or a folder. */
  public boolean namesChainsExplicitly() {
    return (!chainIds.isEmpty() || !chainNames.isEmpty()) && filters.isEmpty() && folder == null;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
