package com.exepex.stratum.restclient.testsupport.headers;

import com.exepex.stratum.httpclient.OutgoingRequest;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Builder;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;

/** What the interceptors saw, in the order they ran, and whether the next call is refused. */
public class CallLog {

  /** Every run of an interceptor, in order. */
  private final List<Run> recorded = new CopyOnWriteArrayList<>();

  /** The name of every interceptor that started a run, in order, even one that then failed. */
  private final List<String> started = new CopyOnWriteArrayList<>();

  /** What the audit interceptor throws on the next call, if it refuses it. */
  private final AtomicReference<@Nullable RuntimeException> refusal = new AtomicReference<>();

  /**
   * Records one run of an interceptor.
   *
   * @param interceptor the interceptor's name
   * @param request the attempt it saw
   */
  public void note(String interceptor, OutgoingRequest request) {
    started.add(interceptor);
    recorded.add(
        Run.builder()
            .interceptor(interceptor)
            .client(request.client())
            .method(request.method())
            .authorization(request.header(HttpHeaders.AUTHORIZATION).orElse(""))
            .body(request.body().orElse(null))
            .text(request.toString())
            .build());
  }

  /**
   * Returns every run since the last {@link #clear()}.
   *
   * @return the runs, in order
   */
  public List<Run> runs() {
    return List.copyOf(recorded);
  }

  /**
   * Returns the names of the interceptors that ran since the last {@link #clear()}, in order.
   *
   * @return the names
   */
  public List<String> interceptors() {
    return recorded.stream().map(Run::interceptor).toList();
  }

  /**
   * Returns the names of the interceptors that started a run since the last {@link #clear()},
   * including one whose reading of the body failed.
   *
   * @return the names, in order
   */
  public List<String> starts() {
    return List.copyOf(started);
  }

  /** Forgets every run, and any refusal not used. */
  public void clear() {
    recorded.clear();
    started.clear();
    refusal.set(null);
  }

  /** Has the audit interceptor refuse the next call, before anything is sent. */
  public void refuseNextCall() {
    refuseNextCallWith(new IllegalStateException("The audit refused the call"));
  }

  /**
   * Has the audit interceptor refuse the next call with the given failure, before anything is sent.
   *
   * @param failure what the audit interceptor throws
   */
  public void refuseNextCallWith(RuntimeException failure) {
    refusal.set(failure);
  }

  /**
   * Returns the failure the next call is refused with, and forgets it.
   *
   * @return the failure, or {@code null} when the call goes on
   */
  public @Nullable RuntimeException takeRefusal() {
    return refusal.getAndSet(null);
  }

  /**
   * One run of an interceptor.
   *
   * @param interceptor the interceptor's name
   * @param client the client of the call
   * @param method the call's method
   * @param authorization the call's {@code Authorization} header, empty when it has none
   * @param body the body the interceptor could read, or {@code null} when it could read none
   * @param text the request's text
   */
  @Builder
  public record Run(
      String interceptor,
      String client,
      String method,
      String authorization,
      byte @Nullable [] body,
      String text) {}
}
