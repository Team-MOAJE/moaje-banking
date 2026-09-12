alter table banking_transfer
    add column next_reconciliation_at datetime(6),
    add column reconciliation_retry_exhausted boolean not null default false,
    add column reconciliation_locked_at datetime(6),
    add column reconciliation_locked_until datetime(6),
    add column reconciliation_lock_owner varchar(120);

drop index idx_banking_transfer_reconciliation_scan on banking_transfer;

create index idx_banking_transfer_reconciliation_scan
    on banking_transfer (
        status,
        reconciliation_required,
        reconciliation_retry_exhausted,
        next_reconciliation_at,
        reconciliation_locked_until,
        processing_started_at
    );
