package com.exepex.stratum.logging;

import com.exepex.stratum.core.properties.StratumPropertiesCustomizer;

/**
 * Changes the logging settings in Java code, and wins over the same settings in files. Logging
 * starts before any bean exists, so a customizer is not a bean: list its class in the service's
 * {@code META-INF/spring.factories} under this interface's name. It needs a public no-argument
 * constructor and runs in {@code @Order} order with any others. It may run more than once during
 * startup, so it must return the same settings every time and have no side effects ({@link
 * com.exepex.stratum.core.properties.StratumPropertiesCustomizer}).
 */
@FunctionalInterface
public interface LoggingPropertiesCustomizer
    extends StratumPropertiesCustomizer<LoggingProperties> {}
