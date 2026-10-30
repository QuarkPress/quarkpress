# WordPress REST parity harness

Serves WordPress 7.1.3 over HTTP so REST API requests can be compared with QuarkPress's: the scenario harness's
subdirectory network (testing/wp-scenario) is copied into its own database of the `mysql:9.5` container, given pretty
permalinks (`/%postname%/`), `WP_ENVIRONMENT_TYPE` `local` and the dev realm's five users (alice, bob, carol, dave,
erin, with the dev realm's roles, e-mails and display names, each with one application password), and served by
`php -S` on the loopback address through a router script that does what Apache's rewrite rules do and presents every
request as one to `localhost` on port 80. The PHP scripts (`php/rest-config.php`, `php/rest-router.php`,
`php/rest-setup.php`) call WordPress functions and mark every rule they apply themselves. Test scope only: never
published, never in the BOM.

```bash
mvn -pl testing/wp-rest-parity -am verify -Pwp-parity
```
