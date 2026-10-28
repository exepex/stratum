package com.exepex.stratum.core.properties.servicecode;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings checked by a rule on the whole type rather than on one property.
 *
 * @param min the lowest value
 * @param max the highest value
 */
@MinBelowMax
@ConfigurationProperties("stratum.range")
public record RangeSettings(int min, int max) {}
