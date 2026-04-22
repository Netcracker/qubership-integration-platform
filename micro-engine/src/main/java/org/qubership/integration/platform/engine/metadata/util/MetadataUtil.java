package org.qubership.integration.platform.engine.metadata.util;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Route;
import org.qubership.integration.platform.engine.metadata.*;

import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MetadataUtil {
    private MetadataUtil() {
    }

    private static String getBeanName(Class<?> cls, String id) {
        return String.format("%s-%s", cls.getSimpleName(), id);
    }

    private static Route getRoute(Exchange exchange) {
        String routeId = exchange.getFromRouteId();
        return exchange.getContext().getRoute(routeId);
    }

    public static <T> Optional<T> lookupBean(Route route, Class<T> cls) {
        String groudId  = route.getGroup();
        CamelContext context = route.getCamelContext();
        String beanName = getBeanName(cls, groudId);
        T bean = context.getRegistry().lookupByNameAndType(beanName, cls);
        return Optional.ofNullable(bean);
    }

    public static <T> T getBean(Exchange exchange, Class<T> cls) {
        return getBean(getRoute(exchange), cls);
    }

    public static <T> T getBean(Route route, Class<T> cls) {
        return lookupBean(route, cls)
                .orElseThrow(() -> new BeanNotFoundException(getBeanName(cls, route.getGroup())));
    }

    public static <T> boolean hasBean(Route route, Class<T> cls) {
        return lookupBean(route, cls).isPresent();
    }

    public static <T> void addBean(Route route, Class<T> cls, T obj) {
        String groudId  = route.getGroup();
        CamelContext context = route.getCamelContext();
        String beanName = getBeanName(cls, groudId);
        context.getRegistry().bind(beanName, obj);
    }

    public static <T> Optional<T> lookupBeanForElement(Exchange exchange, String elementId, Class<T> cls) {
        return lookupBeanForElement(getRoute(exchange), elementId, cls);
    }

    public static <T> Optional<T> lookupBeanForElement(Route route, String elementId, Class<T> cls) {
        CamelContext context = route.getCamelContext();
        String beanName = getBeanName(cls, elementId);
        T bean = context.getRegistry().lookupByNameAndType(beanName, cls);
        return Optional.ofNullable(bean);
    }

    public static <T> T getBeanForElement(Exchange exchange, String elementId, Class<T> cls) {
        return getBeanForElement(getRoute(exchange), elementId, cls);
    }

    public static <T> T getBeanForElement(Route route, String elementId, Class<T> cls) {
        return lookupBeanForElement(route, elementId, cls)
                .orElseThrow(() -> new BeanNotFoundException(getBeanName(cls, elementId)));
    }

    public static Stream<ElementInfo> getElementsInfo(Exchange exchange) {
        return getElementsInfo(getRoute(exchange));
    }

    public static Stream<ElementInfo> getElementsInfo(Route route) {
        DeploymentInfo deploymentInfo = getBean(route, DeploymentInfo.class);
        return route.getCamelContext().getRegistry().findByType(ElementInfo.class)
                .stream()
                .filter(elementInfo -> elementInfo.getSnapshotId()
                        .equals(deploymentInfo.getSnapshot().getId()));
    }

    public static Collection<RouteRegistrationInfo> getRouteRegistrationInfo(
            CamelContext context,
            String snapshotId
    ) {
        return context.getRegistry()
                .findByType(RouteRegistrationInfo.class).stream()
                .filter(routeRegistrationInfo -> snapshotId.equals(routeRegistrationInfo.getSnapshotId()))
                .collect(Collectors.toList());
    }
}
