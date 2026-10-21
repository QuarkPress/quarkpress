-- Every site that exists when this runs gets WordPress's default category, Uncategorized, unless its default_category
-- option already names something (persistence writes 0 for every site); the option then names the new term.
-- Like WordPress's installer, this writes the term without announcing it.
insert into qp_term (site_id, taxonomy, name, slug, description)
select s.id, 'category', 'Uncategorized', 'uncategorized', ''
from qp_site s
where not exists (
    select 1 from qp_option o
    where o.site_id = s.id and o.name = 'default_category' and cast(o.value as text) <> '0');

update qp_option o
set value = to_json(t.id), updated_at = now()
from qp_term t
where o.name = 'default_category'
  and cast(o.value as text) = '0'
  and t.site_id = o.site_id
  and t.taxonomy = 'category'
  and t.slug = 'uncategorized';

insert into qp_option (site_id, name, value, autoload)
select t.site_id, 'default_category', to_json(t.id), true
from qp_term t
where t.taxonomy = 'category'
  and t.slug = 'uncategorized'
  and not exists (select 1 from qp_option o where o.site_id = t.site_id and o.name = 'default_category');
