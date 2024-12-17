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
import io.atlasmap.core.BaseAtlasModule;
import io.atlasmap.mxbean.AtlasModuleMXBean;
import io.atlasmap.spi.*;
import io.atlasmap.v2.DataSourceMetadata;
import io.atlasmap.v2.Field;

import javax.management.openmbean.TabularData;
import java.util.List;
import java.util.Map;

public class DelegatingAtlasModule implements AtlasModule, AtlasModuleMXBean {
    private final BaseAtlasModule delegate;

    public DelegatingAtlasModule(BaseAtlasModule delegate) {
        this.delegate = delegate;
    }

    public BaseAtlasModule getDelegate() {
        return delegate;
    }

    @Override
    public void processPreValidation(AtlasInternalSession atlasInternalSession) throws AtlasException {
        delegate.processPreValidation(atlasInternalSession);
    }

    @Override
    public void processPreSourceExecution(AtlasInternalSession atlasInternalSession) throws AtlasException {
        delegate.processPreSourceExecution(atlasInternalSession);
    }

    @Override
    public void processPreTargetExecution(AtlasInternalSession atlasInternalSession) throws AtlasException {
        delegate.processPreTargetExecution(atlasInternalSession);
    }

    @Override
    public void readSourceValue(AtlasInternalSession atlasInternalSession) throws AtlasException {
        delegate.readSourceValue(atlasInternalSession);
    }

    @Override
    public void writeTargetValue(AtlasInternalSession atlasInternalSession) throws AtlasException {
        delegate.writeTargetValue(atlasInternalSession);
    }

    @Override
    public void processPostSourceExecution(AtlasInternalSession atlasInternalSession) throws AtlasException {
        delegate.processPostSourceExecution(atlasInternalSession);
    }

    @Override
    public void processPostTargetExecution(AtlasInternalSession atlasInternalSession) throws AtlasException {
        delegate.processPostTargetExecution(atlasInternalSession);
    }

    @Override
    public Field cloneField(Field field) throws AtlasException {
        return delegate.cloneField(field);
    }

    @Override
    public Field createField() {
        return delegate.createField();
    }

    @Override
    public void init() throws AtlasException {
        delegate.init();
    }

    @Override
    public void destroy() throws AtlasException {
        delegate.destroy();
    }

    @Override
    public void setClassLoader(ClassLoader classLoader) {
        delegate.setClassLoader(classLoader);
    }

    @Override
    public ClassLoader getClassLoader() {
        return delegate.getClassLoader();
    }

    @Override
    public void processPostValidation(AtlasInternalSession session) throws AtlasException {
        delegate.processPostValidation(session);
    }

    @Override
    public void populateTargetField(AtlasInternalSession session) throws AtlasException {
        delegate.populateTargetField(session);
    }

    @Override
    public AtlasModuleMode getMode() {
        return delegate.getMode();
    }

    @Override
    public void setMode(AtlasModuleMode atlasModuleMode) {
        delegate.setMode(atlasModuleMode);
    }

    @Override
    public Boolean isStatisticsSupported() {
        return delegate.isStatisticsSupported();
    }

    @Override
    public Boolean isStatisticsEnabled() {
        return delegate.isStatisticsEnabled();
    }

    @Override
    public List<AtlasModuleMode> listSupportedModes() {
        return delegate.listSupportedModes();
    }

    @Override
    public AtlasConversionService getConversionService() {
        return delegate.getConversionService();
    }

    @Override
    public AtlasCollectionHelper getCollectionHelper() {
        return delegate.getCollectionHelper();
    }

    @Override
    public String getDocId() {
        return delegate.getDocId();
    }

    @Override
    public void setDocId(String docId) {
        delegate.setDocId(docId);
    }

    @Override
    public String getUri() {
        return delegate.getUri();
    }

    @Override
    public void setUri(String uri) {
        delegate.setUri(uri);
    }

    @Override
    public String getUriDataType() {
        return delegate.getUriDataType();
    }

    @Override
    public Map<String, String> getUriParameters() {
        return delegate.getUriParameters();
    }

    @Override
    public void setConversionService(AtlasConversionService atlasConversionService) {
        delegate.setConversionService(atlasConversionService);
    }

    @Override
    public AtlasFieldActionService getFieldActionService() {
        return delegate.getFieldActionService();
    }

    @Override
    public void setFieldActionService(AtlasFieldActionService atlasFieldActionService) {
        delegate.setFieldActionService(atlasFieldActionService);
    }

    public boolean isAutomaticallyProcessOutputFieldActions() {
        return delegate.isAutomaticallyProcessOutputFieldActions();
    }

    public void setAutomaticallyProcessOutputFieldActions(boolean automaticallyProcessOutputFieldActions) {
        delegate.setAutomaticallyProcessOutputFieldActions(automaticallyProcessOutputFieldActions);
    }

    @Override
    public Boolean isSupportedField(Field field) {
        return delegate.isSupportedField(field);
    }

    @Override
    public void setDataSourceMetadata(DataSourceMetadata meta) {
        delegate.setDataSourceMetadata(meta);
    }

    @Override
    public DataSourceMetadata getDataSourceMetadata() {
        return delegate.getDataSourceMetadata();
    }

    @Override
    public void setDocName(String docName) {
        delegate.setDocName(docName);
    }

    @Override
    public String getDocName() {
        return delegate.getDocName();
    }

    @Override
    public boolean isSourceSupported() {
        return delegate.isSourceSupported();
    }

    @Override
    public boolean isTargetSupported() {
        return delegate.isTargetSupported();
    }

    @Override
    public String getClassName() {
        return delegate.getClassName();
    }

    @Override
    public String[] getDataFormats() {
        return delegate.getDataFormats();
    }

    @Override
    public String getModeName() {
        return delegate.getModeName();
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String[] getPackageNames() {
        return delegate.getPackageNames();
    }

    @Override
    public long getSourceErrorCount() {
        return delegate.getSourceErrorCount();
    }

    @Override
    public long getSourceCount() {
        return delegate.getSourceCount();
    }

    @Override
    public long getSourceMaxExecutionTime() {
        return delegate.getSourceMaxExecutionTime();
    }

    @Override
    public long getSourceMinExecutionTime() {
        return delegate.getSourceMinExecutionTime();
    }

    @Override
    public long getSourceSuccessCount() {
        return delegate.getSourceSuccessCount();
    }

    @Override
    public long getSourceTotalExecutionTime() {
        return delegate.getSourceTotalExecutionTime();
    }

    @Override
    public long getTargetCount() {
        return delegate.getTargetCount();
    }

    @Override
    public long getTargetErrorCount() {
        return delegate.getTargetErrorCount();
    }

    @Override
    public long getTargetMaxExecutionTime() {
        return delegate.getTargetMaxExecutionTime();
    }

    @Override
    public long getTargetMinExecutionTime() {
        return delegate.getTargetMinExecutionTime();
    }

    @Override
    public long getTargetSuccessCount() {
        return delegate.getTargetSuccessCount();
    }

    @Override
    public long getTargetTotalExecutionTime() {
        return delegate.getTargetTotalExecutionTime();
    }

    @Override
    public String getUuid() {
        return delegate.getUuid();
    }

    @Override
    public String getVersion() {
        return delegate.getVersion();
    }

    @Override
    public TabularData readAndResetStatistics() {
        return delegate.readAndResetStatistics();
    }

    @Override
    public void setStatisticsEnabled(boolean enabled) {
        delegate.setStatisticsEnabled(enabled);
    }
}
