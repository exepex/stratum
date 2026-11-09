# HTTP client: mocked clients and `MockClient`

How a Stratum client answers without calling its service: from files in a demo, or from a test's script. Part of the
[HTTP client](http-client.md) page, which lists every key.

[← HTTP client](http-client.md) · Design: [ADR 0011](../adr/0011-http-client-starters.md), decision 10, and
[ADR 0012](../adr/0012-http-client-changes-found-while-building.md), decision 2

## Turn a client's mock on

Set `mock: true` on a client whose service is not deployed yet, such as in a demo, or in a test profile. In
`application.properties`:

```properties
stratum.http-client.clients.payments.url=http://payments:8080
stratum.http-client.clients.payments.mock=true
```

or in `application.yml`:

```yaml
stratum:
  http-client:
    clients:
      payments:
        url: http://payments:8080
        mock: true
```

`url` stays required, so the block reads the same with the mock off. In Java, set `mock(true)` on the client's
settings in an `HttpClientPropertiesCustomizer` ([Configure in Java](http-client.md#configure-in-java)).

A mocked client opens no connection and sends no credential. Each startup logs one `WARN` per mocked client, such as
`HTTP client payments answers from mocks and never calls its service (mock: true)`, so a mock left on in production
is never silent.

## Where the answers come from

Each call of a client interface's method gets the first of these:

1. the answer a test scripted with `MockClient` (below);
2. the file `stratum-mocks/<client>/<method>.json` on the classpath, such as
   `src/main/resources/stratum-mocks/payments/createPayment.json`, answered as `200` with `Content-Type:
   application/json`;
3. for a method that reads no body, such as one returning `void`, `ResponseEntity<Void>` or `HttpHeaders`, `200` with
   no body;
4. otherwise `501 Not Implemented`, with the text `no mock answer for client payments, method createPayment: add
   stratum-mocks/payments/createPayment.json or script it with MockClient`.

Spring reads the answer as it reads a real one: your converters turn the JSON into the method's return type, and your
status handlers see an error status. Overloaded methods share one file. Each file is read once; each call reads its
answer anew, so changing a returned object never changes the next answer. The body your code sends is read and
dropped, so a streamed upload behaves as with a real service.

Retries and the circuit breaker work on a mocked client as on a real one: a scripted `503` on a `GET` is sent again.
Only the methods of the client's interfaces are mocked. A call through `stratumHttpClients.restClient("payments")`
answers `501`, naming the client.

## Script answers in a test

Inject `MockClients` and ask for the client of an interface:

```java
@SpringBootTest(properties = "stratum.http-client.clients.payments.mock=true")
class CheckoutTest {

  @Autowired MockClients mockClients;
  @Autowired Checkout checkout;

  @Test
  void chargesOnce() {
    var payments = mockClients.of(PaymentClient.class);
    payments.on("createPayment").respond(new Payment("p-1", "OK"));

    checkout.checkout(cart());

    assertThat(payments.calls("createPayment")).hasSize(1);
  }
}
```

| Call | What it does |
| --- | --- |
| `on("method").respond(value)` | Answers `200` with the value: a text as it is, any other value as JSON written by the application's JSON mapper, no body for `null`. Pass the object, such as `new Payment(...)`, not its JSON text: a text is sent as `text/plain`, which the JSON converter does not read. |
| `on("method").fail(HttpStatus.NOT_FOUND)` | Answers that status with no body; `fail(status, "{...}")` adds a JSON body. |
| `on("method").delay(Duration.ofMillis(200)).respond(value)` | Answers after the delay. A delay longer than the call's limit, the client's `timeout` or the time its `total-timeout` leaves, waits that limit and fails the call with `SX-HTTPC-0001`, as a read timeout would ([Errors](http-client-errors.md)). |
| `calls("method")` | The calls of that method since `of` was called: each with its method name and arguments. |
| `mockClients.reset()` | Clears every script and call, inside a test. |

A script applies to every later call of the method. Scripts and recorded calls are cleared after each test method,
so one test never sees another's. Calls are recorded only for a client a test asked for with `of`, so a demo
application with `mock: true` keeps nothing in memory. Asking for an interface whose client is not mocked stops the
test with a message that names the setting to add.
