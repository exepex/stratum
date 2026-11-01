# Logging: workflows

This page belongs to the [logging starter](../../starters/stratum-starter-logging/README.md).

A workflow is a named piece of business work, such as checking out an order. Name it once, and
every log line written while it runs carries its name, so a dashboard can show the load and the
failures of each workflow in every service.

With AspectJ on the classpath, put `@Workflow` on a method of a Spring bean:

```java
@Service
class CheckoutService {

  @Workflow("ORDER_CHECKOUT")
  public Receipt checkOut(Order order) {
    log.info("Order checked out"); // carries "workflow":"ORDER_CHECKOUT"
    return paymentService.pay(order); // its own @Workflow("PAYMENT") is nested inside
  }
}
```

AspectJ is the library Spring uses to run code around annotated methods. Add it with Spring Boot's
starter; Stratum does not bring it:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-aspectj</artifactId>
</dependency>
```

Without AspectJ, `@Workflow` is ignored. Spring also skips it on a private method, and when a
method calls another method of the same class. In those cases, and to give a workflow an ID such
as an order number, inject `Workflows` and run the work through it:

```java
@Service
@RequiredArgsConstructor
class RefundService {

  private final Workflows workflows;

  void refund(Order order) {
    workflows.run("ORDER_REFUND", order.number(), () -> sendRefund(order));
  }

  Receipt price(Order order) {
    return workflows.call("ORDER_PRICING", () -> priceList.price(order));
  }
}
```

`run` is for work that returns nothing and `call` for work that returns a value. Both pass on any
exception the work throws, unchanged.

| Field | Value |
| --- | --- |
| `workflow` | The name of the workflow the line was written in: the innermost one when workflows are nested |
| `root_workflow` | The name of the outermost workflow, so `ORDER_CHECKOUT` failures can be counted even when they happen inside `PAYMENT` |
| `workflow_id` | The ID given to `Workflows`, kept by the workflows nested inside it. Left out when none was given, or when it is not 1 to 128 letters, digits, `-`, `_`, `.` or `:`, so it cannot forge a log line |

When the workflow ends, the outer workflow's fields come back, or the fields are removed. A name is
a fixed word of 1 to 128 letters, digits, `-`, `_`, `.` or `:`, such as `ORDER_CHECKOUT`; any other
name throws an `IllegalArgumentException`. Never build a name from data, such as an order number:
that is what the ID is for.

When the service has an `ObservationRegistry`, as with the
[observability starter](../../starters/stratum-starter-observability/README.md), each workflow is also timed and
traced, with no extra code:

- A timer named `stratum.workflow`, tagged with `workflow` and `root_workflow`, counts the calls,
  the failures (tag `error`) and their durations. The observability starter stores durations as
  histograms, so a dashboard reads any percentile per workflow, for one pod or for all.
- A span named after the workflow, with the ID as the `workflow_id` attribute, so a trace can be
  found by an order number. A failure marks the span as failed. Log lines mask an ID that looks
  like a card or national ID, but the span gets it as it is: use an order number or a similar
  business ID, never personal data.

Stratum records only how long a workflow took. It never labels a workflow slow or fast; your
dashboards and alerts decide that. Without a registry, workflows only set the log fields.

## Async and reactive work

A workflow ends when its work has finished, even when that is after the call returns:

- Work that returns a `CompletableFuture` or another `CompletionStage` is timed and traced until
  the stage completes, and its failure is recorded.
- A `@Workflow` method that returns a `Mono` or `Flux` only builds the work. The workflow runs
  each time the result is subscribed, until it completes, fails or is cancelled. Lines written
  while the method builds it are outside the workflow, and so is an exception it throws instead of
  returning one: it passes on unchanged, but is not timed. The class's method decides, so this
  holds when an interface declares `Publisher` or a type variable and the class returns `Mono` or
  `Flux`. A method that returns only `Publisher` runs as other work does.

Without `@Workflow`, run a `Mono` or `Flux` through `ReactiveWorkflows`, a bean that exists when
Reactor is on the classpath. `flux` does the same for a `Flux`:

```java
@Service
@RequiredArgsConstructor
class PaymentService {

  private final ReactiveWorkflows reactiveWorkflows;
  private final PaymentGateway gateway;

  Mono<Receipt> pay(Order order) {
    return reactiveWorkflows.mono("PAYMENT", order.number(), gateway.charge(order));
  }
}
```

The fields follow the work to other threads:

- **`@Async` methods and executors.** With `spring.task.execution.propagate-context=true`, which the
  starter sets, Spring Boot's task executor and scheduler, executors built with its builder, and
  pools you declare as `Executor`, `ExecutorService` or `ScheduledExecutorService` beans keep the
  fields, and the trace and correlation IDs with observability. Pools you create in code outside a
  bean, and `CompletableFuture.supplyAsync` alone, do not.
- **Reactor.** The fields travel in the Reactor context. The starter sets Spring Boot's
  `spring.reactor.context-propagation=auto` and turns on Reactor's context propagation, also without
  WebFlux or WebClient, so the lines written in every operator, such as `map` after a thread change,
  carry them. Setting `spring.reactor.context-propagation=limited` keeps Spring Boot's own mode,
  where only `handle` and `tap` lines carry them, saving a small cost per operator.
- **`@Scheduled` jobs.** Each run starts outside any workflow, as its own piece of work.

The fields stay inside the service. With the observability starter, the trace ID reaches the
services it calls; sending the workflow fields too is [planned](../../starters/stratum-starter-logging/README.md#planned). The observability
starter also puts `traceId`, `spanId` and `correlationId` on the lines written inside a traced
operation, reactive code included. The module runs before Spring Boot's task scheduling;
[threads](../guides/context-across-threads.md) explains why and lists every case.
