-- The front-end options of every site that exists when this runs, as new sites get them. Options a site already has
-- are kept. home and siteurl are the site's address with the http scheme and no trailing slash. The permalink
-- structure is WordPress's default for a network's sites; the main site (the first row, which V1_002 inserted) of a
-- sub-directory network (another site shares its domain with a path other than /) gets the /blog/ structure, as
-- WordPress sets it at network creation.
insert into qw_option (site_id, name, value, autoload)
select s.id, 'permalink_structure',
    case
        when s.id = (select min(id) from qw_site)
            and exists (select 1 from qw_site o where o.domain = s.domain and o.path <> '/' and o.id <> s.id)
        then cast('"/blog/%year%/%monthnum%/%day%/%postname%/"' as json)
        else cast('"/%year%/%monthnum%/%day%/%postname%/"' as json)
    end,
    true
from qw_site s
where not exists (select 1 from qw_option o where o.site_id = s.id and o.name = 'permalink_structure');

insert into qw_option (site_id, name, value, autoload)
select s.id, d.name, to_json('http://' || s.domain || rtrim(s.path, '/')), true
from qw_site s
cross join (values ('home'), ('siteurl')) as d (name)
where not exists (select 1 from qw_option o where o.site_id = s.id and o.name = d.name);

insert into qw_option (site_id, name, value, autoload)
select s.id, d.name, d.value, true
from qw_site s
cross join (values
    ('category_base', cast('""' as json)),
    ('tag_base', cast('""' as json)),
    ('wp_attachment_pages_enabled', cast('"0"' as json)),
    ('wp_page_for_privacy_policy', cast('0' as json))
) as d (name, value)
where not exists (select 1 from qw_option o where o.site_id = s.id and o.name = d.name);
