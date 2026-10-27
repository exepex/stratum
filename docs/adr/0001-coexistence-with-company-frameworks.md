# ADR 0001: Stratum next to a company framework

**Status:** Accepted, 2026-10-27, by the owner.

[← Documentation home](../README.md) · How to apply it: [Using Stratum with a company framework](../guides/company-framework.md)

## Context

Many companies have their own framework on Spring Boot: a parent POM that extends
`spring-boot-starter-parent`, and starters with the company's own beans. Teams in such companies
want to use Stratum's starters as well. Two layouts were requested:

1. The service keeps the company parent and uses Stratum's starters through `stratum-bom`.
2. A team forks Stratum and makes the company parent the parent of `stratum-parent`, so the chain is
   Spring Boot → company parent → `stratum-parent` → service.

Both layouts must give one Spring Boot version and must let the company's beans take priority over
Stratum's.

Two facts constrain the design:

- In Maven, dependency versions inherited from a parent win over versions imported from a BOM,
  without a warning. Checked with Maven 3.9.11: a library managed at one version by a parent and at
  another by an imported BOM resolves to the parent's version.
- In Spring, a bean cannot be stopped by another bean's priority. Only a condition stops a bean from
  being created; `@Primary` and `@Fallback` only choose which of several existing beans is injected.

## Decision

1. **The parent sets the Spring Boot version.** `stratum-bom` manages only Stratum's modules and the
   libraries that Spring Boot does not manage, and does not import Spring Boot's BOM.
   `stratum-parent` gets Spring Boot's versions by inheriting `spring-boot-starter-parent`. Each
   release supports one Spring Boot line, and Stratum stops at startup when the running Spring Boot
   version is outside it.
2. **The company's bean always wins.** Every Stratum bean is `@ConditionalOnMissingBean` on the type
   services inject and is marked `@Fallback`, and every Stratum auto-configuration sorts after
   auto-configurations that declare no order. When whole setups overlap, such as two security filter
   chains, the service turns Stratum's module off with `stratum.<module>.enabled=false`.
3. **The fork is supported, but not recommended.** The documentation recommends layout 1. Layout 2
   is documented as supported, with its cost: the team merges every Stratum release into its fork.

## Consequences

- A service under a company parent gets no second Spring Boot version from Stratum, and learns of
  an unsupported version at the first start.
- With `stratum-parent`, a Spring Boot managed version is overridden through Spring Boot's own
  version property. Stratum itself no longer overrides versions that Spring Boot manages, because
  such an override would not reach services under a company parent.
- A Stratum auto-configuration that must run before one of Spring Boot's can still run before a
  company's unordered auto-configuration, because `before` and `after` take precedence over the
  order value. Its module page says so, and the company orders against it with `beforeName` or the
  service turns the module off.
- Stratum's tests must show each bean backing off for a bean from an unordered auto-configuration,
  not only for a bean from the service.
- Rules ST-ENG-21, ST-ENG-22, ST-ENG-60, ST-ENG-61, ST-ENG-65 and ST-TEST-20 carry the decision into
  the code.
