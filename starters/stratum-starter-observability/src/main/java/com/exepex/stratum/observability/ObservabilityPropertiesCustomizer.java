package com.exepex.stratum.observability;

import com.exepex.stratum.core.properties.StratumPropertiesCustomizer;

/**
 * Changes the observability settings in Java code, and wins over the same settings in files.
 * Stratum reads every module's Java configuration the same way, so a customizer is not a bean: list
 * its class in the service's {@code META-INF/spring.factories} under this interface's name. It
 * needs a public no-argument constructor and runs in {@code @Order} order with any others.
 */
@FunctionalInterface
public interface ObservabilityPropertiesCustomizer
    extends StratumPropertiesCustomizer<ObservabilityProperties> {}
