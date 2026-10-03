package org.qubership.integration.platform.runtime.catalog.service;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Folder;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ChainRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.FolderRepository;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FolderServiceTest {

    @Mock
    private FolderRepository folderRepository;
    @Mock
    private ActionsLogService actionLogger;
    @Mock
    private ChainRepository chainRepository;
    @Mock
    private DeploymentService deploymentService;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    private FolderService folderService;

    @Test
    @DisplayName("Bulk delete logs a chain's parent folder even though the delete removes the folder row")
    void deleteByIdsLogsParentFolderOfDeletedChain() {
        List<String> folderIds = List.of("folder-id");
        Folder parentProxy = mock(Folder.class);
        Chain chain = Chain.builder().id("chain-id").name("chain").parentFolder(parentProxy).build();
        AtomicBoolean deleted = new AtomicBoolean();
        when(chainRepository.findAllChainsInFolders(folderIds)).thenReturn(List.of(chain));
        when(folderRepository.deleteFolderTree(folderIds)).thenAnswer(invocation -> {
            deleted.set(true);
            return folderIds;
        });
        // A lazy proxy knows its id but loads the name from a row the delete removes.
        when(parentProxy.getId()).thenReturn("folder-id");
        when(parentProxy.getName()).thenAnswer(invocation -> {
            if (deleted.get()) {
                throw new EntityNotFoundException("Unable to find Folder with id folder-id");
            }
            return "parent";
        });

        folderService.deleteByIds(folderIds);

        ArgumentCaptor<ActionLog> action = ArgumentCaptor.forClass(ActionLog.class);
        verify(actionLogger).logAction(action.capture());
        assertThat(action.getValue())
                .extracting(ActionLog::getEntityType, ActionLog::getEntityId, ActionLog::getEntityName,
                        ActionLog::getParentType, ActionLog::getParentId, ActionLog::getParentName,
                        ActionLog::getOperation)
                .containsExactly(EntityType.CHAIN, "chain-id", "chain",
                        EntityType.FOLDER, "folder-id", "parent", LogOperation.DELETE);
    }
}
