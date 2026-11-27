package com.exepex.stratum.resilience.it;

import com.exepex.stratum.resilience.testsupport.HeldRequests;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Holds its work in each way a Spring MVC handler answers, synchronous and asynchronous. */
@RestController
@RequiredArgsConstructor
@RequestMapping(AsyncTypesController.LIMITED)
class AsyncTypesController {

  /** The path of every endpoint, which one route limits. */
  static final String LIMITED = "/limited";

  /** Runs the work of the handlers that hand it to another thread themselves. */
  private final ExecutorService workers = Executors.newCachedThreadPool();

  /** The work the endpoints hold. */
  private final HeldRequests held;

  /**
   * Holds on the request thread.
   *
   * @return the answer
   */
  @GetMapping("/sync")
  String sync() {
    return held.hold();
  }

  /**
   * Holds in a {@code Callable}, which Spring MVC runs on its task executor.
   *
   * @return the work
   */
  @GetMapping("/callable")
  Callable<String> callable() {
    return held::hold;
  }

  /**
   * Holds in a {@code WebAsyncTask}.
   *
   * @return the work
   */
  @GetMapping("/web-async-task")
  WebAsyncTask<String> webAsyncTask() {
    return new WebAsyncTask<>(held::hold);
  }

  /**
   * Holds on another thread, which sets a {@code DeferredResult}.
   *
   * @return the result to come
   */
  @GetMapping("/deferred-result")
  DeferredResult<String> deferredResult() {
    var result = new DeferredResult<String>();
    workers.execute(() -> result.setResult(held.hold()));
    return result;
  }

  /**
   * Holds in a {@code CompletableFuture}.
   *
   * @return the answer to come
   */
  @GetMapping("/completable-future")
  CompletableFuture<String> completableFuture() {
    return CompletableFuture.supplyAsync(held::hold, workers);
  }

  /**
   * Holds, then sends one event and completes an {@code SseEmitter}.
   *
   * @return the emitter
   */
  @GetMapping("/sse-emitter")
  SseEmitter sseEmitter() {
    var emitter = new SseEmitter();
    workers.execute(() -> sendHeld(emitter));
    return emitter;
  }

  /**
   * Holds, then sends and completes a {@code ResponseBodyEmitter}.
   *
   * @return the emitter
   */
  @GetMapping("/response-body-emitter")
  ResponseBodyEmitter responseBodyEmitter() {
    var emitter = new ResponseBodyEmitter();
    workers.execute(() -> sendHeld(emitter));
    return emitter;
  }

  /**
   * Holds while it writes a {@code StreamingResponseBody}.
   *
   * @return the body
   */
  @GetMapping("/streaming")
  StreamingResponseBody streaming() {
    return body -> body.write(held.hold().getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Returns a streamed body from a {@code CompletableFuture}, so the request goes asynchronous
   * twice and holds in the second part.
   *
   * @return the answer to come
   */
  @GetMapping("/chained")
  CompletableFuture<ResponseEntity<StreamingResponseBody>> chained() {
    return CompletableFuture.supplyAsync(() -> ResponseEntity.ok(streaming()), workers);
  }

  /** Holds, then sends the answer and completes the emitter. */
  private void sendHeld(ResponseBodyEmitter emitter) {
    try {
      emitter.send(held.hold());
      emitter.complete();
    } catch (IOException clientGone) {
      emitter.completeWithError(clientGone);
    }
  }
}
