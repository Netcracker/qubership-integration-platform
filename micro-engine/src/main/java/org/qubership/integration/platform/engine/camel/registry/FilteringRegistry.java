package org.qubership.integration.platform.engine.camel.registry;

import org.apache.camel.NoSuchBeanTypeException;
import org.apache.camel.RuntimeCamelException;
import org.apache.camel.spi.Registry;

import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;

public class FilteringRegistry implements Registry {
    private final Registry delegate;
    private final Predicate<Object> predicate;

    public FilteringRegistry(Registry delegate, Predicate<Object> predicate) {
        this.delegate = delegate;
        this.predicate = predicate;
    }

    private <T> T filterObject(T obj) {
        return predicate.test(obj) ? obj : null;
    }

    @Override
    public void bind(String id, Object bean) throws RuntimeCamelException {
        delegate.bind(id, bean);
    }

    @Override
    public void bind(String id, Object bean, String initMethod, String destroyMethod) throws RuntimeCamelException {
        delegate.bind(id, bean, initMethod, destroyMethod);
    }

    @Override
    public void bind(String id, Class<?> type, Object bean) throws RuntimeCamelException {
        delegate.bind(id, type, bean);
    }

    @Override
    public void bind(String id, Class<?> type, Object bean, String initMethod, String destroyMethod) throws RuntimeCamelException {
        delegate.bind(id, type, bean, initMethod, destroyMethod);
    }

    @Override
    public void bind(String id, Class<?> type, Supplier<Object> bean) throws RuntimeCamelException {
        delegate.bind(id, type, bean);
    }

    @Override
    public void bind(String id, Class<?> type, Supplier<Object> bean, String initMethod, String destroyMethod) throws RuntimeCamelException {
        delegate.bind(id, type, bean, initMethod, destroyMethod);
    }

    @Override
    public void bindAsPrototype(String id, Class<?> type, Supplier<Object> bean) throws RuntimeCamelException {
        delegate.bindAsPrototype(id, type, bean);
    }

    @Override
    public void unbind(String id) {
        delegate.unbind(id);
    }

    @Override
    public Object wrap(Object value) {
        return delegate.wrap(value);
    }

    @Override
    public Object lookupByName(String name) {
        return filterObject(delegate.lookupByName(name));
    }

    @Override
    public <T> T lookupByNameAndType(String name, Class<T> type) {
        return filterObject(delegate.lookupByNameAndType(name, type));
    }

    @Override
    public <T> Map<String, T> findByTypeWithName(Class<T> type) {
        return delegate.findByTypeWithName(type)
                .entrySet()
                .stream()
                .filter(e -> predicate.test(e.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public <T> T findSingleByType(Class<T> type) {
        return filterObject(delegate.findSingleByType(type));
    }

    @Override
    public <T> Set<T> findByType(Class<T> type) {
        return delegate.findByType(type).stream().filter(predicate).collect(Collectors.toSet());
    }

    @Override
    public <T> T mandatoryFindSingleByType(Class<T> type) {
        T obj = filterObject(delegate.mandatoryFindSingleByType(type));
        if (isNull(obj)) {
            throw new NoSuchBeanTypeException(type);
        }
        return obj;
    }

    @Override
    public Object unwrap(Object value) {
        return delegate.unwrap(value);
    }
}
