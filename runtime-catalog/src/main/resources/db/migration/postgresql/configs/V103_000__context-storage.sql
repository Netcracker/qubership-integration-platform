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

CREATE TABLE context_system
(
    id                      TEXT NOT NULL
        CONSTRAINT pk_context_system
            PRIMARY KEY,
    name                    TEXT,
    description             TEXT,
    created_when            TIMESTAMPTZ,
    modified_when           TIMESTAMPTZ,
    created_by_id           TEXT,
    created_by_name         TEXT,
    modified_by_id          TEXT,
    modified_by_name        TEXT
);
