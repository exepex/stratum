package com.exepex.stratum.core.error;

/**
 * A code for a warning: the operation completed, but something needs attention. Reports made from
 * it have the severity {@link Severity#WARNING}.
 *
 * <p>A service declares its own codes in an enum that implements this interface, with its own
 * prefix. The prefix {@code SX-} is reserved for Stratum, as for {@link ErrorCode}.
 */
public non-sealed interface WarningCode extends MessageCode {}
