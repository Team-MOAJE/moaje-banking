alter table banking_transfer add column next_reconciliation_at timestamp(6);
alter table banking_transfer add column reconciliation_retry_exhausted boolean not null default false;
alter table banking_transfer add column reconciliation_locked_at timestamp(6);
alter table banking_transfer add column reconciliation_locked_until timestamp(6);
alter table banking_transfer add column reconciliation_lock_owner varchar(120);

drop index idx_banking_transfer_reconciliation_scan;

create index idx_banking_transfer_reconciliation_scan
    on banking_transfer (
        status,
        reconciliation_required,
        reconciliation_retry_exhausted,
        next_reconciliation_at,
        reconciliation_locked_until,
        processing_started_at
    );
