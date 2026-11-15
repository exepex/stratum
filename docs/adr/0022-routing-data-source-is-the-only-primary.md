# ADR 0022: The routing data source is Stratum's only primary bean

**Status:** Accepted, 2026-11-15, by the owner.

[← Documentation home](../README.md) · How to use it:
[Repositories on a pool](../modules/data-named-pools.md#repositories-on-a-pool)

## Context

- [ADR 0001](0001-coexistence-with-company-frameworks.md) decision 2 says that the company's bean always wins: every
  Stratum bean is `@ConditionalOnMissingBean` and `@Fallback`.
- The data JPA starter can map JPA repositories to named pools with `stratum.data.sources.<name>.packages` or
  `@UseDataSource`. Spring Boot's entity manager factory, `JdbcTemplate` and JPA transaction manager take the one
  primary `DataSource`, so the data source that picks each statement's pool must be that primary bean. A `@Fallback`
  bean would lose to Spring Boot's own `dataSource`, and the mapping would be dropped without a word.
- The routing data source wraps Spring Boot's own `dataSource`, the main pool, and each mapped pool. A service that
  defines its own `DataSource` makes Spring Boot create none, and a second primary `DataSource` makes injection
  ambiguous.

## Decision

This record narrows ADR 0001 decision 2 for one bean.

1. **The routing data source is primary, and exists only with a mapping.** When a mapping exists, the data JPA starter
   registers `stratumRoutingDataSource` as `@Primary`, with no `@Fallback` and no `@ConditionalOnMissingBean`.
   Without a mapping, Stratum registers nothing and every rule of ADR 0001 applies as before.
2. **Stratum stops startup rather than back off.** A mapping together with no Spring Boot `dataSource`, another primary
   `DataSource`, or a service bean named `stratumRoutingDataSource` stops startup with `SX-DATA-1007`. Its fix says:
   use Spring Boot's dataSource and no other primary DataSource, or drop the mapping.
3. **No other Stratum bean is primary.** Any later primary bean needs its own record.

## Consequences

- A service that maps repositories keeps Spring Boot's `dataSource`. A plain `DataSource` is then the routing data
  source; `@Qualifier("dataSource")` reaches the main pool, as `unwrap(HikariDataSource.class)` does.
- A company framework that defines its own primary `DataSource` cannot be combined with a mapping. The team drops the
  mapping and reaches the named pool by its qualifier, as without the data JPA starter.
- The rule is checked once, at startup, from bean definitions: it costs nothing per call.
