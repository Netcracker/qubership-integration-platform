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

package org.qubership.integration.platform.runtime.catalog.model.mapper.datatypes;

public interface DataTypeVisitor<R, A, E extends Exception> {
    default R visit(DataType type, A arg) throws E {
        return type.accept(this, arg);
    }

    R visitAllOfType(AllOfType type, A arg) throws E;

    R visitAnyOfType(AnyOfType type, A arg) throws E;

    R visitOneOfType(OneOfType type, A arg) throws E;

    R visitNullType(NullType type, A arg) throws E;

    R visitBooleanType(BooleanType type, A arg) throws E;

    R visitIntegerType(IntegerType type, A arg) throws E;

    R visitStringType(StringType type, A arg) throws E;

    R visitArrayType(ArrayType type, A arg) throws E;

    R visitObjectType(ObjectType type, A arg) throws E;

    R visitReferenceType(ReferenceType type, A arg) throws E;
}
