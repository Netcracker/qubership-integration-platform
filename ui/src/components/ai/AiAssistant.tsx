import { Button, Drawer, Tabs, Space, Typography, Divider } from "antd";
import React, { useState } from "react";
import "./AiAssistantPanel.css";
import { OverridableIcon } from "../../icons/IconProvider.tsx";
import { api } from "../../api/api.ts";
import { ChainModificationConfirmation } from "./ChainModificationConfirmation.tsx";
import { applyChainModificationProposal } from "./applyChainModificationProposal.ts";
import { useAiDrawerResize } from "./useAiDrawerResize.ts";
import { useAiAssistantChat } from "./useAiAssistantChat.ts";
import { AiChatMessageList } from "./AiChatMessageList.tsx";
import { AiChatInput } from "./AiChatInput.tsx";
import { INPUT_TEXTAREA_ROWS, SEND_KEY } from "./aiAssistantConstants.ts";

export const AiAssistant: React.FC = () => {
  const [open, setOpen] = useState(false);

  const chat = useAiAssistantChat(open);
  const { drawerWidth, isResizing, onResizeMouseDown } =
    useAiDrawerResize(open);

  const showDrawer = () => {
    setOpen(true);
    chat.ensureSessionOnOpen();
  };

  const onClose = () => setOpen(false);

  return (
    <>
      <div
        onClick={(e) => {
          e.stopPropagation();
          showDrawer();
        }}
        onMouseDown={(e) => {
          if (e.button === 0) {
            e.stopPropagation();
            showDrawer();
          }
        }}
        style={{ display: "inline-block" }}
      >
        <Button
          type="text"
          aria-label={chat.assistantName}
          title={chat.assistantName}
          style={{ fontSize: 18, color: "inherit" }}
          icon={<OverridableIcon name="comment" />}
          onClick={(e) => {
            e.stopPropagation();
            showDrawer();
          }}
        />
      </div>

      <Drawer
        title={
          <div style={{ width: "100%" }}>
            <div
              style={{
                display: "flex",
                justifyContent: "space-between",
                alignItems: "center",
              }}
            >
              <span style={{ fontSize: 16, fontWeight: 500 }}>
                {chat.assistantName}
              </span>
              <Space size="small">
                <Button
                  size="small"
                  icon={<OverridableIcon name="plus" />}
                  onClick={chat.handleCreateSession}
                >
                  New Chat
                </Button>
                <Button
                  size="small"
                  onClick={chat.handleClear}
                  disabled={chat.isLoading || chat.isStreaming}
                >
                  Clear
                </Button>
              </Space>
            </div>
          </div>
        }
        placement="right"
        open={open}
        closable={true}
        onClose={onClose}
        afterOpenChange={() => {}}
        width={drawerWidth}
        zIndex={2000}
        rootClassName="ai-assistant-drawer"
      >
        <div
          className={`ai-drawer-resize-handle ${isResizing ? "resizing" : ""}`}
          onMouseDown={onResizeMouseDown}
        />
        {chat.sessions.length > 0 && chat.currentSessionId && (
          <Tabs
            activeKey={chat.currentSessionId}
            onChange={chat.handleSessionChange}
            onEdit={chat.handleTabEdit}
            items={chat.tabItems}
            type="editable-card"
            hideAdd
            style={{ marginBottom: 6 }}
            size="small"
          />
        )}

        <div className="ai-chat-root">
          {chat.providerError && (
            <Typography.Text type="danger" className="ai-provider-error">
              {chat.providerError}
            </Typography.Text>
          )}

          {chat.chainContext && (
            <div className="ai-context-pill">
              <span className="ai-context-pill__label">Chain:</span>
              <span className="ai-context-pill__value">
                {chat.chainContext.chain.name}
              </span>
            </div>
          )}

          <AiChatMessageList
            visibleMessages={chat.visibleMessages}
            isLoading={chat.isLoading}
            isStreaming={chat.isStreaming}
            assistantName={chat.assistantName}
            workingDots={chat.workingDots}
            chainContext={chat.chainContext}
            showLongRunningHint={chat.showLongRunningHint}
            scrollContainerRef={chat.scrollContainerRef}
            onScroll={chat.handleScroll}
            onExecutePlan={() => void chat.handleExecutePlanClick()}
            onPrepareRegenerate={chat.handlePrepareRegenerateFromIndex}
            onRegenerate={(i) => void chat.handleRegenerateFromIndex(i)}
          />

          <Divider className="ai-divider" />

          <AiChatInput
            fileInputRef={chat.fileInputRef}
            inputRef={chat.inputRef}
            attachedFiles={chat.attachedFiles}
            setAttachedFiles={chat.setAttachedFiles}
            inputValue={chat.inputValue}
            setInputValue={chat.setInputValue}
            textareaRows={INPUT_TEXTAREA_ROWS}
            sendKey={SEND_KEY}
            isLoading={chat.isLoading}
            isStreaming={chat.isStreaming}
            meta={chat.meta}
            onSend={() => void chat.handleSend()}
            onAbort={chat.handleAbort}
          />
        </div>

        <ChainModificationConfirmation
          open={chat.isConfirmationOpen}
          proposal={chat.pendingProposal}
          onCancel={() => chat.setIsConfirmationOpen(false)}
          onApply={(proposal) => {
            void (async () => {
              if (!chat.chainContext) {
                chat.setIsConfirmationOpen(false);
                return;
              }
              const chainId = proposal.chainId || chat.chainContext.chain.id;
              try {
                await applyChainModificationProposal(
                  proposal,
                  api,
                  chat.chainContext,
                );
                await chat.refreshChainContexts(chainId);
              } catch (error) {
                console.error(
                  "[Ai] Failed to apply chain modifications",
                  error,
                );
              } finally {
                chat.setIsConfirmationOpen(false);
                chat.setPendingProposal(null);
              }
            })();
          }}
        />
      </Drawer>
    </>
  );
};
