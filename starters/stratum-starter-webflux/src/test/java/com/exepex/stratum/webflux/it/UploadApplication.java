package com.exepex.stratum.webflux.it;

import com.exepex.stratum.web.RequestBodyLimit;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.util.unit.DataUnit;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** A reactive service that accepts orders as text, forms, and uploads as a stream. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(UploadApplication.UploadController.class)
public class UploadApplication {

  /** The endpoints, each answering with how many bytes its body had. */
  @RestController
  static class UploadController {

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
