create table qp_site (
    id bigint not null auto_increment primary key,
    domain varchar(200) not null,
    path varchar(100) not null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;

insert into qp_site (domain, path) values ('one.test', '/'), ('two.test', '/'), ('three.test', '/');

create table spike_note (
    id bigint not null auto_increment primary key,
    site_id bigint not null,
    body varchar(200) not null,
    constraint spike_note_site_fk foreign key (site_id) references qp_site (id),
    index spike_note_site_idx (site_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;
