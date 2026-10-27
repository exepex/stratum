# Frequently asked questions

[← Documentation home](../README.md)

## General

**Is Stratum a replacement for Spring Boot?**
No. Stratum is built on Spring Boot and configures it. Your service is still a Spring Boot
application, and every Spring Boot guide still applies.

**Why "SDK" and not "framework"?**
You build your services *with* it, the way you build with any SDK: it gives you ready-made parts
and a standard way to use them. It adds no new programming model on top of Spring.

**Do I have to use every starter?**
No. Add only what your service needs. A starter you do not add costs nothing.

**Is Stratum ready to use?**
Not yet. It is in the design stage; the documentation describes the planned behaviour.

## Configuration

**Should I use `application.yml` or `application.properties`?**
Either. Stratum supports both equally, and every example in these docs shows both. Pick one per
service. See [Configuration basics](../configuration/basics.md).

**Can I still use Spring Boot properties such as `server.port` or `logging.level.*`?**
Yes. Stratum's defaults for Spring Boot properties have the lowest priority, so your values always
win.

**How do I see every property and its default?**
In the [Configuration reference](../configuration/reference.md), or through your IDE's completion in
the configuration file.

**Where do I put passwords and client secrets?**
Never in a file in Git. Use `${PLACEHOLDER}` in the file and provide the value from the environment
or a secret store. See [Keeping secrets safe](../configuration/profiles-and-environments.md#keeping-secrets-safe).

## Customization

**Stratum's default does not suit my service. What do I do?**
Change the property first. If that is not enough, use a customizer, then replace the bean. See
[Overriding defaults](../configuration/overriding-defaults.md). If many services need the same
change, ask for the default to change in Stratum.

**Can I use a library Stratum does not include?**
Yes. Add it with a version as usual. If several services need it, propose adding it to the BOM.

**Can I use WebFlux (reactive) instead of Spring MVC?**
Not in the first release. The web and security starters target the servlet stack, which on Java 25
handles high concurrency well with virtual threads. Reactive support may follow if services need it.

## Versions

**Which Spring Boot version does Stratum use?**
Stratum 0.1 targets Spring Boot 4.1.2 and Java 25. See
[Versioning and upgrades](upgrading.md#compatibility).

**Can I use a newer Spring Boot than Stratum's?**
Not safely. Stratum is tested with one Spring Boot version. Wait for the Stratum release that moves
to it.

## Testing

**Do I need Docker?**
Only for integration tests (`*IT.java`). Unit and slice tests (`mvn test`) run without it.

**Do I need a real identity provider in tests?**
No. `stratumJwt(...)` creates test tokens. See [Testing secured endpoints](../modules/test.md#testing-secured-endpoints).
