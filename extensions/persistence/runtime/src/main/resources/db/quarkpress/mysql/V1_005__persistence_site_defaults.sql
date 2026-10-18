-- The default options of every site that exists when this runs (the main site), as new sites get them.
-- Options a site already has are kept.
insert into qp_option (site_id, name, value, autoload)
select s.id, 'blogname', json_quote(s.name), true
from qp_site s
where not exists (select 1 from qp_option o where o.site_id = s.id and o.name = 'blogname');

insert into qp_option (site_id, name, value, autoload)
select s.id, d.name, d.value, true
from qp_site s
cross join (
    select 'blogdescription' as name, '""' as value
    union all select 'timezone_string', '""'
    union all select 'gmt_offset', '"0"'
    union all select 'date_format', '"F j, Y"'
    union all select 'time_format', '"g:i a"'
    union all select 'start_of_week', '1'
    union all select 'posts_per_page', '10'
    union all select 'default_category', '0'
    union all select 'show_on_front', '"posts"'
    union all select 'page_on_front', '0'
    union all select 'page_for_posts', '0'
    union all select 'default_comment_status', '"open"'
    union all select 'default_ping_status', '"open"'
    union all select 'qp_features', '{}'
) d
where not exists (
    select 1 from qp_option o
    where o.site_id = s.id and o.name = convert(d.name using utf8mb4) collate utf8mb4_bin);
