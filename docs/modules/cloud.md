# Cloud connectors: `stratum-starter-cloud-*`

**Status: Planned, early design.** Which cloud providers come first is still open. This page states
the intended shape so services can plan for it.

[← Documentation home](../README.md) · Property prefix: `stratum.cloud`

## What it does

Services often need a few cloud services: object storage for files, a message queue, a secret
store. Each provider has its own SDK, credentials model and retry rules. Cloud starters give each of
these the same Stratum treatment: credentials from the platform (never from files), timeouts,
retries, metrics and tracing, and a small Stratum interface in front of the provider SDK so business
code does not depend on one provider.

## Planned starters

| Starter | Provider | Planned features |
| --- | --- | --- |
| `stratum-starter-cloud-aws` | Amazon Web Services | S3 storage, SQS queues, Secrets Manager and Parameter Store as property sources |
| `stratum-starter-cloud-azure` | Microsoft Azure | Blob Storage, Service Bus, Key Vault as a property source |
| `stratum-starter-cloud-gcp` | Google Cloud | Cloud Storage, Pub/Sub, Secret Manager as a property source |

Add the one for your provider:

```xml
<dependency>
  <groupId>com.exepex.stratum</groupId>
  <artifactId>stratum-starter-cloud-aws</artifactId>
</dependency>
```

The starter does nothing until you switch it on, in `application.properties`:

```properties
stratum.cloud.enabled=true
```

or in `application.yml`:

```yaml
stratum:
  cloud:
    enabled: true
```

## Provider-neutral interfaces

Business code talks to small Stratum interfaces. Switching provider means changing the starter and
configuration, not your code.

```java
@Service
class InvoiceArchive {

  private final ObjectStorage storage;   // Stratum interface

  InvoiceArchive(ObjectStorage storage) {
    this.storage = storage;
  }

  void archive(Invoice invoice, byte[] pdf) {
    storage.put("invoices", invoice.id() + ".pdf", pdf, "application/pdf");
  }
}
```

The provider SDK's own clients (for example `S3Client`) are also available as beans when you need a
feature the Stratum interface does not cover.

## Credentials

Stratum never reads cloud credentials from `application.yml` or `application.properties`. It uses
each provider's default credential chain, which finds credentials from the platform: a Kubernetes
service account bound to a cloud role (IRSA on AWS, Workload Identity on Azure and GCP), an instance
role, or environment variables on a developer machine.

## Properties (draft)

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `stratum.cloud.enabled` | boolean | `false` | Switches the whole starter on. Until it is `true`, the starter sets up nothing. |
| `stratum.cloud.region` | string | *(from the platform)* | Region to use. |
| `stratum.cloud.storage.buckets.<alias>` | string | *(none)* | Maps an alias used in code (`invoices`) to a real bucket or container name. |
| `stratum.cloud.secrets.enabled` | boolean | `false` | Loads secrets from the provider's secret store as properties. |
| `stratum.cloud.secrets.prefix` | string | `<spring.application.name>/` | Only secrets under this prefix are loaded. |
| `stratum.cloud.timeouts.api-call` | duration | `10s` | Timeout for each call to the provider. |

```yaml
stratum:
  cloud:
    region: eu-west-1
    storage:
      buckets:
        invoices: acme-prod-invoices
    secrets:
      enabled: true
```

```properties
stratum.cloud.region=eu-west-1
stratum.cloud.storage.buckets.invoices=acme-prod-invoices
stratum.cloud.secrets.enabled=true
```

## Configure in Java

**Planned.** You will also be able to set every property on this page in Java code, through this module's
customizer or builder, with the same names and defaults. This section will show how once that API is designed.

## Testing

`stratum-test` will include containers that emulate cloud services (LocalStack for AWS, Azurite for
Azure Storage, the Pub/Sub emulator for GCP), so integration tests do not need a cloud account. See
[Testing](test.md).

## Open questions

- Which provider is needed first?
- Should messaging through cloud queues share an interface with Kafka, or stay separate?

Feedback goes to the project's issue tracker.
