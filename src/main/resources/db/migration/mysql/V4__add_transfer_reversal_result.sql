alter table banking_transfer
    add column external_reversal_transaction_id varchar(100) null after external_transaction_id,
    add column reversal_reason varchar(255) null after failure_reason,
    add column reversed_at datetime(6) null after reversal_reason;

create index idx_banking_transfer_external_reversal_transaction_id
    on banking_transfer (external_reversal_transaction_id);
