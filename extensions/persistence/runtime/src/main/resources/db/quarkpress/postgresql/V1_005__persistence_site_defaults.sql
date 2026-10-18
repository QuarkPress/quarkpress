-- The default options of every site that exists when this runs (the main site), as new sites get them.
-- Options a site already has are kept.
insert into qp_option (site_id, name, value, autoload)
select s.id, 'blogname', to_json(s.name), true
from qp_site s
where not exists (select 1 from qp_option o where o.site_id = s.id and o.name = 'blogname');

insert into qp_option (site_id, name, value, autoload)
select s.id, d.name, d.value, true
from qp_site s
cross join (values
    ('blogdescription', cast('""' as json)),
    ('timezone_string', cast('""' as json)),
    ('gmt_offset', cast('"0"' as json)),
    ('date_format', cast('"F j, Y"' as json)),
    ('time_format', cast('"g:i a"' as json)),
    ('start_of_week', cast('1' as json)),
    ('posts_per_page', cast('10' as json)),
    ('default_category', cast('0' as json)),
    ('show_on_front', cast('"posts"' as json)),
    ('page_on_front', cast('0' as json)),
    ('page_for_posts', cast('0' as json)),
    ('default_comment_status', cast('"open"' as json)),
    ('default_ping_status', cast('"open"' as json)),
    ('qp_features', cast('{}' as json))
) as d (name, value)
where not exists (select 1 from qp_option o where o.site_id = s.id and o.name = d.name);
