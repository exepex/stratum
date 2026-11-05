package com.exepex.stratum.cache.testsupport.values;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** A payment whose JSON names its class, as a team's polymorphic field would. */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
public interface Payment {}
