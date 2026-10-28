package com.exepex.stratum.core.error;

/**
 * A code for a failure: the operation did not do what was asked. Reports made from it have the
 * severity {@link Severity#ERROR}, and only these codes can be thrown as a {@link
 * StratumException}.
 *
 * <p>A service declares its own codes in an enum that implements this interface, with its own
 * prefix, such as {@code PAY-0001}. The prefix {@code SX-} is reserved for Stratum: a report made
 * from a code with that prefix declared outside Stratum is rejected.
 */
public non-sealed interface ErrorCode extends MessageCode {}
