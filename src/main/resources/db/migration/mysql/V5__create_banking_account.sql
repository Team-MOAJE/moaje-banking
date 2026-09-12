create table banking_account (
    account_id bigint not null,
    principal_id varchar(100) not null,
    provider_account_id varchar(100) not null,
    status varchar(20) not null,
    bank_code varchar(20) not null,
    product_name varchar(100) not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    primary key (account_id),
    constraint uk_banking_account_provider_account_id unique (provider_account_id)
);

create index idx_banking_account_principal_id on banking_account (principal_id);
