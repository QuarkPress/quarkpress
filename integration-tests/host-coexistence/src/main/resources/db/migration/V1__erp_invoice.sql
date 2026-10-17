create table erp_invoice (
    id bigint not null primary key,
    number varchar(40) not null,
    details json null
);
