package com.exepex.stratum.core.error;

import org.slf4j.helpers.MessageFormatter;

/**
 * A stable code with a fixed description, which names one kind of error or warning so that it can
 * be looked up in the documentation and searched for in logs. A code is either an {@link ErrorCode}
 * or a {@link WarningCode}; its kind decides the {@link Severity} of every report made from it.
 *
 * <p>Codes and descriptions are constants in Java, declared in one enum per module, so that no
 * configuration file can change them.
 */
public sealed interface MessageCode permits ErrorCode, WarningCode {

  /**
   * The code as it appears in logs and responses, such as {@code SX-CORE-1002} or a service's own
   * {@code PAY-0001}. Codes starting with {@code SX-} belong to Stratum.
   *
   * @return the code
   */
  String getCode();

  /**
   * What went wrong and how to fix it. Each {@code {}} in it is a placeholder, filled in order when
   * a report is made, as in a log message.
   *
   * @return the description
   */
  String getDescription();

  /**
   * Returns the description with its placeholders filled in. The text can be shown outside the
   * application, so never pass a secret, personal data or an internal detail as an argument.
   *
   * @implNote The placeholders are SLF4J's {@code {}}, not {@link String#format} patterns, so an
   *     argument is only ever inserted as text and never read as part of the pattern.
   * @param arguments the values for the description's placeholders, in order
   * @return the filled-in description
   */
  default String describe(Object... arguments) {
    return MessageFormatter.basicArrayFormat(getDescription(), arguments);
  }
}
