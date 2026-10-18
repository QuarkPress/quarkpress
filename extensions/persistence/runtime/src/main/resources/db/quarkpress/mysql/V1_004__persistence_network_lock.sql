-- One row that every write of network data (sites, domain aliases, network options) locks first, so those writes run
-- one after the other. No foreign key refers to it, so inserts of a site's rows never hold locks on it.
create table qp_network_lock (
    id smallint primary key
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;

insert into qp_network_lock (id) values (1);
