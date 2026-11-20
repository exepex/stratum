# HTTP client: streaming

How a call of a `@StratumClient` interface streams a large download or upload instead of holding it in memory. Part
of the [HTTP client](http-client.md) page.

[← HTTP client](http-client.md) · Design: [ADR 0011](../adr/0011-http-client-starters.md), decision 7

## Downloads and uploads

A client streams a download when its method returns `InputStream` or `ResponseEntity<InputStream>`. The method
returns once the answer's headers arrive, and the body comes in as you read it. Every other return type, `byte[]` and
`Resource` included, reads the whole body into memory first, so it is limited by `response-body-limit`; a download is
not. `ResponseEntity<InputStreamResource>` is not supported by Spring as a return type. A streamed download is not cut
off by `total-timeout`: only `read-timeout` between bytes bounds it, so close the stream when you are done with it.

A client streams an upload when its `@RequestBody` is a `StreamingHttpOutputMessage.Body` or an `InputStreamResource`.
The body is sent in chunks as it is written, never held in memory.

```java
@StratumClient("documents")
public interface DocumentsClient {

  @GetExchange("/reports/{id}")
  InputStream report(@PathVariable String id);

  @PostExchange("/archive")
  void archive(@RequestBody StreamingHttpOutputMessage.Body document);
}

try (InputStream report = documents.report("2026-q3")) {
  report.transferTo(file);
}
documents.archive(out -> Files.copy(path, out));
```

Close every download, as `try-with-resources` does above. An open download holds one of the client's connections until
it is closed; when all of them are held, the next call fails after `max-wait`. Closing a download before its end reads
the rest of it first, so that the connection can be used again: the thread and the connection stay busy until the
download ends, and a pause in it longer than `read-timeout` ends the close with `SocketTimeoutException`.

`read-timeout` is the longest wait for the next byte, not for the whole download, so a large file that keeps arriving is
read in full. A download that stops for longer than `read-timeout` fails with `SocketTimeoutException` as you read it.
