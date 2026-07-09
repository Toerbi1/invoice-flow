create table supplier (
    id         uuid primary key default gen_random_uuid(),
    name       varchar(255) not null,
    created_at timestamptz  not null default now()
);

create table invoice (
    id                uuid          primary key default gen_random_uuid(),
    status            varchar(32)   not null,
    original_filename varchar(512)  not null,
    document_key      varchar(1024) not null unique,
    content_type      varchar(128),
    size_bytes        bigint,
    supplier_id       uuid          references supplier(id),
    received_at       timestamptz   not null default now(),
    updated_at        timestamptz,
    version           bigint        not null default 0
);

create index idx_invoice_status on invoice(status);