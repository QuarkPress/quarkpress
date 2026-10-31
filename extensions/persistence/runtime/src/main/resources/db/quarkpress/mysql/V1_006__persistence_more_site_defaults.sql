-- More of WordPress's default options for every site that exists when this runs, as new sites get them, stored as the
-- texts WordPress stores. Options a site already has are kept. WPLANG is left out: WordPress's installer writes none
-- for an installation's first site (it reads the default language), while a network's later sites get it empty.
insert into qp_option (site_id, name, value, autoload)
select s.id, d.name, d.value, true
from qp_site s
cross join (
    select 'use_smilies' as name, '"1"' as value
    union all select 'default_post_format', '"0"'
    union all select 'site_icon', '"0"'
) d
where not exists (
    select 1 from qp_option o
    where o.site_id = s.id and o.name = convert(d.name using utf8mb4) collate utf8mb4_bin);
