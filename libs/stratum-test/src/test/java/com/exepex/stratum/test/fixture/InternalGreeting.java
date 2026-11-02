package com.exepex.stratum.test.fixture;

import com.exepex.stratum.core.api.StratumInternal;

/**
 * A type only the made-up starter itself may use.
 *
 * @param text the greeting
 */
@StratumInternal
public record InternalGreeting(String text) {}
