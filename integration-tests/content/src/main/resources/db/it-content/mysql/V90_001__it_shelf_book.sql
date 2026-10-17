create table it_shelf (
    id bigint not null auto_increment primary key,
    site_id bigint not null,
    label varchar(100) not null,
    constraint it_shelf_site_fk foreign key (site_id) references qp_site (id),
    constraint it_shelf_site_id_uk unique (site_id, id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;

-- A site-scoped row refers to a site-scoped row of its own site only: the foreign key includes site_id.
create table it_book (
    id bigint not null auto_increment primary key,
    site_id bigint not null,
    shelf_id bigint not null,
    title varchar(100) not null,
    constraint it_book_site_fk foreign key (site_id) references qp_site (id),
    constraint it_book_shelf_fk foreign key (site_id, shelf_id) references it_shelf (site_id, id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;
