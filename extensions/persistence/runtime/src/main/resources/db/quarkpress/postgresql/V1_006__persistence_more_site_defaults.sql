-- More of WordPress's default options for every site that exists when this runs, as new sites get them, stored as the
-- texts WordPress stores. Options a site already has are kept. WPLANG is left out: WordPress's installer writes none
-- for an installation's first site (it reads the default language), while a network's later sites get it empty.
insert into qp_option (site_id, name, value, autoload)
select s.id, d.name, d.value, true
from qp_site s
cross join (values
    ('use_smilies', cast('"1"' as json)),
    ('default_post_format', cast('"0"' as json)),
    ('site_icon', cast('"0"' as json))
) as d (name, value)
where not exists (select 1 from qp_option o where o.site_id = s.id and o.name = d.name);
