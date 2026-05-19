create table mcp_systems
(
    id               varchar(255) not null
        constraint pk_mcp_system primary key,
    name             text,
    description      text,
    created_when     timestamptz,
    modified_when    timestamptz,
    created_by_id    text,
    created_by_name  text,
    modified_by_id   text,
    modified_by_name text,

    identifier       text unique,
    instructions     text
);

create table mcp_system_labels
(
    id            varchar(255) not null,
    name          text         not null,
    mcp_system_id varchar(255) not null
        references mcp_systems on delete cascade,
    technical     boolean default false
);

create index idx_mcp_system_labels_mcp_system_id
    ON mcp_system_labels (mcp_system_id);

create unique index uk_mcp_system_labels
    ON mcp_system_labels (name, mcp_system_id, technical);
