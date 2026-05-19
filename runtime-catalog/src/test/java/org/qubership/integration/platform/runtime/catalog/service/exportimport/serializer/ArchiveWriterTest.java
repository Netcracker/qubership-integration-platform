/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.ExportableObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.ARCH_PARENT_DIR;

@ExtendWith(MockitoExtension.class)
class ArchiveWriterTest {

    @Mock
    ExportableObjectWriterVisitor exportableObjectWriterVisitor;

    @Captor
    ArgumentCaptor<String> entryPathCaptor;

    ArchiveWriter archiveWriter;

    @BeforeEach
    void setUp() {
        archiveWriter = new ArchiveWriter(exportableObjectWriterVisitor);
    }

    @Test
    @DisplayName("writeArchive with empty list returns valid empty zip bytes")
    void writeArchiveEmptyListReturnsValidZip() throws IOException {
        byte[] result = archiveWriter.writeArchive(List.of());

        assertNotNull(result);
        assertTrue(result.length > 0, "Result should contain zip header bytes");

        // verify the bytes form a valid (empty) zip
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(result))) {
            assertNull(zis.getNextEntry(), "Empty archive should have no entries");
        }
    }

    @Test
    @DisplayName("writeArchive calls accept on single object with correct entry path")
    void writeArchiveSingleObjectCallsAcceptWithCorrectPath() throws IOException {
        ExportableObject obj = mock(ExportableObject.class);
        when(obj.getId()).thenReturn("obj-1");

        archiveWriter.writeArchive(List.of(obj));

        verify(obj).accept(eq(exportableObjectWriterVisitor), any(ZipOutputStream.class), entryPathCaptor.capture());

        String expectedPath = ARCH_PARENT_DIR + File.separator + "obj-1" + File.separator;
        assertThat(entryPathCaptor.getValue(), equalTo(expectedPath));
    }

    @Test
    @DisplayName("writeArchive calls accept on each object in the list")
    void writeArchiveMultipleObjectsCallsAcceptForEach() throws IOException {
        ExportableObject obj1 = mock(ExportableObject.class);
        ExportableObject obj2 = mock(ExportableObject.class);
        ExportableObject obj3 = mock(ExportableObject.class);
        when(obj1.getId()).thenReturn("id-1");
        when(obj2.getId()).thenReturn("id-2");
        when(obj3.getId()).thenReturn("id-3");

        archiveWriter.writeArchive(List.of(obj1, obj2, obj3));

        verify(obj1).accept(eq(exportableObjectWriterVisitor), any(ZipOutputStream.class), entryPathCaptor.capture());
        verify(obj2).accept(eq(exportableObjectWriterVisitor), any(ZipOutputStream.class), entryPathCaptor.capture());
        verify(obj3).accept(eq(exportableObjectWriterVisitor), any(ZipOutputStream.class), entryPathCaptor.capture());

        List<String> capturedPaths = entryPathCaptor.getAllValues();
        assertThat(capturedPaths, containsInAnyOrder(
                ARCH_PARENT_DIR + File.separator + "id-1" + File.separator,
                ARCH_PARENT_DIR + File.separator + "id-2" + File.separator,
                ARCH_PARENT_DIR + File.separator + "id-3" + File.separator
        ));
    }

    @Test
    @DisplayName("writeArchive wraps IOException in RuntimeException")
    void writeArchiveWrapsIOExceptionInRuntimeException() throws IOException {
        ExportableObject obj = mock(ExportableObject.class);
        when(obj.getId()).thenReturn("obj-fail");
        doThrow(new IOException("disk error"))
                .when(obj).accept(any(), any(), any());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> archiveWriter.writeArchive(List.of(obj)));

        assertThat(ex.getMessage(), containsString("Failed to create archive"));
        assertThat(ex.getCause(), instanceOf(IOException.class));
    }

    @Test
    @DisplayName("writeArchive result contains a zip entry written by the visitor")
    void writeArchiveResultContainsEntryWrittenByVisitor() throws IOException {
        String entryName = "services/obj-1/system.yaml";
        String entryContent = "name: test";

        ExportableObject obj = mock(ExportableObject.class);
        when(obj.getId()).thenReturn("obj-1");
        doAnswer(invocation -> {
            ZipOutputStream zos = invocation.getArgument(1);
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(entryContent.getBytes());
            zos.closeEntry();
            return null;
        }).when(obj).accept(any(), any(ZipOutputStream.class), any());

        byte[] result = archiveWriter.writeArchive(List.of(obj));

        List<String> entryNames = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(result))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entryNames.add(entry.getName());
            }
        }

        assertThat(entryNames, hasSize(1));
        assertThat(entryNames, hasItem(entryName));
    }

    @Test
    @DisplayName("writeArchive passes the same ZipOutputStream instance to all accept calls")
    void writeArchivePassesSameZipOutputStreamToAllObjects() throws IOException {
        ExportableObject obj1 = mock(ExportableObject.class);
        ExportableObject obj2 = mock(ExportableObject.class);
        when(obj1.getId()).thenReturn("id-1");
        when(obj2.getId()).thenReturn("id-2");

        ArgumentCaptor<ZipOutputStream> zipCaptor = ArgumentCaptor.forClass(ZipOutputStream.class);

        archiveWriter.writeArchive(List.of(obj1, obj2));

        verify(obj1).accept(any(), zipCaptor.capture(), any());
        ZipOutputStream zip1 = zipCaptor.getValue();

        verify(obj2).accept(any(), zipCaptor.capture(), any());
        ZipOutputStream zip2 = zipCaptor.getValue();

        assertSame(zip1, zip2, "All objects must receive the same ZipOutputStream instance");
    }
}
