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

package org.qubership.integration.platform.runtime.catalog.builder.templates;

import com.github.jknack.handlebars.EscapingStrategy;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Helper;
import com.github.jknack.handlebars.Template;
import com.github.jknack.handlebars.helper.ConditionalHelpers;
import com.github.jknack.handlebars.helper.StringHelpers;
import com.github.jknack.handlebars.io.ClassPathTemplateLoader;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.catalog.exception.SnapshotCreationException;
import org.qubership.integration.platform.catalog.model.library.ElementType;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.catalog.service.library.LibraryElementsService;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Map;

@Slf4j
@Component
public final class TemplateService {

    private static final String TEMPLATE_FOLDER = "/elements";
    private static final String SHARED_TEMPLATE_FOLDER = "/shared";
    private static final String SHARED_TEMPLATE_SUFFIX = ".hbs";
    private static final String DEFAULT_TEMPLATE_SUFFIX = "/template.hbs";
    private static final String COMPOSITE_TRIGGER_DIR_SUFFIX = "/trigger";
    private static final String COMPOSITE_TRIGGER_MODULE_DIR_SUFFIX = "/module";

    private final Handlebars handlebars;
    private final LibraryElementsService libraryService;

    @Autowired
    public TemplateService(ListableBeanFactory beanFactory, LibraryElementsService libraryService) {
        this.libraryService = libraryService;
        this.handlebars = new Handlebars()
                .with(
                    new ClassPathTemplateLoader(TEMPLATE_FOLDER, DEFAULT_TEMPLATE_SUFFIX),
                    new ClassPathTemplateLoader(SHARED_TEMPLATE_FOLDER, SHARED_TEMPLATE_SUFFIX))
                .with(EscapingStrategy.NOOP);

        handlebars.setInfiniteLoops(true);
        handlebars.setPrettyPrint(true);
        handlebars.registerHelpers(StringHelpers.class);
        handlebars.registerHelpers(ConditionalHelpers.class);
        registerCustomHelpers(beanFactory);
    }

    private void registerCustomHelpers(ListableBeanFactory beanFactory) {
        Map<String, Object> helpers = beanFactory.getBeansWithAnnotation(TemplatesHelper.class);
        for (Object bean : helpers.values()) {
            if (bean instanceof Helper) {
                Helper<?> helper = (Helper<?>) bean;
                TemplatesHelper annotation = bean.getClass().getAnnotation(TemplatesHelper.class);
                String name = annotation.value();
                if (StringUtils.isNotBlank(name)) {
                    if (log.isDebugEnabled()) {
                        log.debug("Registering Handlebars helper {} ({})", name, helper.getClass().getName());
                    }
                    handlebars.registerHelper(name, helper);
                }
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("Registering Handlebars helpers {}", bean.getClass().getName());
                }
                handlebars.registerHelpers(bean);
            }
        }
    }

    public String applyTemplate(ChainElement element) {
        Template template = getTemplate(element);
        if (template == null) {
            throw new SnapshotCreationException("Element is not supposed to be outside a parent container.", element);
        }

        String renderedElement;
        try {
            renderedElement = template.apply(element);
        } catch (IOException | RuntimeException e) {
            log.warn("Error while applying template to the element {}: {}", element.getType(), e.getMessage());
            if (e.getCause() instanceof SnapshotCreationException) {
                if (StringUtils.isBlank(((SnapshotCreationException) e.getCause()).getElementId())) {
                    throw new SnapshotCreationException(e.getCause().getMessage(), element, e);
                } else {
                    throw (SnapshotCreationException) e.getCause();
                }
            }
            throw new SnapshotCreationException("Fields are not properly defined or require mandatory connection", element, e);
        }

        return renderedElement;
    }

    public Template getTemplate(ChainElement element) {
        return libraryService.getElementDescriptor(element).getType() == ElementType.COMPOSITE_TRIGGER
                ? getTemplate(element.getType() + (element.getInputDependencies().isEmpty() && element.getParent() == null
                        ? COMPOSITE_TRIGGER_DIR_SUFFIX
                        : COMPOSITE_TRIGGER_MODULE_DIR_SUFFIX))
                : getTemplate(element.getType());
    }

    @SuppressWarnings("checkstyle:EmptyCatchBlock")
    public Template getTemplate(String name) {
        try {
            return handlebars.compile(name);
        } catch (FileNotFoundException ignored) {
        } catch (IOException e) {
            log.error("Can't initialize template for {}", name, e);
        }
        return null;
    }
}
