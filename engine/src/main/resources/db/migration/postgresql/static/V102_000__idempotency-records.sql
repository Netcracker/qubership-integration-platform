create table if not exists idempotency_records (
    key text constraint pk_idempotency_records primary key,
    data jsonb,
    created_at timestamptz not null,
    expires_at timestamptz not null
);
