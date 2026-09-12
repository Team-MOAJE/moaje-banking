create table bank_routing_status (
    bank_code varchar(10) not null,
    bank_name varchar(50) not null,
    is_available boolean not null,
    maint_start_time datetime(6),
    maint_end_time datetime(6),
    updated_at datetime(6) not null,
    primary key (bank_code)
);

create index idx_bank_routing_status_available on bank_routing_status (is_available);
create index idx_bank_routing_status_updated_at on bank_routing_status (updated_at);

create table banking_transfer (
    transfer_id bigint not null,
    principal_id varchar(100) not null,
    operation_type varchar(40) not null,
    idempotency_key varchar(120) not null,
    request_hash varchar(64) not null,
    withdrawal_account_id bigint not null,
    deposit_bank_code varchar(20) not null,
    deposit_account_number varchar(100) not null,
    amount decimal(18, 4) not null,
    currency varchar(3) not null,
    external_transaction_id varchar(100),
    status varchar(30) not null,
    failure_code varchar(50),
    failure_reason varchar(255),
    reconciliation_required boolean not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    version bigint not null,
    primary key (transfer_id),
    constraint uk_banking_transfer_principal_operation_idempotency
        unique (principal_id, operation_type, idempotency_key)
);

create index idx_banking_transfer_status on banking_transfer (status);
create index idx_banking_transfer_external_transaction_id on banking_transfer (external_transaction_id);
create index idx_banking_transfer_withdrawal_account_id on banking_transfer (withdrawal_account_id);
create index idx_banking_transfer_reconciliation_required on banking_transfer (reconciliation_required);

create table banking_outbox (
    event_id varchar(64) not null,
    aggregate_type varchar(80) not null,
    aggregate_id varchar(80) not null,
    event_type varchar(120) not null,
    topic varchar(200) not null,
    message_key varchar(120) not null,
    payload blob not null,
    status varchar(30) not null,
    attempt_count integer not null,
    last_failure_reason varchar(500),
    next_attempt_at timestamp(6) not null,
    published_at timestamp(6),
    locked_at timestamp(6),
    locked_until timestamp(6),
    lock_owner varchar(120),
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    primary key (event_id)
);

create index idx_banking_outbox_publishable on banking_outbox (status, next_attempt_at, locked_until);
create index idx_banking_outbox_aggregate on banking_outbox (aggregate_type, aggregate_id);

create table kftc_api_log (
    log_id bigint not null,
    transfer_id bigint not null,
    external_ref_no varchar(100),
    request_payload mediumtext not null,
    response_code varchar(10),
    status varchar(20) not null,
    created_at datetime(6) not null,
    primary key (log_id),
    constraint uk_kftc_api_log_external_ref_no unique (external_ref_no)
);

create index idx_kftc_api_log_transfer_id on kftc_api_log (transfer_id);
create index idx_kftc_api_log_created_at on kftc_api_log (created_at);

create table reconciliation_history (
    rec_id bigint not null,
    target_date date not null,
    unmatched_count integer not null,
    resolved_count integer not null,
    status varchar(20) not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    primary key (rec_id)
);

create index idx_reconciliation_history_target_date on reconciliation_history (target_date);
create index idx_reconciliation_history_status on reconciliation_history (status);
