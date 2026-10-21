-- Terms of every taxonomy and site, and their assignments to posts.
-- meta is longtext checked with json_valid, not json: the json type reorders object keys.
-- unique (site_id, id) is the composite key of the parent reference and of the assignments' term reference: a term's
-- parent and a post's terms are always of the term's own site. Deleting a term hands its parent down to its children
-- first, so the cascading parent key only acts when a site is deleted.
create table qp_term (
    id bigint not null auto_increment primary key,
    site_id bigint not null,
    taxonomy varchar(32) not null,
    name varchar(200) not null,
    slug varchar(200) not null,
    parent_id bigint,
    description longtext not null,
    count int not null default 0,
    meta longtext not null default ('{}'),
    version int not null default 0,
    constraint qp_term_site_fk foreign key (site_id) references qp_site (id) on delete cascade,
    constraint qp_term_site_id_uq unique (site_id, id),
    constraint qp_term_slug_uq unique (site_id, taxonomy, slug),
    index qp_term_parent_idx (site_id, taxonomy, parent_id),
    index qp_term_parent_key_idx (site_id, parent_id),
    constraint qp_term_parent_fk foreign key (site_id, parent_id) references qp_term (site_id, id) on delete cascade,
    index qp_term_name_idx (site_id, taxonomy, name),
    constraint qp_term_meta_json check (json_valid(meta))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;

create table qp_term_assignment (
    id bigint not null auto_increment primary key,
    site_id bigint not null,
    post_id bigint not null,
    term_id bigint not null,
    term_order int not null default 0,
    constraint qp_term_assignment_site_fk foreign key (site_id) references qp_site (id) on delete cascade,
    constraint qp_term_assignment_post_term_uq unique (post_id, term_id),
    index qp_term_assignment_post_idx (site_id, post_id),
    constraint qp_term_assignment_post_fk foreign key (site_id, post_id)
        references qp_post (site_id, id) on delete cascade,
    index qp_term_assignment_term_idx (site_id, term_id),
    constraint qp_term_assignment_term_fk foreign key (site_id, term_id)
        references qp_term (site_id, id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;
