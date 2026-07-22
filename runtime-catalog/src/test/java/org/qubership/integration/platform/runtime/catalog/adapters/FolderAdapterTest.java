package org.qubership.integration.platform.runtime.catalog.adapters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Folder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FolderAdapterTest {

    @DisplayName("Should pass through id, name and description from the wrapped folder")
    @Test
    void shouldExposeBasicFields() {
        Folder folder = Folder.builder().id("folder-1").name("My Folder").description("some description").build();

        FolderAdapter adapter = new FolderAdapter(folder);

        assertEquals("folder-1", adapter.getId());
        assertEquals("My Folder", adapter.getName());
        assertEquals("some description", adapter.getDescription());
    }

    @DisplayName("Should return an empty parent folder when the wrapped folder has none")
    @Test
    void shouldReturnEmptyParentFolderWhenAbsent() {
        Folder folder = Folder.builder().id("folder-1").build();

        FolderAdapter adapter = new FolderAdapter(folder);

        assertFalse(adapter.getParentFolder().isPresent());
    }

    @DisplayName("Should wrap the parent folder when present")
    @Test
    void shouldWrapParentFolderWhenPresent() {
        Folder parent = Folder.builder().id("parent-1").name("Parent").build();
        Folder child = Folder.builder().id("child-1").name("Child").parentFolder(parent).build();

        FolderAdapter adapter = new FolderAdapter(child);

        assertTrue(adapter.getParentFolder().isPresent());
        assertEquals("parent-1", adapter.getParentFolder().get().getId());
        assertEquals("Parent", adapter.getParentFolder().get().getName());
    }
}
