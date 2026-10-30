# Linking work that runs later

[← Documentation home](../README.md)

A request's trace and correlation ID should follow work only when the request caused that work. A
`@Scheduled` cleanup job that happens to run while an API call is served has nothing to do with it,
and putting both in one trace hides what each one did. A report that an API call asked for, and a
job later writes to S3, a file, FTP or email, does belong to that call, and you want to find it from
the call's trace. This page gives the rule and the observability starter's helper for the second
case.

## The rule

| The work | What Stratum does | Trace | Correlation ID |
| --- | --- | --- | --- |
| No request caused it, such as a `@Scheduled` job or a queue poller | Nothing to do: it starts clean | Its own new trace | Its own trace ID |
| A request caused it, and it runs in the same service straight away: `@Async`, an executor, a Reactor operator | Nothing to do: the context travels ([across threads](context-across-threads.md)) | The request's trace, as a child span | The request's |
| A request caused it, and the service saves it to run later, from a table, a queue or a file | Use `LinkedWork` | Its own new trace, with a link to the request's span | The request's, when one request caused it |

A trace covers one request from start to end. Work saved for later can run minutes or hours after
the request has answered, and a batch often serves many requests at once, so it gets its own trace.
A span link records which request caused it: trace backends such as Jaeger, Tempo and Grafana show
the link and open the request's trace from it.

Each `@Scheduled` run already starts its own trace, with no field from any request, even when it
runs on a thread a request used before. Stratum tests this, so you never need to clear anything at
the start of a job.

## Saving work for later

`LinkedWork` is a bean of the observability starter. While handling the request, store where the
work came from next to the work itself:

```java
@PostMapping("/invoices/export")
void requestExport(@RequestBody ExportRequest request) {
  var origin = linkedWork.captureOrigin().map(WorkOrigin::toText).orElse(null);
  exportRequests.save(new ExportRequestRow(request.month(), origin));
}
```

`captureOrigin()` returns the current span and correlation ID, or nothing outside a traced
operation. `toText()` gives at most 184 characters, a W3C `traceparent`, a space and the
correlation ID, such as `00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01 order-42`. It holds
no personal data and is safe to log, so it can go in a database column, a message header or a file
name.

## Running it later

When the job picks the work up, read the origin back and run the work inside `LinkedWork`:

```java
@Scheduled(fixedDelay = 60_000)
void exportInvoices() {
  exportRequests.findPending().forEach(row -> {
    var origins = Optional.ofNullable(row.origin()).map(WorkOrigin::fromText).stream().toList();
    linkedWork.run("invoice-export", origins, () -> exporter.writeToS3(row));
  });
}
```

Each run gets its own span, named after the work, with a link to the request's span. Its log lines
carry the request's correlation ID, so searching the logs for that ID finds the request and the
export together. Inside a traced job, such as a `@Scheduled` method, the span sits in the job's
trace, under the job's span, so one job run with several exports shows as one trace.

The name, `invoice-export` here, is a fixed name for the kind of work, never data: 1 to 128
letters, digits, `-`, `_`, `.` or `:`. It is the `work` tag of the `stratum.linked.work` timer, so
each kind of work gets its own timings. `call` does the same as `run` and returns the work's result.

A batch that serves several requests gives one origin for each and gets a link to each:

```java
linkedWork.run("daily-statement", origins, () -> statements.sendAll(rows));
```

With more than one origin the run cannot pick one correlation ID, so its lines carry the trace ID of
its own trace as `correlationId`, as any other job does. The links still lead to every request.

`WorkOrigin.fromText` throws `IllegalArgumentException` for text in any other form, and the message
never shows the text. Decide what a bad row means for your job: skip it, or run the work with no
origin, which is an empty list.

## Reactive code

`ReactiveLinkedWork` does the same for a `Mono` or `Flux`. Each subscription is one run, timed and
traced until it completes, fails or is cancelled, and every operator inside it logs the request's
correlation ID on any thread:

```java
Mono<Void> exportInvoice(ExportRequestRow row) {
  var origins = Optional.ofNullable(row.origin()).map(WorkOrigin::fromText).stream().toList();
  return reactiveLinkedWork.mono("invoice-export", origins, exporter.writeToS3(row));
}
```

`flux` does the same for a `Flux`. `ReactiveLinkedWork` exists only when Reactor is on the classpath.

## Your own beans

`LinkedWork` and `ReactiveLinkedWork` are created only when the service has no bean of that type.
The span links are made by `LinkedWorkTracingHandler`, which runs before Spring Boot's tracing
handler for linked work only; a service that declares its own `LinkedWorkTracingHandler` replaces
it. There are no settings: the helper does nothing until your code calls it.

## What it does not do

- It never links work on its own. A job that should stay unrelated needs nothing.
- It does not send the origin to other services. To pass a request on to another service, call it
  while handling the request; Stratum sends `traceparent` and the correlation ID then.
- It does not carry your own MDC fields, such as `customerId`, into the later run. Store what the
  job needs with the work, and put it in the MDC inside the run.
