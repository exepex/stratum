package com.exepex.stratum.cache.testsupport.values;

import com.exepex.stratum.cache.testsupport.valuesevil.Gadget;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.interceptor.BasicOperation;
import org.springframework.cache.interceptor.CacheOperationInvocationContext;
import org.springframework.cache.interceptor.CachePutOperation;
import org.springframework.cache.interceptor.CacheableOperation;
import org.springframework.util.ReflectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Cached methods of every return type the JSON value tests read, and the calls to them as Spring's
 * interceptor describes them. Only their declared types matter; they are never run.
 */
public class OrderMethods {

  /** The cache every call names. */
  public static final String ORDERS = "orders";

  /**
   * Describes a {@code @Cacheable} call of one of these methods.
   *
   * @param methodName the method's name
   * @return the call
   */
  public static CacheOperationInvocationContext<?> cacheable(String methodName) {
    return call(methodName, new CacheableOperation.Builder());
  }

  /**
   * Describes a {@code @CachePut} call of one of these methods.
   *
   * @param methodName the method's name
   * @return the call
   */
  public static CacheOperationInvocationContext<?> cachePut(String methodName) {
    return call(methodName, new CachePutOperation.Builder());
  }

  /** Describes a call of a method with one operation on the orders cache. */
  private static CacheOperationInvocationContext<?> call(
      String methodName, org.springframework.cache.interceptor.CacheOperation.Builder builder) {
    builder.setName(methodName);
    builder.setCacheName(ORDERS);
    var operation = builder.build();
    var method = ReflectionUtils.findMethod(OrderMethods.class, methodName);
    var target = new OrderMethods();
    return new CacheOperationInvocationContext<BasicOperation>() {
      @Override
      public BasicOperation getOperation() {
        return operation;
      }

      @Override
      public Object getTarget() {
        return target;
      }

      @Override
      public Method getMethod() {
        return method;
      }

      @Override
      public Object[] getArgs() {
        return new Object[0];
      }
    };
  }

  /**
   * Returns an order.
   *
   * @return nothing: the tests read only the declared type
   */
  public @Nullable Order order() {
    return null;
  }

  /**
   * Returns the order as it reads after a field was added.
   *
   * @return nothing: the tests read only the declared type
   */
  public @Nullable OrderWithPriority orderWithPriority() {
    return null;
  }

  /**
   * Returns the order as it reads after a field changed type.
   *
   * @return nothing: the tests read only the declared type
   */
  public @Nullable OrderWithItemList orderWithItemList() {
    return null;
  }

  /**
   * Returns a paid order.
   *
   * @return nothing: the tests read only the declared type
   */
  public @Nullable PaidOrder paidOrder() {
    return null;
  }

  /**
   * Returns a measured order.
   *
   * @return nothing: the tests read only the declared type
   */
  public @Nullable MeasuredOrder measuredOrder() {
    return null;
  }

  /**
   * Returns times.
   *
   * @return an empty one: the tests read only the declared type
   */
  public List<Instant> times() {
    return List.of();
  }

  /**
   * Returns identifiers.
   *
   * @return an empty one: the tests read only the declared type
   */
  public Set<UUID> ids() {
    return Set.of();
  }

  /**
   * Returns orders by identifier.
   *
   * @return an empty one: the tests read only the declared type
   */
  public Map<String, Order> ordersById() {
    return Map.of();
  }

  /**
   * Returns orders as an array.
   *
   * @return an empty one: the tests read only the declared type
   */
  public Order[] orderArray() {
    return new Order[0];
  }

  /**
   * Returns a count.
   *
   * @return nothing: the tests read only the declared type
   */
  public @Nullable Long count() {
    return null;
  }

  /**
   * Returns a count as a primitive.
   *
   * @return nothing: the tests read only the declared type
   */
  public int quantity() {
    return 0;
  }

  /**
   * Returns a maybe-order.
   *
   * @return nothing: the tests read only the declared type
   */
  public Optional<Order> maybeOrder() {
    return Optional.empty();
  }

  /**
   * Returns an order later.
   *
   * @return nothing: the tests read only the declared type
   */
  public @Nullable CompletableFuture<Order> orderLater() {
    return null;
  }

  /**
   * Returns an order reactively.
   *
   * @return nothing: the tests read only the declared type
   */
  public @Nullable Mono<Order> orderMono() {
    return null;
  }

  /**
   * Returns orders reactively.
   *
   * @return nothing: the tests read only the declared type
   */
  public @Nullable Flux<Order> orderFlux() {
    return null;
  }

  /**
   * Returns anything, which cannot be read.
   *
   * @return nothing: the tests read only the declared type
   */
  public @Nullable Object anything() {
    return null;
  }

  /**
   * Returns an interface, which cannot be read.
   *
   * @return nothing: the tests read only the declared type
   */
  public @Nullable Payment payment() {
    return null;
  }

  /**
   * Returns an abstract class, which cannot be read.
   *
   * @return nothing: the tests read only the declared type
   */
  public @Nullable AbstractReport report() {
    return null;
  }

  /**
   * Returns a list of an interface, which cannot be read.
   *
   * @return an empty one: the tests read only the declared type
   */
  public List<Payment> payments() {
    return List.of();
  }

  /**
   * Returns a type from a package that is not allowed.
   *
   * @return nothing: the tests read only the declared type
   */
  public @Nullable Gadget gadget() {
    return null;
  }

  /**
   * Returns a list of a type from a package that is not allowed.
   *
   * @return an empty one: the tests read only the declared type
   */
  public List<Gadget> gadgets() {
    return List.of();
  }
}
