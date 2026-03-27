BEGIN;

ALTER TABLE integration_system
ALTER COLUMN integration_system_type TYPE VARCHAR(255) USING (
    CASE integration_system_type
      WHEN 0 THEN 'INTERNAL'
      WHEN 1 THEN 'EXTERNAL'
      WHEN 2 THEN 'IMPLEMENTED'
      ELSE NULL
    END
  );

ALTER TABLE integration_system
ALTER COLUMN protocol TYPE VARCHAR(255) USING (
    CASE protocol
      WHEN 0 THEN 'HTTP'
      WHEN 1 THEN 'AMQP'
      WHEN 2 THEN 'KAFKA'
      WHEN 3 THEN 'SOAP'
      WHEN 4 THEN 'GRAPHQL'
      WHEN 5 THEN 'METAMODEL'
      WHEN 6 THEN 'GRPC'
      ELSE NULL
    END
  );

ALTER TABLE config_parameters
ALTER COLUMN value_type TYPE VARCHAR(255) USING (
    CASE value_type
      WHEN 0 THEN 'STRING'
      WHEN 1 THEN 'INT'
      WHEN 2 THEN 'FLOAT'
      WHEN 3 THEN 'BOOLEAN'
      WHEN 4 THEN 'BYTE'
      ELSE NULL
    END
  );

ALTER TABLE environment
ALTER COLUMN source_type TYPE VARCHAR(255) USING (
    CASE source_type
      WHEN 0 THEN 'MANUAL'
      WHEN 1 THEN 'MAAS'
      WHEN 2 THEN 'MAAS_BY_CLASSIFIER'
      ELSE NULL
    END
  );

COMMIT;