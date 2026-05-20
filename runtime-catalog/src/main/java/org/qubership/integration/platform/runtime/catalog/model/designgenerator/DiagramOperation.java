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

package org.qubership.integration.platform.runtime.catalog.model.designgenerator;

import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

public class DiagramOperation {

    @Getter
    @Setter
    private String operationString;

    // <input_order, parameters>
    private Map<Integer, ArgumentParameters> argsInputOrder = null;

    // <output_order, parameters>
    private Map<Integer, ArgumentParameters> argsOutputOrder = null;

    public DiagramOperation(String operationString) {
        this.operationString = operationString;
    }

    public DiagramOperation(String operationString, ArgumentParameters... params) {
        this.operationString = operationString;
        argsInputOrder = new HashMap<>();
        argsOutputOrder = new HashMap<>();
        for (ArgumentParameters param : params) {
            argsInputOrder.put(param.getInputOrder(), param);
            argsOutputOrder.put(param.getOutputOrder(), param);
        }
    }

    /**
     * Escape argument characters, true by default
     */
    public boolean isEscapeArgument(int outputOrderNumber) {
        return argsOutputOrder == null
                || !argsOutputOrder.containsKey(outputOrderNumber)
                || argsOutputOrder.get(outputOrderNumber).isEscapeArgument();
    }

    /**
        Remap input arguments by rule [input_order] -> [output_order]
     */
    public String[] remapArguments(String[] args) {
        if (argsInputOrder == null) {
            return args;
        }

        String[] output = new String[args.length];
        for (int inputOrderNumber = 0; inputOrderNumber < args.length; inputOrderNumber++) {
            output[argsInputOrder.get(inputOrderNumber).getOutputOrder()] = args[inputOrderNumber];
        }

        return output;
    }
}
