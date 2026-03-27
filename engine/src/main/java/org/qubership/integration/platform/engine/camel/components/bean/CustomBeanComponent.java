package org.qubership.integration.platform.engine.camel.components.bean;

import org.apache.camel.component.bean.BeanComponent;
import org.apache.camel.spi.annotations.Component;
import org.apache.camel.util.ObjectHelper;

// WA for https://issues.apache.org/jira/browse/CAMEL-22468
@Component("bean")
public class CustomBeanComponent extends BeanComponent {

    @Override
    protected void doInit() throws Exception {
        ObjectHelper.notNull(getCamelContext(), "camelContext");
    }

    @Override
    protected void doStart() throws Exception {
        super.doInit();
    }
}
