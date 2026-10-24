-- Revisions and autosaves of posts, in a table of their own (WordPress keeps them as rows of its posts table).
-- meta is longtext checked with json_valid, not json: the json type reorders object keys.
-- The post reference is composite so a revision can only belong to a post of its own site; it cascades, so deleting
-- a post (or a site) takes its revisions with it. The author and the autosave owner each have a reference that is
-- set to null when the profile is deleted.
-- autosave_owner is the author of an autosave and null for a regular revision or an autosave without author. The
-- module writes it (it is not a generated column: MySQL refuses a set-null key on the base column of one). The unique
-- key keeps one autosave per user and post and, as nulls are distinct in a unique key, limits nothing else.
create table qp_revision (
    id bigint not null auto_increment primary key,
    site_id bigint not null,
    post_id bigint not null,
    author_id bigint,
    title text not null,
    content longtext not null,
    excerpt text not null,
    meta longtext not null default ('{}'),
    autosave boolean not null,
    autosave_owner bigint,
    created_at datetime(6) not null,
    constraint qp_revision_site_fk foreign key (site_id) references qp_site (id) on delete cascade,
    index qp_revision_post_key_idx (site_id, post_id),
    constraint qp_revision_post_fk foreign key (site_id, post_id) references qp_post (site_id, id) on delete cascade,
    constraint qp_revision_author_fk foreign key (author_id) references qp_user_profile (id) on delete set null,
    constraint qp_revision_owner_fk foreign key (autosave_owner) references qp_user_profile (id) on delete set null,
    index qp_revision_post_idx (post_id, created_at, id),
    unique key qp_revision_autosave_uq (post_id, autosave_owner),
    constraint qp_revision_meta_json check (json_valid(meta))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;
