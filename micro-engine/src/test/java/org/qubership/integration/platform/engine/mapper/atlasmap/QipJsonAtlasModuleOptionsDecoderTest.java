package org.qubership.integration.platform.engine.mapper.atlasmap;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class QipJsonAtlasModuleOptionsDecoderTest {

    @Test
    void shouldDecodeSerializeTargetDocumentWhenTrue() {
        QipJsonAtlasModuleOptions options =
                QipJsonAtlasModuleOptionsDecoder.decode("atlas:cip:json?serializeTargetDocument=true");

        assertTrue(options.isSerializeTargetDocument());
    }

    @Test
    void shouldDecodeSerializeTargetDocumentWhenFalse() {
        QipJsonAtlasModuleOptions options =
                QipJsonAtlasModuleOptionsDecoder.decode("atlas:cip:json?serializeTargetDocument=false");

        assertFalse(options.isSerializeTargetDocument());
    }

    @Test
    void shouldTreatInvalidBooleanValueAsFalse() {
        QipJsonAtlasModuleOptions options =
                QipJsonAtlasModuleOptionsDecoder.decode("atlas:cip:json?serializeTargetDocument=notBoolean");

        assertFalse(options.isSerializeTargetDocument());
    }

    @Test
    void shouldUseLastSupportedOptionValueWhenParameterRepeated() {
        QipJsonAtlasModuleOptions options =
                QipJsonAtlasModuleOptionsDecoder.decode(
                        "atlas:cip:json?serializeTargetDocument=false&serializeTargetDocument=true"
                );

        assertTrue(options.isSerializeTargetDocument());
    }

    @Test
    void shouldIgnoreUnknownParametersAndDecodeSupportedOne() {
        QipJsonAtlasModuleOptions options =
                QipJsonAtlasModuleOptionsDecoder.decode(
                        "atlas:cip:json?foo=bar&serializeTargetDocument=true&baz=qux"
                );

        assertTrue(options.isSerializeTargetDocument());
    }
}
