alter table banking_transfer add column external_reversal_transaction_id varchar(100);
alter table banking_transfer add column reversal_reason varchar(255);
alter table banking_transfer add column reversed_at timestamp(6);

create index idx_banking_transfer_external_reversal_transaction_id
    on banking_transfer (external_reversal_transaction_id);
