# Log fields across threads

[← Documentation home](../README.md)

A request's work often moves to other threads: an `@Async` method, a task on an executor, an event
listener, a Reactor operator after `publishOn`. With the logging starter on, every log line written
there carries the same fields as the request's own lines, and never another request's. This page
says what travels, where, and the few places your code has to help.

## What travels

| Field | Set by | Travels to other threads |
| --- | --- | --- |
| `traceId`, `spanId` | Tracing, with the observability starter | Yes |
| `correlationId` | The observability starter, from baggage | Yes |
| `workflow`, `root_workflow`, `workflow_id` | [Workflows](../../starters/stratum-starter-logging/README.md#workflows) | Yes |
| Any field your code puts with `MDC.put`, such as `customerId` | Your code | Yes, with the logging starter |

Fields stay inside the service. Only `traceparent` and the correlation ID in `baggage` go to the
services you call; your own MDC fields never do.

When the work on another thread ends, that thread's MDC goes back to what it held before, normally
nothing. The next request served by the same pool thread starts clean.

## Blocking code

Put the field once, where the request starts, and remove it when the request ends:

```java
try (var ignored = MDC.putCloseable("customerId", customerId)) {
  orderService.place(order);
}
```

Every line written inside then carries `customerId`, on whichever of these threads it runs:

| Where the work runs | Carried |
| --- | --- |
| The same thread, including `@EventListener` methods | Yes |
| `@Async` methods, `@Async @EventListener` methods | Yes |
| Spring Boot's task executor, and executors built with its `ThreadPoolTaskExecutorBuilder` | Yes |
| A bean you declare as `ExecutorService` or `ScheduledExecutorService` | Yes |
| An `ExecutorService` wrapped with `ContextExecutorService.wrap(pool)` | Yes |
| A bean declared as its class, such as `ThreadPoolExecutor` | No |
| `Executors.newFixedThreadPool(...)` and other pools you create in code without wrapping | No |
| `new ThreadPoolTaskExecutor()` without Spring Boot's builder | No |
| `new Thread(...)`, `Thread.ofVirtual().start(...)` | No |
| `CompletableFuture.supplyAsync(task)` without an executor (the JVM's common pool) | No |

Stratum wraps every bean you declare as `ExecutorService` or `ScheduledExecutorService` for you,
so this pool carries the fields as it is:

```java
@Bean
ExecutorService invoiceRenderingPool() {
  return Executors.newFixedThreadPool(4);
}
```

A bean declared as its class, such as `ThreadPoolExecutor`, is left alone, because the wrapper is
not that class and code that injects it would fail to start. Declare it as `ExecutorService`
instead. A pool you create in code, outside a bean, needs wrapping once with Micrometer's
`ContextExecutorService` or `ContextScheduledExecutorService`, which the logging starter brings:
`ContextExecutorService.wrap(Executors.newFixedThreadPool(4))`.

Declaring an `ExecutorService` bean stops Spring Boot from creating its own task executor, which
would leave `@Async` methods on a new thread per call with no fields. Stratum then creates the
executor Spring Boot would have made, with the same `spring.task.execution.*` settings, under the
names `applicationTaskExecutor` and `taskExecutor`. It does not when your service has its own
`TaskExecutor`, a bean named `taskExecutor` or an `AsyncConfigurer`, because `@Async` then uses
your choice, or when `stratum.context-propagation.async-threads` is `false`.

Give `CompletableFuture` methods an executor instead of using the common pool:
`CompletableFuture.supplyAsync(task, invoiceRenderingPool)`. Nothing can reach a bare
`new Thread(...)`; use a wrapped pool instead.

An event multicaster with its own executor (`SimpleApplicationEventMulticaster.setTaskExecutor`)
carries the fields only when that executor is one of the carried kinds above.

## Reactive code

A field put with `MDC.put` in a WebFlux handler, or anywhere before a `Mono` or `Flux` is
subscribed, follows the chain across Reactor's own thread changes, such as `publishOn`,
`subscribeOn` and `delayElement`: Reactor copies it to each task it schedules. Requests served
side by side by the same server threads never see each other's fields.

After a step that completes outside Reactor, such as `Mono.fromFuture(...)` with a
`CompletableFuture`, Reactor resets the MDC from the subscription and the field is gone. Wrap the
chain in `ReactiveMdcFields` to keep it there:

```java
Mono<Receipt> place(Order order) {
  return ReactiveMdcFields.mono(Map.of("orderId", order.id()), payments.charge(order));
}
```

Every operator inside `payments.charge(order)`, on any thread, then logs `orderId`, and also the
fields the subscribing thread held, such as a `customerId` the handler put. `ReactiveMdcFields.flux`
does the same for a `Flux`. Fields of an enclosing call are kept, and a field set again takes the
new value. `traceId`, `spanId`, `correlationId` and the workflow fields are refused with an
`IllegalArgumentException`, because tracing and workflows keep those in step with each span and
workflow.

Blocking code that calls `block()` on a `Mono` passes its own MDC fields into the chain, so no
helper is needed there.

This relies on Reactor's automatic context propagation, which the starter turns on by default
(`stratum.context-propagation.reactor`). With it set to `false`, the fields appear only in
`handle` and `tap`.

## Settings

| Property | Default | Effect |
| --- | --- | --- |
| `stratum.context-propagation.async-threads` | `true` | Spring Boot's executors and `@Async` carry the fields. |
| `stratum.context-propagation.reactor` | `true` | Every Reactor operator carries the fields. |
| `stratum.context-propagation.executor-beans` | `true` | Your `ExecutorService` and `ScheduledExecutorService` beans carry the fields. |

All three are in the [configuration reference](../configuration/reference.md).
