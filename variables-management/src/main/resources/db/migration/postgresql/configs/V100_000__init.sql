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

DO $$

  DECLARE
    executed BOOLEAN;
  BEGIN
    SELECT true INTO executed FROM flyway_schema_history where version = '7.000';

    IF coalesce(executed, false) is false THEN

            create table if not exists users
            (
                id      varchar(255) not null
                        constraint pk_users
                        primary key,
                username varchar(255)
            );

            create table if not exists logged_actions
            (
                id          varchar(255) not null
                    constraint pk_logged_actions
                        primary key,
                action_time timestamp,
                entity_type varchar(255),
                entity_id   varchar(255),
                entity_name varchar(255),
                parent_id   varchar(255),
                operation   varchar(255),
                user_id     varchar(255),
                username    varchar(255),
                parent_name varchar(255),
                parent_type varchar(255),
                request_id  varchar(255)
            );

            create index if not exists logged_actions_timestamp_idx
                on logged_actions (action_time);

            create table if not exists import_instructions
            (
                id            varchar(255) not null
                    primary key,
                action        varchar(255) not null,
                modified_when timestamp
            );

            create table if not exists import_instruction_labels
            (
                id             varchar(255) not null
                    primary key,
                instruction_id varchar(255) not null
                    references import_instructions,
                name           varchar(255) not null,
                constraint label_unique_per_instructions
                    unique (name, instruction_id)
            );
  END IF;

END$$;