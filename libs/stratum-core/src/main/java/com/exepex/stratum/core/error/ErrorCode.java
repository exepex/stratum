package com.exepex.stratum.core.error;

import java.io.Serializable;

/**
 * A stable code with a fixed severity and description, which names one kind of error or warning so
 * that it can be looked up in the documentation and searched for in logs.
 *
 * <p>Each module declares its codes, errors and warnings together, in one enum that implements this
 * interface, so that no configuration file can change them. A service does the same with its own
 * prefix, such as {@code PAY-0001}. The prefix {@code SX-} is reserved for Stratum: a report made
 * from a code with that prefix declared outside Stratum is rejected. It is {@link Serializable}, as
 * every enum is, so that a {@link StratumException} that holds it can be serialized.
 */
public interface ErrorCode extends Serializable {

  /**
   * The code as it appears in logs and responses, such as {@code SX-CORE-1002} or a service's own
   * {@code PAY-0001}.
   *
   * @return the code
   */
  String getCode();

  /**
   * Whether a report with this code means the operation failed or only needs attention.
   *
   * @return the severity
   */
  Severity getSeverity();

  /**
   * What went wrong, in fixed text with no placeholders. The place that reports the problem can add
   * one detail, such as the name of the class at fault, after it.
   *
   * @return the description
   */
  String getDescription();
}
