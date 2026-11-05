package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.ErrorReport;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheOperationInvocationContext;
import org.springframework.cache.interceptor.CacheableOperation;
import org.springframework.cache.support.NoOpCache;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.core.ResolvableType;
import tools.jackson.databind.JavaType;

/**
 * Reads Redis cache values as each cached method's return type. The type is decided once per
 * method: {@code Optional}, {@code CompletableFuture} and {@code Mono} give their content type and
 * {@code Flux} a list of it, as Spring stores them. A type that is {@code Object}, an interface or
 * abstract (collections and maps apart), or outside the allowed packages, is refused: the method
 * runs without the cache, counted in {@code stratum.cache.skips} and warned with {@code
 * SX-CACHE-1004}. A stored value over the cache's size limit, or one that does not fit the type, is
 * unreadable: a miss, counted and warned with {@code SX-CACHE-1005}. Warnings name the cache and
 * the type, never a key or value, at most once per cache per interval.
 */
@StratumInternal
@Slf4j
@RequiredArgsConstructor
public class TypedCacheValues implements CacheValueTyping {

  /** Stores and reads the JSON, and knows the allowed packages. */
  private final CacheJsonValues json;

  /** The size limit of each cache, checked before a value is parsed. */
  private final StratumCacheProperties properties;

  /** Admits one refusal warning per cache per interval. */
  private final WarningThrottle refusalThrottle;

  /** Admits one unreadable-value warning per cache per interval. */
  private final WarningThrottle unreadableThrottle;

  /** Counts the refusals and unreadable values. */
  private final CacheCounters counters;

  /** Each cached method's value type, decided on its first call; bounded by the code's methods. */
  private final Map<CachedMethod, ValueType> valueTypes = new ConcurrentHashMap<>();

  @Override
  public boolean appliesTo(Cache cache) {
    return json.readsValuesOf(cache);
  }

  /**
   * Returns the cache as a {@code @Cacheable} call sees it: typed reads, or, for a refused type, a
   * cache that never uses Redis. Puts and evicts need no type and get the cache unchanged.
   *
   * @param cache the layer's cache
   * @param context the call
   * @return the cache for this call
   */
  @Override
  public Cache typed(Cache cache, CacheOperationInvocationContext<?> context) {
    if (!(context.getOperation() instanceof CacheableOperation)) {
      return cache;
    }
    var key =
        new CachedMethod(
            context.getMethod(), AopProxyUtils.ultimateTargetClass(context.getTarget()));
    var valueType = valueTypes.computeIfAbsent(key, this::valueTypeOf);
    return valueType.refusal() == null
        ? new TypedCache(cache, valueType.type(), this)
        : refused(cache.getName(), valueType);
  }

  /**
   * Reads a stored value as the type, unless it is over the cache's size limit or does not fit;
   * then it is reported and the result is empty. A value that is not stored JSON is returned as it
   * is.
   *
   * @param cacheName the cache's name
   * @param value the stored value
   * @param type the type to read it as
   * @return the value, or empty when it is unreadable
   */
  Optional<Object> read(String cacheName, @Nullable Object value, JavaType type) {
    if (!(value instanceof CachedJson stored)) {
      return Optional.ofNullable(value);
    }
    var read =
        stored.size() <= properties.maxValueBytesFor(cacheName)
            ? json.read(stored, type)
            : Optional.empty();
    if (read.isPresent()) {
      return read;
    }
    counters.skip(unreadableThrottle.tagValueFor(cacheName), CacheConstants.REASON_UNREADABLE);
    report(
        unreadableThrottle,
        cacheName,
        CacheErrorCode.VALUE_UNREADABLE,
        "cache " + cacheName + ", type " + type.toCanonical());
    return Optional.empty();
  }

  /** Decides a method's value type, or why it is refused, once per method. */
  private ValueType valueTypeOf(CachedMethod key) {
    var declared = ResolvableType.forMethodReturnType(key.method(), key.targetClass());
    var stored = storedType(declared);
    return new ValueType(javaType(stored), refusal(stored));
  }

  /** Returns the type Spring stores for a declared return type. */
  private ResolvableType storedType(ResolvableType declared) {
    var raw = declared.resolve(Object.class);
    if (raw == Optional.class || raw == CompletableFuture.class) {
      return declared.getGeneric(0);
    }
    var adapter = ReactiveAdapterRegistry.getSharedInstance().getAdapter(raw);
    if (adapter == null) {
      return declared;
    }
    return adapter.isMultiValue()
        ? ResolvableType.forClassWithGenerics(List.class, declared.getGeneric(0))
        : declared.getGeneric(0);
  }

  /**
   * Returns why a type cannot be read, or {@code null} when it can: the type, each type argument
   * and each array component must be concrete and allowed.
   */
  private @Nullable String refusal(ResolvableType type) {
    if (type.isArray()) {
      return refusal(type.getComponentType());
    }
    var raw = type.resolve();
    if (raw == null || raw == Object.class || isAbstractValue(raw)) {
      return CacheConstants.REASON_TYPE_NOT_CONCRETE;
    }
    if (!json.allowsDeclared(raw)) {
      return CacheConstants.REASON_TYPE_NOT_ALLOWED;
    }
    return Arrays.stream(type.getGenerics())
        .map(this::refusal)
        .filter(reason -> reason != null)
        .findFirst()
        .orElse(null);
  }

  /** Returns whether a class is an interface or abstract, apart from collections and maps. */
  private boolean isAbstractValue(Class<?> raw) {
    return (raw.isInterface() || Modifier.isAbstract(raw.getModifiers()))
        && !raw.isPrimitive()
        && !Collection.class.isAssignableFrom(raw)
        && !Map.class.isAssignableFrom(raw);
  }

  /** Returns Jackson's type for a resolved type, type arguments and array components included. */
  private JavaType javaType(ResolvableType type) {
    var factory = json.typeFactory();
    if (type.isArray()) {
      return factory.constructArrayType(javaType(type.getComponentType()));
    }
    var raw = type.resolve(Object.class);
    var arguments = type.getGenerics();
    return arguments.length == 0
        ? factory.constructType(raw)
        : factory.constructParametricType(
            raw, Arrays.stream(arguments).map(this::javaType).toArray(JavaType[]::new));
  }

  /** Counts and warns a refused call, and returns a cache that never uses Redis. */
  private Cache refused(String cacheName, ValueType valueType) {
    var reason = String.valueOf(valueType.refusal());
    counters.skip(refusalThrottle.tagValueFor(cacheName), reason);
    report(
        refusalThrottle,
        cacheName,
        CacheErrorCode.TYPE_REFUSED,
        "cache "
            + cacheName
            + ", type "
            + valueType.type().toCanonical()
            + ", reason "
            + reason
            + ", setting "
            + CacheConstants.ALLOWED_PACKAGES);
    return new NoOpCache(cacheName);
  }

  /**
   * Warns once per cache per interval; the detail names the cache and type, never a key or value.
   */
  private void report(WarningThrottle throttle, String cacheName, ErrorCode code, String detail) {
    if (throttle.shouldWarn(cacheName) && log.isWarnEnabled()) {
      var report = ErrorReport.of(code, detail);
      // The report already turns line breaks into spaces; SpotBugs only sees this replacement.
      log.warn(report.getCodeAndMessage().replaceAll("[\r\n]", " "));
    }
  }

  /**
   * A cached method on the class it was called on, which resolves its generic return type.
   *
   * @param method the method
   * @param targetClass the class of the bean the method was called on
   */
  private record CachedMethod(Method method, Class<?> targetClass) {}

  /**
   * A cached method's value type, and why it is refused, if it is.
   *
   * @param type the type values are read as
   * @param refusal the reason the type is refused, or {@code null}
   */
  private record ValueType(JavaType type, @Nullable String refusal) {}
}
