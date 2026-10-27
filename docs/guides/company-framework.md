# Using Stratum with a company framework

**Status: Planned.** How to use Stratum when your company already has its own framework built on
Spring Boot, and how to make the two work together without conflicts.

[← Documentation home](../README.md) · Decision record: [ADR 0001](../adr/0001-coexistence-with-company-frameworks.md)

## Who this page is for

Many companies have their own framework on top of Spring Boot: a parent POM that every service
inherits, and starters with the company's own beans for logging, security or clients. This page is
for a team in such a company that wants to use Stratum as well, either for all of its concerns or
only for some starters that the company framework does not provide.

In this page, **company parent** means the company's parent POM, which itself extends
`spring-boot-starter-parent`, and **company framework** means the company's parent together with
its starters.

## The three setups

| | Setup A: Stratum as parent | Setup B: company parent, Stratum BOM | Setup C: team fork of Stratum |
| --- | --- | --- | --- |
| Parent chain | Spring Boot → `stratum-parent` → service | Spring Boot → company parent → service | Spring Boot → company parent → forked `stratum-parent` → service |
| Use when | There is no company framework | The service must keep the company parent | The team wants one parent with both, and accepts maintaining a fork |
| Who sets the Spring Boot version | Stratum | The company parent | The company parent |
| Stratum upgrades | Change one version | Change one version | Merge each Stratum release into the fork |
| Recommended | Yes, without a company framework | **Yes, with a company framework** | Supported, at the team's own cost |

Setup A is described in [Installation](../getting-started/installation.md). The rest of this page
covers setups B and C, which share the same rules for versions and beans.

## Rule 1: one Spring Boot version, set by the parent

A service must run exactly one Spring Boot version, and both frameworks must support it. Stratum
does not compete with the company parent for that decision:

- `stratum-bom` lists only Stratum's own modules and the libraries that Spring Boot does not
  manage, such as Resilience4j or the cloud SDKs. It does not import Spring Boot's BOM.
- The Spring Boot version comes from the parent: `spring-boot-starter-parent` in setup A, and the
  company parent in setups B and C.
- Each Stratum release supports one Spring Boot line, such as `4.1.x`. The
  [compatibility table](upgrading.md#compatibility) lists it.
- At startup, Stratum checks the Spring Boot version that is actually running. If it is outside the
  supported line, the service stops with a message that names both versions.

So the company decides when to move to a new Spring Boot version, and Stratum tells you at the first
start if its release does not support that version yet.

> [!NOTE]
> When a library is managed by both the company parent and `stratum-bom`, Maven uses the parent's
> version and ignores the BOM's without a warning. This is Maven's normal behaviour: dependency
> versions inherited from a parent win over versions imported from a BOM. Compare
> `mvn dependency:tree` with and without the BOM import to see which versions changed, and check
> each library both frameworks manage against the compatibility table in the release notes.

## Rule 2: the company's bean wins

When both frameworks provide a bean for the same job, the company's bean is used and Stratum's
steps back. Stratum does this in three ways:

1. **Every Stratum bean is conditional.** It is created only when no bean of the same type exists
   yet (`@ConditionalOnMissingBean`). The condition checks the type your code injects, such as
   Resilience4j's `CircuitBreakerRegistry`, so a company bean of that type is enough. Where Spring
   Boot already creates the bean, such as `RestClient.Builder`, Stratum does not create its own: it
   adds its settings to Boot's bean through a customizer. A company bean of that type then replaces
   Boot's bean, and Stratum's settings are applied to it only if the company builds it from Boot's
   customizers.
2. **Stratum's auto-configurations run late.** Spring Boot creates auto-configured beans in a
   sorted order. Stratum's auto-configurations sort after every auto-configuration that does not
   declare its own order, so the company's beans exist by the time Stratum checks for them.
3. **Every Stratum bean is a fallback.** If a company bean of the same type is created anyway, for
   example by a company auto-configuration that runs late and creates its bean without a
   condition, Spring injects the company's bean and not Stratum's (`@Fallback`). Code that injects
   a list of all beans of that type still receives both. `@Fallback` only chooses between beans
   that exist: it cannot bring back a company bean that backed off because Stratum's was there
   first.

What this means in practice:

| Where the company bean comes from | Result |
| --- | --- |
| A `@Configuration` class in the service, or a company class found by component scanning | The company's bean wins: Spring processes these before any auto-configuration |
| A company auto-configuration without an order | The company's bean wins: it runs before Stratum's |
| A company auto-configuration that also runs late and creates its bean without a condition | Both beans exist; the company's is injected because Stratum's is a fallback |
| A company auto-configuration that also runs late and creates its bean only when none exists (`@ConditionalOnMissingBean`) | Stratum's bean wins, because the company's backs off. The company orders its auto-configuration before Stratum's with `beforeName`, or the service turns the Stratum module off |
| A company bean of a different type that does the same job | Stratum cannot detect it; turn Stratum's part off (Rule 3) |

One exception to the late order: a Stratum auto-configuration that must run before one of Spring
Boot's own, to replace a bean Boot would otherwise create, is moved before it. The security starter
is the expected case, because its `SecurityFilterChain` replaces Spring Boot's default one. Such a
Stratum auto-configuration can also run before an unordered company auto-configuration, and its
module page says so. In that case the company orders its auto-configuration before Stratum's with
`beforeName`, as shown below, or the service turns the Stratum module off.

A company auto-configuration that sets its own late order can still run before Stratum's by naming
it, for example
`@AutoConfiguration(beforeName = "com.exepex.stratum.web.autoconfigure.StratumCorrelationAutoConfiguration")`.
Using `beforeName` avoids a compile-time dependency on Stratum. Stratum's auto-configuration class
names may change between minor versions, and the release notes list every rename.

## Rule 3: when whole setups overlap, turn Stratum's part off

Some concerns are not one bean but a whole setup, such as a security filter chain, a log format or a
request filter. Running two of them gives wrong results: two filter chains, logs in two formats or
two correlation IDs per request. Choose one framework for each such concern, and turn Stratum's off
with its switch:

| Overlap | Symptom when both run | Turn Stratum's off |
| --- | --- | --- |
| Security filter chain | Requests pass one chain and fail the other | `stratum.security.enabled=false` |
| Log format and redaction | Log lines in two formats, or the company's format without Stratum's redaction | `stratum.logging.enabled=false` |
| Correlation ID filter | Two IDs per request, under two header names | `stratum.web.correlation.enabled=false` |
| Metrics and tracing setup | Duplicate metric tags, or traces that do not connect | `stratum.observability.enabled=false` |
| HTTP client defaults | Two sets of timeouts or interceptors on the same client | `stratum.http-client.enabled=false` |

For example, a service that keeps the company's security and logging but uses Stratum for
everything else:

```yaml
# src/main/resources/application.yml
stratum:
  security:
    enabled: false
  logging:
    enabled: false
```

```properties
# src/main/resources/application.properties
stratum.security.enabled=false
stratum.logging.enabled=false
```

Each module page lists the beans the module creates under **What you get**, so you can compare them
with the company framework's beans. More ways to switch parts off are in
[Overriding defaults](../configuration/overriding-defaults.md).

## Setup B: keep the company parent and import the BOM

This is the recommended setup when a company framework exists. The service keeps its parent and
uses Stratum's starters through the BOM.

**Step 1.** Import `stratum-bom` in the service's `pom.xml`:

```xml
<project>
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.acme.platform</groupId>      <!-- your company parent -->
    <artifactId>acme-parent</artifactId>
    <version>7.3.0</version>
    <relativePath/>
  </parent>

  <groupId>com.acme.orders</groupId>
  <artifactId>order-service</artifactId>
  <version>1.0.0-SNAPSHOT</version>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>com.exepex.stratum</groupId>
        <artifactId>stratum-bom</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
```

If the company parent does not extend `spring-boot-starter-parent` and does not manage Spring Boot's
versions, also import `spring-boot-dependencies` at the version in the
[compatibility table](upgrading.md#compatibility). The two BOMs do not overlap, so their order does
not matter.

**Step 2.** Add only the Stratum starters for concerns the company framework does not cover, or
covers less well. The [starter table](../getting-started/installation.md#step-2-add-the-starters-you-need)
lists them. Do not write a `<version>` for them.

**Step 3.** For each concern both frameworks cover, decide which one to keep, and turn Stratum's off
as shown in Rule 3.

**Step 4.** Check the result:

- `mvn dependency:tree` shows one version of each Spring Boot artifact.
- The service starts. If the Spring Boot version is outside Stratum's supported line, Stratum stops
  it with a message.
- Starting with `--debug` prints Spring Boot's conditions report. Under "Negative matches", each
  Stratum bean the company replaced is listed with `@ConditionalOnMissingBean` and the bean that
  was found. See [Troubleshooting](troubleshooting.md).

The company parent keeps setting Java, plugins and build checks. `stratum-parent`'s build checks do
not apply in this setup; the company parent's apply instead.

## Setup C: fork Stratum under the company parent

A team can fork Stratum, which is open source, and make the company parent the parent of
`stratum-parent`. The chain is then Spring Boot → company parent → forked `stratum-parent` →
service, and services get the company's and Stratum's build settings from one parent.

**What the fork changes:**

1. In `stratum-parent/pom.xml`, replace the `<parent>` (`spring-boot-starter-parent`) with the
   company parent.
2. Give the fork its own `groupId` or a version suffix, such as `0.1.0-acme.1`, so its artifacts
   never mix with Stratum's own in a shared Maven repository.
3. Build the whole fork with `mvn verify`, and publish it to the company's repository.

**What the company parent must provide:**

- It extends `spring-boot-starter-parent` at a version in Stratum's supported line.
- It supports Java 25. `stratum-parent` sets `maven.compiler.release` to 25, which overrides the
  company parent's Java setting for every service below it.

**How build settings combine:** Maven merges the plugin settings of both parents, plugin by plugin
and then execution by execution:

- Executions with different ids both run. If the company parent formats or checks code with
  different rules than Stratum, both checks run and can disagree.
- Executions with the same id are merged, and Stratum's settings win, because `stratum-parent` is
  the closer parent.
- Stratum names its plugin executions with a `stratum-` prefix so that they never merge by accident
  with the company's. The team decides which checks to keep and removes the others in the fork.

**Beans:** company starters and Stratum starters are on the same classpath, so rules 2 and 3 apply
exactly as in setup B.

**The cost:** every Stratum release must be merged into the fork, built and published again. The
merge stays small only if the fork changes nothing but the parent and the coordinates. For that
reason, setup B is recommended unless the team needs a single parent.

## Common problems

| Problem | Likely cause | Fix |
| --- | --- | --- |
| Startup stops: Spring Boot version not supported | The company parent manages a Spring Boot version outside Stratum's line | Use the Stratum release that supports it, or wait for one. See [Versioning and upgrades](upgrading.md) |
| A Stratum bean is used instead of the company's | The company bean is created later or has a different type | Order the company auto-configuration with `beforeName`, or turn the Stratum module off |
| `NoSuchMethodError` at startup | A library's version comes from the company parent and is older than Stratum needs | Compare `mvn dependency:tree` with the release notes and align the company parent |
