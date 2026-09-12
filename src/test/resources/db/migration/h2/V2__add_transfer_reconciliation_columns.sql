alter table banking_transfer add column processing_started_at timestamp(6);
alter table banking_transfer add column reconciliation_attempt_count integer not null default 0;
alter table banking_transfer add column last_reconciliation_failure_reason varchar(500);
alter table banking_transfer add column last_reconciled_at timestamp(6);

create index idx_banking_transfer_reconciliation_scan
    on banking_transfer (status, reconciliation_required, processing_started_at);
