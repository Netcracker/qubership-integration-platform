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

package org.qubership.integration.platform.engine.mapper.atlasmap;

import io.atlasmap.api.AtlasException;
import io.atlasmap.api.AtlasSession;
import io.atlasmap.core.BaseAtlasModule;
import io.atlasmap.spi.AtlasInternalSession;
import io.atlasmap.spi.AtlasModuleDetail;
import io.atlasmap.v2.AtlasModelFactory;
import io.atlasmap.v2.ConstantField;
import io.atlasmap.v2.Field;
import org.qubership.integration.platform.mapper.GeneratedField;

import java.util.function.Function;

@AtlasModuleDetail(
        name = "QipGeneratedValueAtlasModule",
        uri = "atlas:cip:generated",
        modes = { "SOURCE" },
        dataFormats = { },
        configPackages = { "org.qubership.integration.platform.engine.mapper.atlasmap" }
)
public class QipGeneratedValueAtlasModule extends BaseAtlasModule {
    private static final String VALUE_GENERATOR_PROPERTY_PREFIX = "Atlas.GeneratedValue.";
    private final ValueGeneratorFactory valueGeneratorFactory;

    public QipGeneratedValueAtlasModule() {
        this.valueGeneratorFactory = new ValueGeneratorFactory();
    }

    @Override
    public Boolean isSupportedField(Field field) {
        return field instanceof GeneratedField;
    }

    @Override
    public void processPreValidation(AtlasInternalSession session) throws AtlasException {
    }

    @Override
    public void processPreSourceExecution(AtlasInternalSession session) throws AtlasException {
        try {
            ValueGeneratorInfo valueGeneratorInfo = ValueGeneratorInfoDecoder.decode(getUri());
            Function<AtlasSession, String> valueGenerator =
                    valueGeneratorFactory.getValueGenerator(valueGeneratorInfo.name(), valueGeneratorInfo.parameters());
            String value = valueGenerator.apply(session);
            session.getSourceProperties().put(VALUE_GENERATOR_PROPERTY_PREFIX + getDocId(), value);
        } catch (Exception exception) {
            throw new AtlasException(exception.getMessage(), exception);
        }
    }

    @Override
    public void processPreTargetExecution(AtlasInternalSession session) throws AtlasException {
        throw new AtlasException("Module supports only source mode");
    }

    @Override
    public void readSourceValue(AtlasInternalSession session) throws AtlasException {
        Field field = session.head().getSourceField();
        Field result = cloneField(field);
        result.setValue(session.getSourceProperties().get(VALUE_GENERATOR_PROPERTY_PREFIX + getDocId()));
        session.head().setSourceField(result);
    }

    @Override
    public void processPostSourceExecution(AtlasInternalSession session) throws AtlasException {
    }

    @Override
    public void writeTargetValue(AtlasInternalSession session) throws AtlasException {
        throw new AtlasException("Module supports only source mode");
    }

    @Override
    public void processPostTargetExecution(AtlasInternalSession session) throws AtlasException {
        throw new AtlasException("Module supports only source mode");
    }

    @Override
    public Field cloneField(Field field) throws AtlasException {
        Field result = createField();
        AtlasModelFactory.copyField(field, result, true);
        result.setValue(field.getValue());
        return result;
    }

    @Override
    public Field createField() {
        return new ConstantField();
    }
}
