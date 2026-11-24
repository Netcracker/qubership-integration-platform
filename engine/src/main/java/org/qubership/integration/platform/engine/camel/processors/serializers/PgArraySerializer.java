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

package org.qubership.integration.platform.engine.camel.processors.serializers;

import com.rabbitmq.client.LongString;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.jdbc.PgArray;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

import java.sql.SQLException;
import java.util.stream.IntStream;




@Slf4j
public class PgArraySerializer extends StdSerializer<PgArray> {

    public PgArraySerializer(Class<PgArray> t) {
        super(t);
    }

    @Override
    public void serialize(PgArray value, JsonGenerator generator, SerializationContext provider)
        throws JacksonException {
        try {
            Object convertedArray = value.getArray();
            if (convertedArray instanceof String[] array) {
                generator.writeArray(array, 0, array.length);
                return;
            }
            if (convertedArray instanceof int[] array) {
                generator.writeArray(array, 0, array.length);
                return;
            }
            if (convertedArray instanceof long[] array) {
                generator.writeArray(array, 0, array.length);
                return;
            }
            if (convertedArray instanceof double[] array) {
                generator.writeArray(array, 0, array.length);
                return;
            }
            if (convertedArray instanceof float[] array) {
                generator.writeArray(IntStream.range(0, array.length).mapToDouble(i -> array[i]).toArray(), 0, array.length);
                return;
            }
            throw new IllegalArgumentException("Failed to serialize PgArray object, invalid array type: " + convertedArray.getClass().getName());
        } catch (JacksonException e) {
            log.warn("Exception while serializing {} object", LongString.class.getName(), e);
            throw e;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
