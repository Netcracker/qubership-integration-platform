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

package org.qubership.integration.platform.engine.camel.scheduler;

import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.core.QuartzScheduler;
import org.quartz.core.QuartzSchedulerResources;
import org.quartz.impl.StdSchedulerFactory;

import java.util.Properties;

public class StdSchedulerFactoryProxy extends StdSchedulerFactory {
    public StdSchedulerFactoryProxy() {
    }

    public StdSchedulerFactoryProxy(Properties props) throws SchedulerException {
        super(props);
    }

    public StdSchedulerFactoryProxy(String fileName) throws SchedulerException {
        super(fileName);
    }

    protected Scheduler instantiate(QuartzSchedulerResources rsrcs, QuartzScheduler qs) {
        Scheduler scheduler = new StdSchedulerProxy(qs);
        return scheduler;
    }
}
