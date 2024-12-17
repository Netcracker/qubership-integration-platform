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
        SELECT true INTO executed FROM flyway_schema_history where version = '10.001';

        IF coalesce(executed, false) is false THEN

            create table IF NOT EXISTS sessions_info
            (
                id                  varchar(255) not null
                    constraint pk_sessions_info
                        primary key,
                started             timestamp,
                finished            timestamp,
                duration            bigint       not null,
                execution_status    integer,
                chain_id            varchar(255),
                chain_name          varchar(255),
                engine_address      varchar(255),
                logging_level       varchar(255),
                snapshot_name       varchar(255),
                correlation_id      varchar(255),
                original_session_id varchar(255)
                    constraint fk_sessions_info_on_originalsession
                        references sessions_info
                        on delete cascade,
                domain              varchar(255) default NULL::character varying
            );

            alter table sessions_info
                owner to postgres;

            create index IF NOT EXISTS idx_sessions_info_started
                on sessions_info (started);

            create index IF NOT EXISTS idx_sessions_info_original_session_id
                on sessions_info (original_session_id);

            create index IF NOT EXISTS idx_sessions_info_chain_id_execution_status
                on sessions_info (chain_id, execution_status);

            create table IF NOT EXISTS checkpoints
            (
                id                    varchar(255) not null
                    constraint pk_checkpoints
                        primary key,
                session_id            varchar(255)
                    constraint fk_checkpoints_on_session
                        references sessions_info
                        on delete cascade,
                checkpoint_element_id varchar(255),
                headers               text,
                body                  oid,
                timestamp             timestamp,
                context_data          text
            );

            alter table checkpoints
                owner to postgres;

            create index IF NOT EXISTS sessionid_checkpoints
                on checkpoints (session_id);

            create index IF NOT EXISTS idx_checkpoints_session_id_checkpoint_element_id
                on checkpoints (session_id, checkpoint_element_id);

            create table IF NOT EXISTS properties
            (
                id            varchar(255) not null
                    constraint pk_properties
                        primary key,
                name          varchar(255),
                type          varchar(255),
                value         oid,
                checkpoint_id varchar(255)
                    constraint fk_checkpoints_on_chain
                        references checkpoints
                        on delete cascade
            );

            alter table properties
                owner to postgres;

        END IF;

    END
$$;