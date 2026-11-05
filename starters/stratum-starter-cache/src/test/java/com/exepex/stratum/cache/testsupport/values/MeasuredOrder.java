package com.exepex.stratum.cache.testsupport.values;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * An order whose amounts name their {@code java} number classes in the JSON.
 *
 * @param id the order's identifier
 * @param amounts the amounts, each with its class named
 */
public record MeasuredOrder(
    String id, @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS) List<Number> amounts) {}
