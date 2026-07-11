alter table invoice
    add column invoice_number   varchar(64),
    add column currency         varchar(3),
    add column invoice_date     date,
    add column total_net        numeric(15,2),
    add column total_gross      numeric(15,2),
    add column validation_error varchar(1024),
    add column mongo_ref        varchar(256);

alter table supplier
    add constraint uq_supplier_name unique (name);

create unique index uq_invoice_supplier_number
    on invoice (supplier_id, invoice_number)
    where invoice_number is not null
      and status not in ('VALIDATION_FAILED', 'REJECTED');