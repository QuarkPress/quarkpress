-- The e-mail address of a profile, from the identity provider's verified email claim; null until a login brings one.
alter table qw_user_profile add column email varchar(100);
