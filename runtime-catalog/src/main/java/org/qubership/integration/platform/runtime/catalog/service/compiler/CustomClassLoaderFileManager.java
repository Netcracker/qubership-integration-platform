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

package org.qubership.integration.platform.runtime.catalog.service.compiler;

import java.io.IOException;
import java.util.Set;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

/** Based on CustomClassloaderJavaFileManager class from
 <a href="http://atamur.blogspot.com/2009/10/using-built-in-javacompiler-with-custom.html">Using built-in JavaCompiler with a custom classloader</a>
 post. */
public class CustomClassLoaderFileManager extends ForwardingJavaFileManager<JavaFileManager> {
    private final PackageInternalsFinder finder;

    public CustomClassLoaderFileManager(ClassLoader classLoader, JavaFileManager fileManager) {
        super(fileManager);
        this.finder = new PackageInternalsFinder(classLoader);
    }

    @Override
    public Iterable<JavaFileObject> list(
            Location location,
            String packageName,
            Set<JavaFileObject.Kind> kinds,
            boolean recurse
    ) throws IOException {
        return StandardLocation.CLASS_PATH.equals(location)
                && kinds.contains(JavaFileObject.Kind.CLASS)
                && !packageName.startsWith("java.")
                ? finder.find(packageName)
                : super.list(location, packageName, kinds, recurse);
    }

    @Override
    public boolean hasLocation(Location location) {
        return StandardLocation.CLASS_PATH.equals(location)
                || StandardLocation.PLATFORM_CLASS_PATH.equals(location)
                || super.hasLocation(location);
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        return file instanceof CustomJavaFileObject
                ? ((CustomJavaFileObject) file).binaryName()
                : super.inferBinaryName(location, file);
    }
}
