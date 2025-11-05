# Legacy Documentation

This file contains historical documentation preserved from the original Ant/Tomcat
build system. It is provided for reference only — the main README points to
containerised and modern build flows.

## Legacy build notes

- Legacy builds used Ant and were tested with Ant 1.9.2.
- Ruby Sass (older) was used for SCSS compilation historically.
- Tomcat 7.0.64 was the reference runtime in older documentation.

## Legacy dependencies and references

- Ant: [Ant manual](http://ant.apache.org/manual/install.html)
- SASS: [Sass website](https://sass-lang.com).

  (Legacy Ruby Sass is referenced in older docs.)
- PostgreSQL: [PostgreSQL downloads](https://www.postgresql.org/download/)

## Legacy configuration

Legacy configuration was handled by Ant properties files (`build/default.properties`,
`build/overrides.properties`) and `example.properties` for per-installation
settings. The old configuration model is obsolete and kept only for archival
purposes.

For modern deployments, use the layered configuration described in `README.md`.

(If you need specific legacy snippets restored, open an issue and indicate
which part of the historical docs you need.)
