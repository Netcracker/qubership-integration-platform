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

package org.qubership.integration.platform.runtime.catalog.kubernetes.secret;

import io.kubernetes.client.openapi.ApiCallback;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1Secret;
import org.springframework.lang.NonNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class SecretUpdateCallback implements ApiCallback<V1Secret> {

    private final CompletableFuture<Map<String, String>> future;

    public SecretUpdateCallback(@NonNull CompletableFuture<Map<String, String>> future) {
        this.future = future;
    }

    @Override
    public void onSuccess(V1Secret secret, int statusCode, Map<String, List<String>> responseHeaders) {
        ConcurrentMap<String, String> secretDataMap = new ConcurrentHashMap<>();
        if (secret.getData() != null) {
            secret.getData().forEach((k, v) -> secretDataMap.put(k, new String(v)));
        }

        future.complete(secretDataMap);
    }

    @Override
    public void onFailure(ApiException e, int statusCode, Map<String, List<String>> responseHeaders) {
        future.completeExceptionally(e);
    }

    @Override
    public void onUploadProgress(long bytesWritten, long contentLength, boolean done) {
        // do nothing
    }

    @Override
    public void onDownloadProgress(long bytesRead, long contentLength, boolean done) {
        // do nothing
    }
}
