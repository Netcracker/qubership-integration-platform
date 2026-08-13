-- Every object is created idempotently: a downstream installation already owns
-- this schema from its own earlier migrations, and this one has to apply on top.
-- Nothing is schema-qualified and the schema itself is not created here -- the
-- schema name is provisioned by the host and is not always testing_service.

do $$ begin
    create type run_status as enum (
        'pending',
        'running',
        'canceled',
        'finished',
        'skipped'
    );
exception when duplicate_object then null;
end $$;

do $$ begin
    create type entity_type as enum (
        'body',
        'header',
        'status',
        'path_parameter',
        'query_parameter'
    );
exception when duplicate_object then null;
end $$;

do $$ begin
    create type http_method as enum (
        'GET',
        'POST',
        'PUT',
        'PATCH',
        'DELETE',
        'HEAD'
    );
exception when duplicate_object then null;
end $$;

do $$ begin
    create type matcher_type as enum (
        'empty',
        'exist',
        'equal',
        'contain',
        'match',
        'start_with',
        'end_with',
        'match_json_schema',
        'match_json'
    );
exception when duplicate_object then null;
end $$;

create table if not exists messages (
    id         uuid not null default gen_random_uuid(),
    body       text,
    owner_id   uuid unique not null,
    constraint pk_messages primary key (id)
);

create index if not exists idx_messages_owner_id on messages (owner_id);

create table if not exists headers (
    id           uuid not null default gen_random_uuid(),
    message_id   uuid not null,
    name         text,
    value        text,
    constraint pk_headers primary key (id),
    constraint fk_headers_on_message
        foreign key (message_id) references messages (id) on delete cascade
);

create table if not exists test_cases (
    id            uuid not null default gen_random_uuid(),
    name          text,
    description   text,
    enabled       boolean default true,

    created_by    text,
    created_at    timestamptz,

    updated_by    text,
    updated_at    timestamptz,

    constraint pk_test_cases primary key (id)
);

create table if not exists endpoint_mocks (
    id            uuid not null default gen_random_uuid(),
    name          text,
    description   text,
    enabled       boolean default true,

    created_by    text,
    created_at    timestamptz,

    updated_by    text,
    updated_at    timestamptz,

    constraint pk_endpoint_mocks primary key (id)
);

create table if not exists request_settings (
    id             uuid not null default gen_random_uuid(),
    method         http_method,
    timeout        integer,
    test_case_id   uuid unique not null,
    constraint pk_request_settings primary key (id),
    constraint fk_request_settings_on_test_cases
        foreign key (test_case_id) references test_cases (id) on delete cascade
);

create table if not exists response_settings (
    id                 uuid not null default gen_random_uuid(),
    status             integer,
    delay              integer,
    endpoint_mock_id   uuid unique not null,
    constraint pk_response_settings primary key (id),
    constraint fk_response_settings_on_endpoint_mocks
        foreign key (endpoint_mock_id) references endpoint_mocks (id) on delete cascade
);

create table if not exists query_parameters (
    id                    uuid not null default gen_random_uuid(),
    request_settings_id   uuid,
    name                  text,
    value                 text,
    constraint pk_query_parameters primary key (id),
    constraint fk_query_parameters_on_request_settings
        foreign key (request_settings_id) references request_settings (id) on delete cascade
);

create table if not exists path_parameters (
    id                    uuid not null default gen_random_uuid(),
    request_settings_id   uuid,
    name                  text,
    value                 text,
    constraint pk_path_parameters primary key (id),
    constraint fk_path_parameters_on_request_settings
        foreign key (request_settings_id) references request_settings (id) on delete cascade
);

create table if not exists trigger_references (
    id             uuid not null default gen_random_uuid(),
    chain_id       text,
    element_id     text,
    test_case_id   uuid unique not null,
    constraint pk_trigger_references primary key (id),
    constraint fk_trigger_references_on_test_cases
        foreign key (test_case_id) references test_cases (id) on delete cascade
);

create index if not exists idx_trigger_references_chain_id on trigger_references (chain_id);
create index if not exists idx_trigger_references_element_id on trigger_references (element_id);

create table if not exists endpoint_references (
    id                 uuid not null default gen_random_uuid(),
    chain_id           text,
    element_id         text,
    endpoint_mock_id   uuid unique not null,
    constraint pk_endpoint_references primary key (id),
    constraint fk_endpoint_references_on_endpoint_mocks
        foreign key (endpoint_mock_id) references endpoint_mocks (id) on delete cascade
);

create index if not exists idx_endpoint_references_chain_id on endpoint_references (chain_id);
create index if not exists idx_endpoint_references_element_id on endpoint_references (element_id);

create table if not exists matchers (
    id            uuid not null default gen_random_uuid(),
    owner_id      uuid,
    name          text,
    description   text,
    enabled       boolean default true,
    type          matcher_type,
    entity_type   entity_type,
    entity_name   text,
    constraint pk_matchers primary key (id)
);

create index if not exists idx_matchers_owner_id on matchers (owner_id);

create table if not exists matcher_parameters (
    id           uuid not null default gen_random_uuid(),
    matcher_id   uuid,
    name         text,
    value        text,
    constraint pk_matcher_parameters primary key (id),
    constraint fk_matcher_parameters_on_matcher
        foreign key (matcher_id) references matchers (id) on delete cascade
);

create table if not exists tests_runs (
    id           uuid not null default gen_random_uuid(),

    created_by   text,
    created_at   timestamptz,

    updated_by   text,
    updated_at   timestamptz,

    constraint pk_tests_runs primary key (id)
);

create table if not exists test_case_runs (
    id             uuid not null default gen_random_uuid(),
    tests_run_id   uuid,
    test_case_id   uuid,
    start          timestamptz,
    finish         timestamptz,
    status         run_status default 'pending',
    session_id     text,
    constraint pk_test_case_runs primary key (id),
    constraint fk_test_case_runs_on_test_runs
        foreign key (tests_run_id) references tests_runs (id) on delete cascade
);

create index if not exists idx_test_case_runs_test_case_id on test_case_runs (test_case_id);

create table if not exists validation_errors (
    id                 uuid not null default gen_random_uuid(),
    test_case_run_id   uuid,
    matcher_id         uuid,
    message            text,
    constraint pk_validation_errors primary key (id),
    constraint fk_validation_errors_on_test_case_runs
        foreign key (test_case_run_id) references test_case_runs (id) on delete cascade,
    unique (test_case_run_id, matcher_id)
);

create index if not exists idx_validation_errors_matcher_id on validation_errors (matcher_id);

create or replace view tests_runs_view as
    select
        tests_run.*,
        min(test_case_run.start) as start,
        max(test_case_run.finish) as finish,
        min(case
            when test_case_run.status = 'pending' then 'running'
            when test_case_run.status = 'skipped' then 'finished'
            else test_case_run.status
            end) as status,
        count(distinct error.id) as errors,
        count(test_case_run.test_case_id) as test_cases
    from
        tests_runs tests_run
    left join test_case_runs test_case_run on test_case_run.tests_run_id = tests_run.id
    left join lateral (
        select
            id
        from
            validation_errors validation_error
        where
            test_case_run.id = validation_error.test_case_run_id
        limit 1
    ) error on true -- count failed test case runs, not validation errors
    group by tests_run.id;

create or replace view test_case_runs_view as
    select
        test_case_run.*,
        test_case.name as test_case_name,
        test_case.description as test_case_description,
        trigger_reference.chain_id as chain_id,
        count(validation_error.id) as errors
    from
        test_case_runs test_case_run
    left join test_cases test_case on test_case_run.test_case_id = test_case.id
    left join trigger_references trigger_reference on trigger_reference.test_case_id = test_case_run.test_case_id
    left join validation_errors validation_error on test_case_run.id = validation_error.test_case_run_id
    group by test_case_run.id, test_case.name, test_case.description, trigger_reference.chain_id;

create or replace view test_cases_view as
    select
        test_case.*,
        trigger_reference.chain_id as chain_id,
        trigger_reference.element_id as element_id,
        count(matcher.id) as validation_rule_count,
        count(enabled_matchers.id) as enabled_rule_count
    from
        test_cases test_case
    left join trigger_references trigger_reference on test_case.id = trigger_reference.test_case_id
    left join matchers matcher on matcher.owner_id = test_case.id
    left join matchers enabled_matchers on enabled_matchers.owner_id = test_case.id and enabled_matchers.enabled
    group by test_case.id, trigger_reference.chain_id, trigger_reference.element_id;

-- The delete targets are unqualified on purpose: the trigger resolves them
-- through search_path, so the function works under any schema name.
create or replace function remove_matchers_on_owner_delete() returns trigger as $$
    begin
        delete from matchers as matcher where matcher.owner_id in (select id from old_table);
        return null;
    end;
$$ language plpgsql;

create or replace function remove_message_on_owner_delete() returns trigger as $$
    begin
        delete from messages as message where message.owner_id in (select id from old_table);
        return null;
    end;
$$ language plpgsql;

drop trigger if exists remove_matchers_on_delete_endpoint_mock on endpoint_mocks;
create trigger remove_matchers_on_delete_endpoint_mock after delete on endpoint_mocks
    referencing old table as old_table
    for each statement execute procedure remove_matchers_on_owner_delete();

drop trigger if exists remove_matchers_on_delete_test_case on test_cases;
create trigger remove_matchers_on_delete_test_case after delete on test_cases
    referencing old table as old_table
    for each statement execute procedure remove_matchers_on_owner_delete();

drop trigger if exists remove_message_on_delete_response_settings on response_settings;
create trigger remove_message_on_delete_response_settings after delete on response_settings
    referencing old table as old_table
    for each statement execute procedure remove_message_on_owner_delete();

drop trigger if exists remove_message_on_delete_request_settings on request_settings;
create trigger remove_message_on_delete_request_settings after delete on request_settings
    referencing old table as old_table
    for each statement execute procedure remove_message_on_owner_delete();
