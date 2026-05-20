package org.qubership.integration.platform.engine.model.maas.rabbitmq;

public final class MaasRabbitmqConstants {

    public static final String ENTITY_NAME = "name";
    public static final String ENTITY_ARGUMENTS = "arguments";

    public static final String BINDING_SOURCE = "source";
    public static final String BINDING_DESTINATION = "destination";
    public static final String BINDING_ROUTING_KEY = "routing_key";
    public static final String AUTO_DELETE = "auto_delete";
    public static final String DURABLE = "durable";

    public static final String EXCHANGE_TYPE = "type";
    public static final String EXCHANGE_TYPE_DIRECT = "direct";

    public static final String MAAS_ENV_PROP_PREFIX = "maas.";
    public static final String MAAS_ENV_PROP = "props.";
    public static final String MAAS_ENV_ARGS = "args.";
    public static final String MAAS_ENV_PROP_EXCHANGE = "exchange.";
    public static final String MAAS_ENV_PROP_QUEUE = "queue.";
    public static final String MAAS_ENV_PROP_BINDING = "binding.";

    public static final String  MAAS_ENV_PROPS_QUEUE_PREFIX = MAAS_ENV_PROP_PREFIX + MAAS_ENV_PROP_QUEUE + MAAS_ENV_PROP;
    public static final String  MAAS_ENV_ARGS_QUEUE_PREFIX = MAAS_ENV_PROP_PREFIX + MAAS_ENV_PROP_QUEUE + MAAS_ENV_ARGS;

    public static final String MAAS_ENV_PROPS_BINDING_PREFIX = MAAS_ENV_PROP_PREFIX + MAAS_ENV_PROP_BINDING
        + MAAS_ENV_PROP;
    public static final String MAAS_ENV_ARGS_BINDING_PREFIX = MAAS_ENV_PROP_PREFIX + MAAS_ENV_PROP_BINDING
        + MAAS_ENV_ARGS;

    public static final String MAAS_ENV_PROPS_EXCHANGE_PREFIX = MAAS_ENV_PROP_PREFIX + MAAS_ENV_PROP_EXCHANGE + MAAS_ENV_PROP;
    public static final String MAAS_ENV_ARGS_EXCHANGE_PREFIX = MAAS_ENV_PROP_PREFIX + MAAS_ENV_PROP_EXCHANGE + MAAS_ENV_ARGS;
    public static final String DEFAULT_VHOST_CLASSIFIER_NAME = "public";

    private MaasRabbitmqConstants() {}
}
