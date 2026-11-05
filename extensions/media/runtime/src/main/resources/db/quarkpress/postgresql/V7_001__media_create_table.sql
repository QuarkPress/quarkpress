-- The file side of attachments, one row per attachment post, keyed by the post's id (post ids are global; the unique
-- (site_id, post_id) is the composite key the site-scoped reference needs). The post reference is composite so a row
-- can only belong to a post of its own site; it cascades, so deleting the post (or the site) takes the row with it.
-- storage_key is the main file's full storage key, with the sites/<id>/ folder of a site other than the main one; the
-- path relative to the site's uploads folder is derived from it, not stored a second time. original_key names the
-- untouched upload when the main file is a scaled, rotated or converted copy of it.
-- width and height are null whenever the main file's dimensions are unknown, whatever its type.
-- sizes, image_meta and extra are json, not jsonb: json keeps the text as written, so object keys keep their order
-- (sizes in generation order, each entry in WordPress's shape: file, width, height, mime-type, filesize). extra holds
-- every other attachment metadata key, in order; image_meta is null until the file's embedded data is read.
-- stored_bytes counts every file of the attachment (main file, original, sizes), so a site's used space is a sum.
create table qp_media (
    post_id bigint primary key,
    site_id bigint not null,
    storage_key varchar(1024) not null,
    original_key varchar(1024),
    mime_type varchar(100) not null,
    file_size bigint not null,
    width int,
    height int,
    sizes json not null default '{}',
    image_meta json,
    extra json not null default '{}',
    alt_text text not null,
    processing varchar(10) not null,
    stored_bytes bigint not null,
    version int not null default 0,
    constraint qp_media_site_fk foreign key (site_id) references qp_site (id) on delete cascade,
    constraint qp_media_post_fk foreign key (site_id, post_id) references qp_post (site_id, id) on delete cascade,
    constraint qp_media_site_post_uq unique (site_id, post_id),
    constraint qp_media_processing_check check (processing in ('none', 'pending', 'partial', 'done', 'failed'))
);

create index qp_media_mime_idx on qp_media (site_id, mime_type);
