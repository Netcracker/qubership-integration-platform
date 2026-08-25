-- The whole schema change of this release, in one file.
--
-- Renames specification_group to api_group; adds operations.typed, which holds the protocol-specific operation data
-- that method and path are derived from; adds the API-level models.specification_type and specification_version,
-- populated at import; drops the de-materialized operation schema columns and the dead models.active; remaps the
-- retired CUSTOMER_MANUAL model source; and asserts (api_group_id, version) uniqueness.
--
-- Flyway runs each migration in its own transaction, so this file adds no explicit BEGIN/COMMIT: an explicit COMMIT
-- would end that transaction early and leave the schema-history row to a second one. When the unique constraint
-- below fails on a duplicate, the whole file rolls back and startup stops.

-- Renames specification_group to api_group throughout: table, columns, constraints, indexes, and the shared
-- modified-timestamp propagation function. Runs before the uk_models_on_api_group_id_version constraint
-- below, which references the renamed column.
--
-- Every rename is guarded by a catalog lookup because neither ALTER ... RENAME CONSTRAINT nor ALTER INDEX ...
-- RENAME supports IF EXISTS, and the guard also makes the block safe to re-apply. V100_000 skips schema creation on
-- a database carried over from the pre-consolidation product (flyway_schema_history already holds version 66.000),
-- so there the objects come from a migration set that is not in this repo and may carry different auto-generated
-- names. The NOT NULL constraints need the guard for one more reason: PostgreSQL records their names only from
-- version 17 on.
--
-- Guarded is not the same as optional. A constraint or index name that does not match is left alone, because the
-- name is cosmetic. The two tables and the two columns are not: the entity mapping and the trigger function below
-- address them by name, and CREATE OR REPLACE FUNCTION does not resolve table names, so a missed rename would leave
-- a function that only fails on the first write. The block therefore asserts the resulting shape and raises, which
-- stops the migration (FlywayInitializer.migrate() runs in @PostConstruct, so startup stops with it) instead of
-- letting the app boot against a schema it cannot query.
DO
$$
    BEGIN
        IF to_regclass('specification_group') IS NOT NULL AND to_regclass('api_group') IS NULL THEN
            ALTER TABLE specification_group RENAME TO api_group;
        END IF;

        IF EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = to_regclass('api_group') AND conname = 'pk_specification_group')
            AND NOT EXISTS (SELECT 1 FROM pg_constraint
                            WHERE conrelid = to_regclass('api_group') AND conname = 'pk_api_group') THEN
            ALTER TABLE api_group RENAME CONSTRAINT pk_specification_group TO pk_api_group;
        END IF;

        IF EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = to_regclass('api_group') AND conname = 'fk_specification_group_on_system')
            AND NOT EXISTS (SELECT 1 FROM pg_constraint
                            WHERE conrelid = to_regclass('api_group') AND conname = 'fk_api_group_on_system') THEN
            ALTER TABLE api_group RENAME CONSTRAINT fk_specification_group_on_system TO fk_api_group_on_system;
        END IF;

        IF to_regclass('idx_specification_group_system_id') IS NOT NULL
            AND to_regclass('idx_api_group_system_id') IS NULL THEN
            ALTER INDEX idx_specification_group_system_id RENAME TO idx_api_group_system_id;
        END IF;

        IF EXISTS (SELECT 1 FROM pg_attribute
                   WHERE attrelid = to_regclass('models') AND attname = 'specification_group_id'
                     AND NOT attisdropped)
            AND NOT EXISTS (SELECT 1 FROM pg_attribute
                            WHERE attrelid = to_regclass('models') AND attname = 'api_group_id'
                              AND NOT attisdropped) THEN
            ALTER TABLE models RENAME COLUMN specification_group_id TO api_group_id;
        END IF;

        IF EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = to_regclass('models') AND conname = 'fk_models_on_specification_group')
            AND NOT EXISTS (SELECT 1 FROM pg_constraint
                            WHERE conrelid = to_regclass('models') AND conname = 'fk_models_on_api_group') THEN
            ALTER TABLE models RENAME CONSTRAINT fk_models_on_specification_group TO fk_models_on_api_group;
        END IF;

        IF to_regclass('idx_models_specification_group_id') IS NOT NULL
            AND to_regclass('idx_models_api_group_id') IS NULL THEN
            ALTER INDEX idx_models_specification_group_id RENAME TO idx_models_api_group_id;
        END IF;

        IF to_regclass('specification_group_labels') IS NOT NULL AND to_regclass('api_group_labels') IS NULL THEN
            ALTER TABLE specification_group_labels RENAME TO api_group_labels;
        END IF;

        IF EXISTS (SELECT 1 FROM pg_attribute
                   WHERE attrelid = to_regclass('api_group_labels') AND attname = 'specification_group_id'
                     AND NOT attisdropped)
            AND NOT EXISTS (SELECT 1 FROM pg_attribute
                            WHERE attrelid = to_regclass('api_group_labels') AND attname = 'api_group_id'
                              AND NOT attisdropped) THEN
            ALTER TABLE api_group_labels RENAME COLUMN specification_group_id TO api_group_id;
        END IF;

        IF to_regclass('idx_specification_group_labels_specification_group_id') IS NOT NULL
            AND to_regclass('idx_api_group_labels_api_group_id') IS NULL THEN
            ALTER INDEX idx_specification_group_labels_specification_group_id RENAME TO idx_api_group_labels_api_group_id;
        END IF;

        IF to_regclass('uk_specification_group_labels') IS NOT NULL
            AND to_regclass('uk_api_group_labels') IS NULL THEN
            ALTER INDEX uk_specification_group_labels RENAME TO uk_api_group_labels;
        END IF;

        -- V100_000 declared this table's primary key and foreign key inline, so PostgreSQL auto-named both after
        -- the old table and column. Rename them too, or the schema keeps constraints named for objects that no
        -- longer exist.
        IF EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = to_regclass('api_group_labels')
                     AND conname = 'specification_group_labels_pkey')
            AND NOT EXISTS (SELECT 1 FROM pg_constraint
                            WHERE conrelid = to_regclass('api_group_labels')
                              AND conname = 'api_group_labels_pkey') THEN
            ALTER TABLE api_group_labels RENAME CONSTRAINT specification_group_labels_pkey TO api_group_labels_pkey;
        END IF;

        IF EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = to_regclass('api_group_labels')
                     AND conname = 'specification_group_labels_specification_group_id_fkey')
            AND NOT EXISTS (SELECT 1 FROM pg_constraint
                            WHERE conrelid = to_regclass('api_group_labels')
                              AND conname = 'api_group_labels_api_group_id_fkey') THEN
            ALTER TABLE api_group_labels
                RENAME CONSTRAINT specification_group_labels_specification_group_id_fkey
                    TO api_group_labels_api_group_id_fkey;
        END IF;

        -- PostgreSQL 17 gave NOT NULL constraints catalog rows, auto-named after the table and column, so these
        -- four carry the old names as well. On 16 and earlier the rows do not exist and the guards skip.
        IF EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = to_regclass('api_group') AND conname = 'specification_group_id_not_null')
            AND NOT EXISTS (SELECT 1 FROM pg_constraint
                            WHERE conrelid = to_regclass('api_group') AND conname = 'api_group_id_not_null') THEN
            ALTER TABLE api_group RENAME CONSTRAINT specification_group_id_not_null TO api_group_id_not_null;
        END IF;

        IF EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = to_regclass('api_group_labels')
                     AND conname = 'specification_group_labels_id_not_null')
            AND NOT EXISTS (SELECT 1 FROM pg_constraint
                            WHERE conrelid = to_regclass('api_group_labels')
                              AND conname = 'api_group_labels_id_not_null') THEN
            ALTER TABLE api_group_labels
                RENAME CONSTRAINT specification_group_labels_id_not_null TO api_group_labels_id_not_null;
        END IF;

        IF EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = to_regclass('api_group_labels')
                     AND conname = 'specification_group_labels_name_not_null')
            AND NOT EXISTS (SELECT 1 FROM pg_constraint
                            WHERE conrelid = to_regclass('api_group_labels')
                              AND conname = 'api_group_labels_name_not_null') THEN
            ALTER TABLE api_group_labels
                RENAME CONSTRAINT specification_group_labels_name_not_null TO api_group_labels_name_not_null;
        END IF;

        IF EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = to_regclass('api_group_labels')
                     AND conname = 'specification_group_labels_specification_group_id_not_null')
            AND NOT EXISTS (SELECT 1 FROM pg_constraint
                            WHERE conrelid = to_regclass('api_group_labels')
                              AND conname = 'api_group_labels_api_group_id_not_null') THEN
            ALTER TABLE api_group_labels
                RENAME CONSTRAINT specification_group_labels_specification_group_id_not_null
                    TO api_group_labels_api_group_id_not_null;
        END IF;

        -- The shape the rest of this file, the entity mapping, and update_parent_modified_param() all require.
        IF to_regclass('api_group') IS NULL THEN
            RAISE EXCEPTION 'Expected table specification_group or api_group, found neither';
        END IF;

        IF NOT EXISTS (SELECT 1 FROM pg_attribute
                       WHERE attrelid = to_regclass('models') AND attname = 'api_group_id' AND NOT attisdropped) THEN
            RAISE EXCEPTION 'Expected column models.specification_group_id or models.api_group_id, found neither';
        END IF;

        IF to_regclass('api_group_labels') IS NULL THEN
            RAISE EXCEPTION 'Expected table specification_group_labels or api_group_labels, found neither';
        END IF;

        IF NOT EXISTS (SELECT 1 FROM pg_attribute
                       WHERE attrelid = to_regclass('api_group_labels') AND attname = 'api_group_id'
                         AND NOT attisdropped) THEN
            RAISE EXCEPTION 'Expected column api_group_labels.specification_group_id or api_group_labels.api_group_id, found neither';
        END IF;
    END
$$;

-- CREATE OR REPLACE rewrites the whole body, so all five branches are retyped, not only the changed ones. The block
-- above has already asserted that api_group and models.api_group_id exist, so this needs no guard of its own.
CREATE OR REPLACE FUNCTION update_parent_modified_param() RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$update_parent_modified_param$
DECLARE
    table_name_string NAME := TG_TABLE_NAME;
BEGIN
    IF
        (table_name_string = 'operations')
    THEN
        UPDATE models
        SET modified_by_id   = NEW.modified_by_id,
            modified_by_name = NEW.modified_by_name,
            modified_when    = NEW.modified_when
        WHERE id = NEW.model_id;
    ELSIF
        (table_name_string = 'models')
    THEN
        UPDATE api_group
        SET modified_by_id   = NEW.modified_by_id,
            modified_by_name = NEW.modified_by_name,
            modified_when    = NEW.modified_when
        WHERE id = NEW.api_group_id;
    ELSIF
        (table_name_string = 'api_group')
    THEN
        UPDATE integration_system
        SET modified_by_id   = NEW.modified_by_id,
            modified_by_name = NEW.modified_by_name,
            modified_when    = NEW.modified_when
        WHERE id = NEW.system_id;
    ELSIF
        (table_name_string = 'specification_source')
    THEN
        UPDATE models
        SET modified_by_id   = NEW.modified_by_id,
            modified_by_name = NEW.modified_by_name,
            modified_when    = NEW.modified_when
        WHERE id = NEW.model_id;
    ELSIF
        (table_name_string = 'environment')
    THEN
        UPDATE integration_system
        SET modified_by_id   = NEW.modified_by_id,
            modified_by_name = NEW.modified_by_name,
            modified_when    = NEW.modified_when
        WHERE id = NEW.system_id;
    END IF;

    RETURN NEW;
END
$update_parent_modified_param$;

-- SPECIFICATION_GROUP -> API_GROUP on the two enum-backed varchar columns that persist the old wire value.
UPDATE logged_actions SET entity_type = 'API_GROUP' WHERE entity_type = 'SPECIFICATION_GROUP';
UPDATE logged_actions SET parent_type = 'API_GROUP' WHERE parent_type = 'SPECIFICATION_GROUP';
UPDATE import_instructions SET entity_type = 'API_GROUP' WHERE entity_type = 'SPECIFICATION_GROUP';

ALTER TABLE operations
    ADD COLUMN IF NOT EXISTS typed jsonb;

ALTER TABLE models
    ADD COLUMN IF NOT EXISTS specification_type    text,
    ADD COLUMN IF NOT EXISTS specification_version text;

ALTER TABLE models
    DROP COLUMN IF EXISTS active;

-- Operation schemas are re-derived on read by OperationSchemaExtractor; drop the materialized columns.
-- The specification column stays: nothing re-derives it.
ALTER TABLE operations
    DROP COLUMN IF EXISTS request_schema,
    DROP COLUMN IF EXISTS response_schemas;

-- SystemModelSource no longer declares CUSTOMER_MANUAL. The value was schema-valid and importable until this
-- release, so a row still holding it would fail enum hydration and turn every read of that model into a 500.
-- Only the database needs this: an archive carrying the old value is read through a @JsonAlias on MANUAL.
UPDATE models
SET source = 'MANUAL'
WHERE source = 'CUSTOMER_MANUAL';

-- Uniqueness was an app-level invariant only (ParserUtils count-then-save race), so duplicate rows may exist.
-- If they do, the constraint below fails and, because FlywayInitializer.migrate() runs in @PostConstruct, blocks
-- app startup for the whole rollout. PostgreSQL reports only "could not create unique index" and one sample key,
-- which leaves the operator to hunt for the rest by hand while every pod crash-loops, so this block names them
-- first: same rollback, same stopped startup, but the message carries the pairs to de-dup. The list is capped at
-- 20 pairs plus a total, because a log line holding thousands is no more usable than none.
--
-- The audit query behind the cap, for the pre-flight run in the plan's Post-Completion:
--   SELECT api_group_id, version, count(*) FROM catalog.models GROUP BY 1,2 HAVING count(*) > 1;
-- De-dup is a destructive human decision and is intentionally not automated here.
DO
$$
    DECLARE
        duplicate_total  bigint;
        duplicate_sample text;
    BEGIN
        -- Both columns are nullable and UNIQUE treats nulls as distinct, so a null in either one never
        -- conflicts. Skipping those rows keeps the check from stopping startup over a pair the constraint
        -- would have accepted.
        SELECT count(*), string_agg(pair, ', ' ORDER BY pair_rank) FILTER (WHERE pair_rank <= 20)
        INTO duplicate_total, duplicate_sample
        FROM (SELECT format('(%s, %s)', api_group_id, version)          AS pair,
                     row_number() OVER (ORDER BY api_group_id, version) AS pair_rank
              FROM models
              WHERE api_group_id IS NOT NULL
                AND version IS NOT NULL
              GROUP BY api_group_id, version
              HAVING count(*) > 1) duplicates;

        IF duplicate_total > 0 THEN
            RAISE EXCEPTION 'Table models holds % duplicate (api_group_id, version) pair(s), so constraint '
                'uk_models_on_api_group_id_version cannot be added. First %: %',
                duplicate_total, least(duplicate_total, 20), duplicate_sample
                USING HINT = 'Keep one model per pair, delete the rest, then restart. '
                             'Nothing in this migration was applied.';
        END IF;
    END
$$;

ALTER TABLE models
    ADD CONSTRAINT uk_models_on_api_group_id_version
        UNIQUE (api_group_id, version);
