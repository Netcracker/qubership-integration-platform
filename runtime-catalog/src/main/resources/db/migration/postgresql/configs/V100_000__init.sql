-- Copyright 2024-2025 NetCracker Technology Corporation
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

DO
$$

    DECLARE
        executed BOOLEAN;
    BEGIN
        SELECT TRUE INTO executed FROM flyway_schema_history WHERE version = '66.000';

        IF COALESCE(executed, FALSE) IS FALSE THEN

            -- config_parameters table

            CREATE TABLE config_parameters
            (
                id               VARCHAR(255) NOT NULL
                    CONSTRAINT pk_config_parameters
                        PRIMARY KEY,
                created_when     TIMESTAMP,
                description      VARCHAR(255),
                modified_when    TIMESTAMP,
                name             VARCHAR(255),
                namespace        VARCHAR(255),
                value            TEXT,
                value_type       INTEGER,
                created_by_id    VARCHAR(255),
                created_by_name  VARCHAR(255),
                modified_by_id   VARCHAR(255),
                modified_by_name VARCHAR(255),
                CONSTRAINT uk_config_parameters_on_namespace_name
                    UNIQUE (namespace, name)
            );


            -- folders table

            CREATE TABLE folders
            (
                id               VARCHAR(255) NOT NULL
                    CONSTRAINT pk_folders
                        PRIMARY KEY,
                created_when     TIMESTAMP,
                description      VARCHAR(255),
                modified_when    TIMESTAMP,
                name             VARCHAR(255),
                parent_folder_id VARCHAR(255)
                    CONSTRAINT fk_folders_on_parent_folder
                        REFERENCES folders
                        ON DELETE CASCADE,
                created_by_id    VARCHAR(255),
                created_by_name  VARCHAR(255),
                modified_by_id   VARCHAR(255),
                modified_by_name VARCHAR(255),
                CONSTRAINT folder_recursion_loop_check
                    CHECK ((id)::TEXT <> (parent_folder_id)::TEXT)
            );


            -- chains table

            CREATE TABLE chains
            (
                id                   VARCHAR(255) NOT NULL
                    CONSTRAINT pk_chains
                        PRIMARY KEY,
                created_when         TIMESTAMP,
                description          VARCHAR(255),
                modified_when        TIMESTAMP,
                name                 VARCHAR(255),
                parent_folder_id     VARCHAR(255)
                    CONSTRAINT fk_chains_on_parent_folder
                        REFERENCES folders
                        ON DELETE CASCADE,
                current_snapshot_id  VARCHAR(255),
                unsaved_changes      BOOLEAN DEFAULT TRUE,
                default_swimlane_id  VARCHAR(255),
                reuse_swimlane_id    VARCHAR(255),
                created_by_id        VARCHAR(255),
                created_by_name      VARCHAR(255),
                modified_by_id       VARCHAR(255),
                modified_by_name     VARCHAR(255),
                business_description TEXT,
                assumptions          TEXT,
                out_of_scope         TEXT,
                overridden_by_chain  VARCHAR(255),
                overrides_chain      VARCHAR(255),
                last_import_hash     TEXT,
                CONSTRAINT check_chains_default_and_reuse_swimlanes
                    CHECK ((default_swimlane_id)::TEXT <> (reuse_swimlane_id)::TEXT)
            );

            CREATE INDEX idx_chains_current_snapshot_id
                ON chains (current_snapshot_id);

            CREATE INDEX idx_chains_parent_folder_id
                ON chains (parent_folder_id);

            CREATE INDEX idx_chains_default_swimlane_id
                ON chains (default_swimlane_id);

            CREATE INDEX idx_chains_reuse_swimlane_id
                ON chains (reuse_swimlane_id);


            -- elements table

            CREATE TABLE elements
            (
                dtype               VARCHAR(31),
                id                  VARCHAR(255)          NOT NULL
                    CONSTRAINT pk_elements
                        PRIMARY KEY,
                created_when        TIMESTAMP,
                description         VARCHAR(255),
                modified_when       TIMESTAMP,
                name                VARCHAR(255),
                properties          JSONB,
                type                VARCHAR(255),
                chain_id            VARCHAR(255)
                    CONSTRAINT fk_elements_on_chain
                        REFERENCES chains
                        ON DELETE CASCADE,
                parent_element_id   VARCHAR(255)
                    CONSTRAINT fk_elements_on_parent_element
                        REFERENCES elements
                        ON DELETE CASCADE,
                snapshot_id         VARCHAR(255),
                original_id         VARCHAR(255),
                environment         JSONB,
                swimlane_id         VARCHAR(255)
                    CONSTRAINT fk_elements_on_swimlane
                        REFERENCES elements
                        ON DELETE SET NULL,
                created_by_id       VARCHAR(255),
                created_by_name     VARCHAR(255),
                modified_by_id      VARCHAR(255),
                modified_by_name    VARCHAR(255),
                is_default_swimlane BOOLEAN DEFAULT FALSE NOT NULL,
                is_reuse_swimlane   BOOLEAN DEFAULT FALSE NOT NULL
            );

            ALTER TABLE chains
                ADD CONSTRAINT fk_default_swimlane_on_chain
                    FOREIGN KEY (default_swimlane_id) REFERENCES elements
                        ON DELETE SET NULL;

            ALTER TABLE chains
                ADD CONSTRAINT fk_reuse_swimlane_on_chain
                    FOREIGN KEY (reuse_swimlane_id) REFERENCES elements
                        ON DELETE SET NULL;


            -- dependencies table

            CREATE TABLE dependencies
            (
                dependency_id   VARCHAR(255) NOT NULL
                    CONSTRAINT pk_dependencies
                        PRIMARY KEY,
                element_from_id VARCHAR(255)
                    CONSTRAINT fk_dependencies_on_element_from
                        REFERENCES elements
                        ON DELETE CASCADE,
                element_to_id   VARCHAR(255)
                    CONSTRAINT fk_dependencies_on_element_to
                        REFERENCES elements
                        ON DELETE CASCADE,
                CONSTRAINT uk_dependencies
                    UNIQUE (element_from_id, element_to_id)
            );

            CREATE INDEX idx_dependencies_element_from_id
                ON dependencies (element_from_id);

            CREATE INDEX idx_dependencies_element_to_id
                ON dependencies (element_to_id);

            CREATE INDEX idx_elements_parent_element_id
                ON elements (parent_element_id);

            CREATE INDEX idx_elements_snapshot_id
                ON elements (snapshot_id);

            CREATE UNIQUE INDEX idx_elements_chain_id_default_swimlane_unique
                ON elements (chain_id)
                WHERE (((dtype)::TEXT = 'swimlane_elements'::TEXT) AND (is_default_swimlane IS TRUE));

            CREATE UNIQUE INDEX idx_elements_chain_id_reuse_swimlane_unique
                ON elements (chain_id)
                WHERE (((dtype)::TEXT = 'swimlane_elements'::TEXT) AND (is_reuse_swimlane IS TRUE));

            CREATE INDEX idx_elements_type
                ON elements (type);

            CREATE INDEX idx_elements_properties_integration_operation_path
                ON elements (JSONB_EXTRACT_PATH_TEXT(properties, VARIADIC ARRAY ['integrationOperationPath'::TEXT]));

            CREATE INDEX idx_elements_properties_integration_operation_protocol_type
                ON elements (JSONB_EXTRACT_PATH_TEXT(properties, VARIADIC
                                                     ARRAY ['integrationOperationProtocolType'::TEXT]));

            CREATE INDEX idx_elements_properties_context_path
                ON elements (JSONB_EXTRACT_PATH_TEXT(properties, VARIADIC ARRAY ['contextPath'::TEXT]));

            CREATE INDEX idx_elements_properties_topics
                ON elements (JSONB_EXTRACT_PATH_TEXT(properties, VARIADIC ARRAY ['topics'::TEXT]));

            CREATE INDEX idx_elements_properties_queue
                ON elements (JSONB_EXTRACT_PATH_TEXT(properties, VARIADIC ARRAY ['queue'::TEXT]));

            CREATE INDEX idx_elements_properties_exchange
                ON elements (JSONB_EXTRACT_PATH_TEXT(properties, VARIADIC ARRAY ['exchange'::TEXT]));

            CREATE INDEX idx_elements_chain_id
                ON elements (chain_id);

            CREATE INDEX idx_elements_swimlane_id
                ON elements (swimlane_id);

            CREATE INDEX idx_elements_original_id
                ON elements (original_id);


            -- snapshots table

            CREATE TABLE snapshots
            (
                id                  VARCHAR(255) NOT NULL
                    CONSTRAINT pk_snapshots
                        PRIMARY KEY,
                created_when        TIMESTAMP,
                name                VARCHAR(255),
                xml_configuration   TEXT,
                chain_id            VARCHAR(255)
                    CONSTRAINT fk_snapshots_on_chain
                        REFERENCES chains
                        ON DELETE CASCADE,
                description         VARCHAR(255),
                modified_when       TIMESTAMP,
                default_swimlane_id VARCHAR(255)
                    CONSTRAINT fk_default_swimlane_on_snapshot
                        REFERENCES elements
                        ON DELETE SET NULL,
                reuse_swimlane_id   VARCHAR(255)
                    CONSTRAINT fk_reuse_swimlane_on_snapshot
                        REFERENCES elements
                        ON DELETE SET NULL,
                created_by_id       VARCHAR(255),
                created_by_name     VARCHAR(255),
                modified_by_id      VARCHAR(255),
                modified_by_name    VARCHAR(255)
            );

            ALTER TABLE chains
                ADD CONSTRAINT fk_chains_on_current_snapshot
                    FOREIGN KEY (current_snapshot_id) REFERENCES snapshots;


            -- deployments table

            CREATE TABLE deployments
            (
                id                     VARCHAR(255) NOT NULL
                    CONSTRAINT pk_deployments
                        PRIMARY KEY,
                domain                 VARCHAR(255),
                suspended              BOOLEAN,
                chain_id               VARCHAR(255)
                    CONSTRAINT fk_deployments_on_chain
                        REFERENCES chains
                        ON DELETE CASCADE,
                snapshot_id            VARCHAR(255)
                    CONSTRAINT fk_deployments_on_snapshot
                        REFERENCES snapshots
                        ON DELETE CASCADE,
                created_when           TIMESTAMP,
                log_logging_level      VARCHAR(255),
                log_payload_level      VARCHAR(255),
                sessions_logging_level VARCHAR(255),
                dpt_events_enabled     BOOLEAN DEFAULT FALSE,
                name                   VARCHAR,
                masking_enabled        BOOLEAN DEFAULT FALSE,
                created_by_id          VARCHAR(255),
                created_by_name        VARCHAR(255)
            );

            CREATE INDEX idx_deployments_domain
                ON deployments (domain);

            CREATE INDEX idx_deployments_sessions_logging_level
                ON deployments (sessions_logging_level);

            CREATE INDEX idx_deployments_chain_id
                ON deployments (chain_id);

            CREATE INDEX idx_deployments_snapshot_id
                ON deployments (snapshot_id);

            ALTER TABLE elements
                ADD CONSTRAINT fk_elements_on_snapshot
                    FOREIGN KEY (snapshot_id) REFERENCES snapshots
                        ON DELETE CASCADE;

            CREATE INDEX idx_snapshots_default_swimlane_id
                ON snapshots (default_swimlane_id);

            CREATE INDEX idx_snapshots_reuse_swimlane_id
                ON snapshots (reuse_swimlane_id);

            CREATE INDEX idx_snapshots_chain_id
                ON snapshots (chain_id);

            CREATE INDEX idx_folders_parent_folder_id
                ON folders (parent_folder_id);


            -- masked_fields table

            CREATE TABLE masked_fields
            (
                id               VARCHAR(255) NOT NULL
                    CONSTRAINT pk_masked_fields
                        PRIMARY KEY,
                name             VARCHAR(255),
                chain_id         VARCHAR(255)
                    CONSTRAINT fk_masked_fields_on_chain
                        REFERENCES chains
                        ON DELETE CASCADE,
                created_when     TIMESTAMP,
                modified_when    TIMESTAMP,
                description      VARCHAR(255),
                snapshot_id      VARCHAR(255)
                    CONSTRAINT fk_masked_fields_on_snapshot
                        REFERENCES snapshots
                        ON DELETE CASCADE,
                created_by_id    VARCHAR(255),
                created_by_name  VARCHAR(255),
                modified_by_id   VARCHAR(255),
                modified_by_name VARCHAR(255)
            );

            CREATE INDEX idx_masked_fields_snapshot_id
                ON masked_fields (snapshot_id);

            CREATE INDEX idx_masked_fields_chain_id
                ON masked_fields (chain_id);


            -- logged_actions table

            CREATE TABLE logged_actions
            (
                id          VARCHAR(255) NOT NULL
                    CONSTRAINT pk_logged_actions
                        PRIMARY KEY,
                action_time TIMESTAMP,
                entity_type VARCHAR(255),
                entity_id   VARCHAR(255),
                entity_name VARCHAR(255),
                parent_id   VARCHAR(255),
                operation   VARCHAR(255),
                user_id     VARCHAR(255),
                username    VARCHAR(255),
                parent_name VARCHAR(255),
                parent_type VARCHAR(255),
                request_id  VARCHAR(255)
            );

            CREATE INDEX logged_actions_timestamp_idx
                ON logged_actions (action_time);


            -- compiled_libraries table

            CREATE TABLE compiled_libraries
            (
                id               VARCHAR(255) NOT NULL
                    CONSTRAINT pk_compiled_libraries
                        PRIMARY KEY,
                name             VARCHAR(255),
                description      VARCHAR(255),
                data             BYTEA,
                created_when     TIMESTAMP,
                modified_when    TIMESTAMP,
                created_by_id    VARCHAR(255),
                created_by_name  VARCHAR(255),
                modified_by_id   VARCHAR(255),
                modified_by_name VARCHAR(255)
            );


            -- integration_system table

            CREATE TABLE integration_system
            (
                id                      VARCHAR(255) NOT NULL
                    CONSTRAINT pk_integration_system
                        PRIMARY KEY,
                name                    VARCHAR(255),
                description             VARCHAR(255),
                active_environment_id   VARCHAR(255),
                integration_system_type INTEGER,
                internal_service_name   VARCHAR(255),
                protocol                INTEGER,
                created_when            TIMESTAMP,
                modified_when           TIMESTAMP,
                created_by_id           VARCHAR(255),
                created_by_name         VARCHAR(255),
                modified_by_id          VARCHAR(255),
                modified_by_name        VARCHAR(255)
            );


            -- environment table

            CREATE TABLE environment
            (
                id               VARCHAR(255) NOT NULL
                    CONSTRAINT pk_environment
                        PRIMARY KEY,
                name             VARCHAR(255),
                description      VARCHAR(255),
                address          VARCHAR(255),
                source_type      INTEGER,
                system_id        VARCHAR(255)
                    CONSTRAINT fk_environment_on_system
                        REFERENCES integration_system,
                maas_instance_id VARCHAR(255),
                properties       JSONB,
                created_when     TIMESTAMP,
                modified_when    TIMESTAMP,
                created_by_id    VARCHAR(255),
                created_by_name  VARCHAR(255),
                modified_by_id   VARCHAR(255),
                modified_by_name VARCHAR(255)
            );

            CREATE INDEX idx_environment_system_id
                ON environment (system_id);


            -- environment_labels table

            CREATE TABLE environment_labels
            (
                environment_id VARCHAR(255)
                    CONSTRAINT fk_environment_labels_on_environment
                        REFERENCES environment,
                labels         VARCHAR
            );

            CREATE INDEX idx_environment_labels_environment_id
                ON environment_labels (environment_id);


            -- specification_group table

            CREATE TABLE specification_group
            (
                id               VARCHAR(255) NOT NULL
                    CONSTRAINT pk_specification_group
                        PRIMARY KEY,
                name             VARCHAR(255),
                description      VARCHAR(255),
                url              VARCHAR(2000),
                system_id        VARCHAR(255)
                    CONSTRAINT fk_specification_group_on_system
                        REFERENCES integration_system,
                synchronization  BOOLEAN,
                created_when     TIMESTAMP,
                modified_when    TIMESTAMP,
                created_by_id    VARCHAR(255),
                created_by_name  VARCHAR(255),
                modified_by_id   VARCHAR(255),
                modified_by_name VARCHAR(255)
            );


            -- models table

            CREATE TABLE models
            (
                id                     VARCHAR(255) NOT NULL
                    CONSTRAINT pk_models
                        PRIMARY KEY,
                name                   VARCHAR(255),
                description            VARCHAR(255),
                active                 BOOLEAN,
                deprecated             BOOLEAN,
                version                VARCHAR(255),
                source                 VARCHAR(255),
                specification_group_id VARCHAR(255)
                    CONSTRAINT fk_models_on_specification_group
                        REFERENCES specification_group,
                compiled_library_id    VARCHAR(255)
                    CONSTRAINT fk_models_compiled_library_id
                        REFERENCES compiled_libraries,
                created_when           TIMESTAMP,
                modified_when          TIMESTAMP,
                created_by_id          VARCHAR(255),
                created_by_name        VARCHAR(255),
                modified_by_id         VARCHAR(255),
                modified_by_name       VARCHAR(255)
            );

            CREATE INDEX idx_models_compiled_library_id
                ON models (compiled_library_id);

            CREATE INDEX idx_models_specification_group_id
                ON models (specification_group_id);


            -- operations table

            CREATE TABLE operations
            (
                id               VARCHAR(255) NOT NULL
                    CONSTRAINT pk_operations
                        PRIMARY KEY,
                name             VARCHAR(255),
                description      VARCHAR(255),
                method           VARCHAR(255),
                path             VARCHAR(2000),
                model_id         VARCHAR(255)
                    CONSTRAINT fk_operations_on_model
                        REFERENCES models,
                specification    JSONB,
                request_schema   JSONB,
                response_schemas JSONB,
                created_when     TIMESTAMP,
                modified_when    TIMESTAMP,
                created_by_id    VARCHAR(255),
                created_by_name  VARCHAR(255),
                modified_by_id   VARCHAR(255),
                modified_by_name VARCHAR(255)
            );

            CREATE INDEX idx_operations_model_id
                ON operations (model_id);

            CREATE INDEX idx_specification_group_system_id
                ON specification_group (system_id);


            -- specification_source table

            CREATE TABLE specification_source
            (
                id               VARCHAR(255) NOT NULL
                    CONSTRAINT pk_specification_source
                        PRIMARY KEY,
                name             VARCHAR(255),
                description      VARCHAR(255),
                model_id         VARCHAR(255)
                    CONSTRAINT fk_specification_source_on_model
                        REFERENCES models,
                main             BOOLEAN,
                source           TEXT,
                source_hash      VARCHAR(64),
                created_when     TIMESTAMP,
                modified_when    TIMESTAMP,
                created_by_id    VARCHAR(255),
                created_by_name  VARCHAR(255),
                modified_by_id   VARCHAR(255),
                modified_by_name VARCHAR(255)
            );

            CREATE INDEX idx_specification_source_model_id
                ON specification_source (model_id);


            -- deployment_routes table

            CREATE TABLE deployment_routes
            (
                id              VARCHAR(255) NOT NULL
                    CONSTRAINT pk_deployment_routes
                        PRIMARY KEY,
                deployment_id   VARCHAR(255)
                    CONSTRAINT fk_deployment_routes_deployment_id
                        REFERENCES deployments
                        ON DELETE CASCADE,
                path            VARCHAR(512),
                type            VARCHAR(255),
                connect_timeout INTEGER,
                gateway_prefix  VARCHAR(255),
                variable_name   VARCHAR(255)
            );

            CREATE INDEX idx_deployment_routes_deployment_id
                ON deployment_routes (deployment_id);


            -- chain_labels table

            CREATE TABLE chain_labels
            (
                name      VARCHAR(255) NOT NULL,
                chain_id  VARCHAR(255) NOT NULL
                    CONSTRAINT fk_labels_on_chain
                        REFERENCES chains
                        ON DELETE CASCADE,
                id        VARCHAR(255) NOT NULL
                    CONSTRAINT pk_chain_labels
                        PRIMARY KEY,
                technical BOOLEAN DEFAULT FALSE,
                CONSTRAINT uk_chain_labels
                    UNIQUE (name, chain_id, technical)
            );

            CREATE INDEX idx_chain_labels_chain_id
                ON chain_labels (chain_id);


            -- import_sessions table

            CREATE TABLE import_sessions
            (
                id                    VARCHAR(50) NOT NULL
                    PRIMARY KEY,
                result                TEXT,
                completion_percentage SMALLINT,
                error                 TEXT,
                modified_when         TIMESTAMP
            );


            -- validation_chains_alerts table

            CREATE TABLE validation_chains_alerts
            (
                id              VARCHAR(255) NOT NULL
                    CONSTRAINT pk_validation_chains_alerts
                        PRIMARY KEY,
                created_when    TIMESTAMP,
                created_by_id   VARCHAR(255),
                created_by_name VARCHAR(255),
                validation_id   VARCHAR(255),
                chain_id        VARCHAR(255)
                    CONSTRAINT fk_validation_chains_alerts_on_chain
                        REFERENCES chains
                        ON DELETE CASCADE,
                element_id      VARCHAR(255)
                    CONSTRAINT fk_validation_chains_alerts_on_element
                        REFERENCES elements
                        ON DELETE CASCADE,
                properties      JSONB
            );

            CREATE INDEX idx_validation_chains_alerts_validation_id
                ON validation_chains_alerts (validation_id);


            -- validation_status table

            CREATE TABLE validation_status
            (
                validation_id    VARCHAR(255) NOT NULL
                    CONSTRAINT pk_validation_status
                        PRIMARY KEY,
                state            VARCHAR(100),
                message          TEXT,
                created_when     TIMESTAMP,
                created_by_id    VARCHAR(255),
                created_by_name  VARCHAR(255),
                modified_when    TIMESTAMP,
                modified_by_id   VARCHAR(255),
                modified_by_name VARCHAR(255),
                started_when     TIMESTAMP
            );


            -- snapshot_labels table

            CREATE TABLE snapshot_labels
            (
                name        VARCHAR(255) NOT NULL,
                snapshot_id VARCHAR(255) NOT NULL
                    REFERENCES snapshots
                        ON DELETE CASCADE,
                id          VARCHAR(255) NOT NULL
                    PRIMARY KEY,
                technical   BOOLEAN DEFAULT FALSE
            );

            CREATE INDEX idx_snapshot_labels_snapshot_id
                ON snapshot_labels (snapshot_id);

            CREATE UNIQUE INDEX uk_snapshot_labels
                ON snapshot_labels (name, snapshot_id, technical);


            -- integration_system_labels table

            CREATE TABLE integration_system_labels
            (
                name                  VARCHAR(255) NOT NULL,
                integration_system_id VARCHAR(255) NOT NULL
                    REFERENCES integration_system
                        ON DELETE CASCADE,
                id                    VARCHAR(255) NOT NULL
                    PRIMARY KEY,
                technical             BOOLEAN DEFAULT FALSE
            );

            CREATE INDEX idx_integration_system_labels_integration_system_id
                ON integration_system_labels (integration_system_id);

            CREATE UNIQUE INDEX uk_integration_system_labels
                ON integration_system_labels (name, integration_system_id, technical);


            -- specification_group_labels table

            CREATE TABLE specification_group_labels
            (
                name                   VARCHAR(255) NOT NULL,
                specification_group_id VARCHAR(255) NOT NULL
                    REFERENCES specification_group
                        ON DELETE CASCADE,
                id                     VARCHAR(255) NOT NULL
                    PRIMARY KEY,
                technical              BOOLEAN DEFAULT FALSE
            );

            CREATE INDEX idx_specification_group_labels_specification_group_id
                ON specification_group_labels (specification_group_id);

            CREATE UNIQUE INDEX uk_specification_group_labels
                ON specification_group_labels (name, specification_group_id, technical);


            -- model_labels table

            CREATE TABLE model_labels
            (
                name      VARCHAR(255) NOT NULL,
                model_id  VARCHAR(255) NOT NULL
                    REFERENCES models
                        ON DELETE CASCADE,
                id        VARCHAR(255) NOT NULL
                    PRIMARY KEY,
                technical BOOLEAN DEFAULT FALSE
            );

            CREATE INDEX idx_model_labels_model_id
                ON model_labels (model_id);

            CREATE UNIQUE INDEX uk_model_labels
                ON model_labels (name, model_id, technical);


            -- detailed_design_templates table

            CREATE TABLE detailed_design_templates
            (
                id               VARCHAR(255) NOT NULL
                    CONSTRAINT pk_detailed_design_templates
                        PRIMARY KEY,
                name             VARCHAR(255),
                description      VARCHAR(255),
                content          TEXT,
                created_when     TIMESTAMP,
                created_by_id    VARCHAR(255),
                created_by_name  VARCHAR(255),
                modified_when    TIMESTAMP,
                modified_by_id   VARCHAR(255),
                modified_by_name VARCHAR(255)
            );


            -- import_instructions table

            CREATE TABLE import_instructions
            (
                id               VARCHAR(255) NOT NULL
                    PRIMARY KEY,
                entity_type      VARCHAR(255) NOT NULL,
                action           VARCHAR(255) NOT NULL,
                overridden_by_id VARCHAR(255),
                modified_when    TIMESTAMP,
                CONSTRAINT overridden_by_id_only_for_override
                    CHECK ((((action)::TEXT = 'OVERRIDE'::TEXT) AND (overridden_by_id IS NOT NULL)) OR
                           (((action)::TEXT <> 'OVERRIDE'::TEXT) AND (overridden_by_id IS NULL)))
            );


            -- import_instruction_labels table

            CREATE TABLE import_instruction_labels
            (
                id             VARCHAR(255) NOT NULL
                    PRIMARY KEY,
                instruction_id VARCHAR(255) NOT NULL
                    REFERENCES import_instructions,
                name           VARCHAR(255) NOT NULL,
                CONSTRAINT label_unique_per_instructions
                    UNIQUE (name, instruction_id)
            );


            -- functions and triggers

            CREATE FUNCTION update_chain_modified_param() RETURNS TRIGGER
                LANGUAGE plpgsql
            AS
            $update_chain_modified_param$
            BEGIN
                UPDATE chains
                SET modified_by_id   = NEW.modified_by_id,
                    modified_by_name = NEW.modified_by_name,
                    modified_when    = NEW.modified_when
                WHERE id = NEW.chain_id;
                RETURN NEW;
            END
            $update_chain_modified_param$;

            CREATE TRIGGER update_chain_modified_param
                AFTER INSERT OR UPDATE
                ON elements
                FOR EACH ROW
            EXECUTE PROCEDURE update_chain_modified_param('element');

            CREATE FUNCTION update_parent_modified_param() RETURNS TRIGGER
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
                    UPDATE specification_group
                    SET modified_by_id   = NEW.modified_by_id,
                        modified_by_name = NEW.modified_by_name,
                        modified_when    = NEW.modified_when
                    WHERE id = NEW.specification_group_id;
                ELSIF
                    (table_name_string = 'specification_group')
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

            CREATE TRIGGER update_parent_modified_param
                AFTER INSERT OR UPDATE
                ON environment
                FOR EACH ROW
            EXECUTE PROCEDURE update_parent_modified_param();

            CREATE TRIGGER update_parent_modified_param
                AFTER INSERT OR UPDATE
                ON models
                FOR EACH ROW
            EXECUTE PROCEDURE update_parent_modified_param();

            CREATE TRIGGER update_parent_modified_param
                AFTER INSERT OR UPDATE
                ON operations
                FOR EACH ROW
            EXECUTE PROCEDURE update_parent_modified_param();

            CREATE TRIGGER update_parent_modified_param
                AFTER INSERT OR UPDATE
                ON specification_group
                FOR EACH ROW
            EXECUTE PROCEDURE update_parent_modified_param();

            CREATE TRIGGER update_parent_modified_param
                AFTER INSERT OR UPDATE
                ON specification_source
                FOR EACH ROW
            EXECUTE PROCEDURE update_parent_modified_param();

            CREATE FUNCTION import_instructions_unique_override_func() RETURNS TRIGGER
                LANGUAGE plpgsql
            AS
            $import_instructions_unique_override_func$
            DECLARE
                found_id VARCHAR(255);
            BEGIN
                SELECT CASE
                           WHEN overridden_by_id = NEW.id THEN NEW.id
                           WHEN id = NEW.overridden_by_id OR overridden_by_id = NEW.overridden_by_id
                               THEN NEW.overridden_by_id
                           END
                INTO found_id
                FROM import_instructions
                WHERE id = NEW.overridden_by_id
                   OR overridden_by_id = NEW.id
                   OR overridden_by_id = NEW.overridden_by_id;

                IF found_id IS NOT NULL
                THEN
                    RAISE EXCEPTION 'duplicate key value violates unique constraint "import_instructions_unique_override_idx"'
                        USING ERRCODE = '23505', DETAIL = found_id;
                END IF;

                RETURN NEW;
            END
            $import_instructions_unique_override_func$;

            CREATE TRIGGER import_instructions_unique_override
                BEFORE INSERT OR UPDATE
                ON import_instructions
                FOR EACH ROW
                WHEN (new.entity_type::TEXT = 'CHAIN'::TEXT)
            EXECUTE PROCEDURE import_instructions_unique_override_func();
        END IF;

    END
$$;
