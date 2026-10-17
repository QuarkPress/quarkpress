-- Option values are longtext checked with json_valid, not json: the json type reorders object keys.
create table qp_site (
    id bigint not null auto_increment primary key,
    domain varchar(200) not null,
    path varchar(100) not null,
    name varchar(255) not null,
    public_flag smallint not null default 1,
    archived boolean not null default false,
    mature boolean not null default false,
    spam boolean not null default false,
    deleted boolean not null default false,
    registered_at datetime(6) not null default (utc_timestamp(6)),
    updated_at datetime(6) not null default (utc_timestamp(6)),
    version int not null default 0,
    constraint qp_site_domain_path_uq unique (domain, path),
    index qp_site_path_idx (path)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;

create table qp_network_option (
    name varchar(191) not null primary key,
    value longtext not null,
    updated_at datetime(6) not null default (utc_timestamp(6)),
    constraint qp_network_option_value_json check (json_valid(value))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;

create table qp_option (
    id bigint not null auto_increment primary key,
    site_id bigint not null,
    name varchar(191) not null,
    value longtext not null,
    autoload boolean not null default true,
    updated_at datetime(6) not null default (utc_timestamp(6)),
    constraint qp_option_site_fk foreign key (site_id) references qp_site (id) on delete cascade,
    constraint qp_option_site_name_uq unique (site_id, name),
    index qp_option_site_autoload_idx (site_id, autoload),
    constraint qp_option_value_json check (json_valid(value))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;
