-- Preview sessions and one-time lock release tokens of a site. Only the SHA-256 of a token is stored; the token
-- itself is never recoverable. Preview tokens are computed from a per-site secret and are not stored here.
-- post_id is null for a session; a release token names its post through the composite key, so it follows the post.
create table qp_post_token (
    id bigint not null auto_increment primary key,
    site_id bigint not null,
    kind varchar(20) not null,
    post_id bigint,
    user_id bigint not null,
    token_hash char(64) not null,
    lock_value varchar(40),
    expires_at datetime(6) not null,
    created_at datetime(6) not null,
    constraint qp_post_token_site_fk foreign key (site_id) references qp_site (id) on delete cascade,
    index qp_post_token_post_idx (site_id, post_id),
    constraint qp_post_token_post_fk foreign key (site_id, post_id) references qp_post (site_id, id) on delete cascade,
    constraint qp_post_token_hash_uq unique (site_id, token_hash),
    index qp_post_token_lookup_idx (site_id, kind, post_id, user_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;
