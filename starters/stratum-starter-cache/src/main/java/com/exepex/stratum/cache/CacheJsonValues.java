package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.serializer.RedisElementReader;
import org.springframework.data.redis.serializer.RedisElementWriter;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.DefaultBaseTypeLimitingValidator;
import tools.jackson.databind.type.TypeFactory;

/**
 * Stores Redis cache values as plain JSON, with no type name in the bytes, and reads them back only
 * as a type that the cache layer names. A read from Redis returns the bytes in a {@link CachedJson}
 * holder, unparsed. Types are allowed from the service's own packages and the allowed packages; a
 * cached method may also declare a {@code java} type, but a class named inside the JSON, through
 * {@code @JsonTypeInfo}, is refused unless it is in those packages, before it is loaded. A missing
 * field gets its default, primitives included, and an unknown field is ignored.
 */
@StratumInternal
public class CacheJsonValues implements RedisElementReader<Object>, RedisElementWriter<Object> {

  /** Allows classes from the allowed packages, whether declared or named inside the JSON. */
  private final AllowedPackagesValidator validator;

  /** Writes and reads the JSON. */
  private final JsonMapper mapper;

  /**
   * Creates the values for the given packages.
   *
   * @param allowedPackages the service's own packages and the allowed packages
   */
  public CacheJsonValues(List<String> allowedPackages) {
    this.validator =
        new AllowedPackagesValidator(allowedPackages.stream().map(name -> name + ".").toList());
    this.mapper =
        JsonMapper.builder()
            .findAndAddModules()
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .polymorphicTypeValidator(validator)
            .build();
  }

  /**
   * Returns the packages allowed beside {@code java}, for the startup line.
   *
   * @return the package names
   */
  public List<String> allowedPackages() {
    return validator.allowedPrefixes.stream()
        .map(prefix -> prefix.substring(0, prefix.length() - 1))
        .toList();
  }

  /**
   * Returns this reader and writer as the value pair of a Redis cache configuration.
   *
   * @return the pair
   */
  public SerializationPair<Object> serializationPair() {
    return SerializationPair.just(this, this);
  }

  /**
   * Returns whether a cache is a Redis cache whose values are read by this object.
   *
   * @param cache a cache the cache manager made
   * @return whether its values are this JSON
   */
  public boolean readsValuesOf(Cache cache) {
    return cache instanceof RedisCache redisCache
        && redisCache.getCacheConfiguration().getValueSerializationPair().getReader().equals(this);
  }

  /**
   * Writes a value as JSON, with no type name.
   *
   * @param value the value
   * @return the JSON bytes
   */
  @Override
  public ByteBuffer write(Object value) {
    return ByteBuffer.wrap(mapper.writeValueAsBytes(value));
  }

  /**
   * Returns the stored bytes, unparsed, for the cache layer to read as its method's type.
   *
   * @param buffer the stored bytes
   * @return the holder
   */
  @Override
  public Object read(ByteBuffer buffer) {
    return new CachedJson(buffer);
  }

  /**
   * Reads stored JSON as a type; an empty result when the JSON is {@code null} or does not fit the
   * type, such as a field whose type changed or a class named in the JSON that is not allowed.
   *
   * @param value the stored JSON
   * @param type the type to read it as
   * @return the value, or empty when it cannot be read
   */
  public Optional<Object> read(CachedJson value, JavaType type) {
    try (var stored = value.open()) {
      return Optional.ofNullable(mapper.readValue(stored, type));
    } catch (JacksonException _) {
      return Optional.empty();
    } catch (IOException closing) {
      // A stream over memory never fails to close.
      throw new UncheckedIOException(closing);
    }
  }

  /**
   * Returns the factory that builds the types values are read as.
   *
   * @return the factory
   */
  public TypeFactory typeFactory() {
    return mapper.getTypeFactory();
  }

  /**
   * Returns whether a cached method may declare the class: a {@code java} class, or one from the
   * allowed packages. A primitive has no package and is allowed.
   *
   * @param type the class
   * @return whether it is allowed
   */
  public boolean allowsDeclared(Class<?> type) {
    return type.isPrimitive()
        || type.getName().startsWith(CacheConstants.JAVA_PACKAGE)
        || validator.allows(type.getName());
  }

  /**
   * Allows a class named inside the JSON only from the allowed packages, never {@code java}. The
   * name is checked before the class is loaded, the loaded class again, and the base types Jackson
   * finds unsafe stay denied. Package-private so a test can check the name check on its own.
   */
  @RequiredArgsConstructor
  static final class AllowedPackagesValidator extends DefaultBaseTypeLimitingValidator {

    private static final long serialVersionUID = 1L;

    /** The allowed package names, each with a trailing dot, so a longer name never matches. */
    private final List<String> allowedPrefixes;

    @Override
    public Validity validateSubClassName(
        DatabindContext context, JavaType baseType, String subClassName) {
      return allows(subClassName) ? Validity.INDETERMINATE : Validity.DENIED;
    }

    @Override
    protected boolean isSafeSubType(DatabindContext context, JavaType baseType, JavaType subType) {
      return allows(subType.getRawClass().getName());
    }

    /** Returns whether a class name is in the allowed packages. */
    private boolean allows(String className) {
      return allowedPrefixes.stream().anyMatch(className::startsWith);
    }
  }
}
