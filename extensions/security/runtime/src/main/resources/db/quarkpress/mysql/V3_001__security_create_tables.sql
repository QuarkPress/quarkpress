-- User profiles (one per OIDC subject), network administrators and site memberships.
-- JSON values are longtext checked with json_valid, not json: the json type reorders object keys.
create table qp_user_profile (
    id bigint not null auto_increment primary key,
    subject varchar(255) not null,
    display_name varchar(250) not null,
    nicename varchar(50) not null,
    bio text,
    primary_site_id bigint,
    meta longtext not null default ('{}'),
    created_at datetime(6) not null default (utc_timestamp(6)),
    last_login_at datetime(6),
    constraint qp_user_profile_subject_uq unique (subject),
    constraint qp_user_profile_nicename_uq unique (nicename),
    constraint qp_user_profile_primary_site_fk foreign key (primary_site_id) references qp_site (id) on delete set null,
    constraint qp_user_profile_meta_json check (json_valid(meta))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;

create table qp_network_admin (
    user_id bigint not null primary key,
    granted_at datetime(6) not null default (utc_timestamp(6)),
    granted_by bigint,
    constraint qp_network_admin_user_fk foreign key (user_id) references qp_user_profile (id) on delete cascade,
    constraint qp_network_admin_granted_by_fk foreign key (granted_by) references qp_user_profile (id)
        on delete set null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;

-- unique (site_id, user_id) is the composite parent key for any site-scoped table that refers to a membership.
create table qp_site_membership (
    id bigint not null auto_increment primary key,
    site_id bigint not null,
    user_id bigint not null,
    roles longtext not null,
    capabilities longtext not null default ('{}'),
    created_at datetime(6) not null default (utc_timestamp(6)),
    constraint qp_site_membership_site_fk foreign key (site_id) references qp_site (id) on delete cascade,
    constraint qp_site_membership_user_fk foreign key (user_id) references qp_user_profile (id) on delete cascade,
    constraint qp_site_membership_site_user_uq unique (site_id, user_id),
    index qp_site_membership_user_idx (user_id),
    constraint qp_site_membership_roles_json check (json_valid(roles)),
    constraint qp_site_membership_capabilities_json check (json_valid(capabilities))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;
