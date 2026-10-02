package org.qubership.integration.platform.ai.chain.folder;

/** What a request about the chains page asks for. */
public enum ChainListAction {
  FIND_CHAINS,
  MOVE_CHAINS,
  RENAME_CHAIN,
  SET_DESCRIPTION,
  EDIT_LABELS,
  COPY_CHAINS,
  DELETE_CHAINS,
  CREATE_FOLDER,
  RENAME_FOLDER,
  MOVE_FOLDER,
  DELETE_FOLDER,
  /** The request lacks something the other actions need, or is not about chains and folders. */
  CLARIFY
}
