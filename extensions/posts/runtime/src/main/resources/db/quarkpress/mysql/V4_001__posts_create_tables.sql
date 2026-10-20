-- Posts of every type, and the per-site lock that serializes post writes.
-- meta is longtext checked with json_valid, not json: the json type reorders object keys.
-- unique (site_id, id) is the composite parent key of the parent reference and of every site-scoped table that refers
-- to a post; the parent reference is composite so a post can only have a parent of its own site.
create table qp_post (
    id bigint not null auto_increment primary key,
    site_id bigint not null,
    type varchar(20) not null,
    status varchar(20) not null,
    slug varchar(200) not null default '',
    title text not null,
    content longtext not null,
    excerpt text not null,
    author_id bigint,
    parent_id bigint,
    menu_order int not null default 0,
    published_at datetime(6),
    created_at datetime(6) not null,
    modified_at datetime(6) not null,
    comment_status varchar(20) not null,
    ping_status varchar(20) not null,
    password varchar(255) not null default '',
    sticky boolean not null default false,
    guid varchar(255) not null,
    meta longtext not null default ('{}'),
    version int not null default 0,
    constraint qp_post_site_fk foreign key (site_id) references qp_site (id) on delete cascade,
    constraint qp_post_site_id_uq unique (site_id, id),
    constraint qp_post_author_fk foreign key (author_id) references qp_user_profile (id) on delete set null,
    index qp_post_parent_idx (site_id, parent_id),
    constraint qp_post_parent_fk foreign key (site_id, parent_id) references qp_post (site_id, id) on delete cascade,
    index qp_post_type_status_idx (site_id, type, status, published_at, id),
    index qp_post_slug_idx (site_id, slug),
    index qp_post_author_idx (site_id, author_id),
    index qp_post_due_idx (status, published_at),
    constraint qp_post_meta_json check (json_valid(meta))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;

create table qp_post_lock (
    site_id bigint not null primary key,
    constraint qp_post_lock_site_fk foreign key (site_id) references qp_site (id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;
