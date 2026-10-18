-- Further domains of a site. A request on one of them resolves to the site; redirect_to_primary asks the HTTP layer
-- to send the client to the site's own address.
create table qp_site_domain (
    id bigint not null auto_increment primary key,
    site_id bigint not null,
    domain varchar(200) not null,
    redirect_to_primary boolean not null default true,
    created_at datetime(6) not null default (utc_timestamp(6)),
    constraint qp_site_domain_site_fk foreign key (site_id) references qp_site (id) on delete cascade,
    constraint qp_site_domain_domain_uq unique (domain),
    index qp_site_domain_site_idx (site_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;
