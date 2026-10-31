package com.exepex.stratum.webflux.it;

import com.exepex.stratum.web.RequestBodyLimit;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.util.unit.DataUnit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A reactive service that accepts orders as text, forms, multipart uploads, and uploads as a
 * stream.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(UploadApplication.UploadController.class)
public class UploadApplication {

  /** The endpoints, each answering with how many bytes its body had. */
  @RestController
  static class UploadController {

    /** The size of the report that {@code /reports} builds, over its own buffer limit. */
    private static final int REPORT_BYTES = 2048;

    /** The buffer limit of {@code /reports}, smaller than its report. */
    private static final int REPORT_BUFFER_LIMIT = 1024;

    /**
     * Joins a report larger than its buffer limit, as a service that reads a large downstream
     * response into memory does. The limit has nothing to do with the request's body.
     *
     * @return never answers: the join fails
     */
    @GetMapping("/reports")
    Mono<String> report() {
      var report = DefaultDataBufferFactory.sharedInstance.wrap(new byte[REPORT_BYTES]);
      return DataBufferUtils.join(Flux.just(report), REPORT_BUFFER_LIMIT)
          .map(joined -> String.valueOf(joined.readableByteCount()));
    }

    /**
     * Reads the body as text, as most APIs do.
     *
     * @param order the body
     * @return its length
     */
    @PostMapping("/orders")
    Mono<String> order(@RequestBody Mono<String> order) {
      return order.map(text -> String.valueOf(text.length()));
    }

    /**
     * Reads a larger body as text, with a limit of its own.
     *
     * @param orders the body
     * @return its length
     */
    @RequestBodyLimit(value = 8, unit = DataUnit.KILOBYTES)
    @PostMapping("/orders/imports")
    Mono<String> importOrders(@RequestBody Mono<String> orders) {
      return orders.map(text -> String.valueOf(text.length()));
    }

    /**
     * Reads a form, which Spring reads from the original body.
     *
     * @param exchange the request and its response
     * @return how many fields it had
     */
    @PostMapping("/forms")
    Mono<String> form(ServerWebExchange exchange) {
      return exchange.getFormData().map(fields -> String.valueOf(fields.size()));
    }

    /**
     * Reads a multipart upload, which Spring reads from the original body.
     *
     * @param exchange the request and its response
     * @return how many parts it had
     */
    @PostMapping("/files")
    Mono<String> files(ServerWebExchange exchange) {
      return exchange.getMultipartData().map(parts -> String.valueOf(parts.size()));
    }

    /**
     * Reads the body as a stream of buffers, as an upload does.
     *
     * @param upload the body
     * @return its length
     */
    @PostMapping("/uploads/raw")
    Mono<String> upload(@RequestBody Flux<DataBuffer> upload) {
      return upload
          .map(
              buffer -> {
                var byteCount = buffer.readableByteCount();
                DataBufferUtils.release(buffer);
                return byteCount;
              })
          .reduce(0, Integer::sum)
          .map(String::valueOf);
    }
  }
}
