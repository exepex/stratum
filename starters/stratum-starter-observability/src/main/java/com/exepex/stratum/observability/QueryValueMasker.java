package com.exepex.stratum.observability;

import com.exepex.stratum.redaction.ContentMaskingRules;
import com.exepex.stratum.redaction.SensitiveFieldNames;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks the values of a URL's query string and fragment with the logging starter's built-in rules,
 * one whole parameter at a time: a value is masked in full by its name, such as {@code
 * api_key=abc,def}, or else by what it looks like, such as a card number. Each parameter ends only
 * at {@code &} or {@code #}, so a comma, semicolon or bracket inside a value never ends it early,
 * as it would in a log line.
 */
final class QueryValueMasker {

  /** One parameter: the text between the query's separators {@code ?}, {@code &} and {@code #}. */
  private static final Pattern PARAMETER = Pattern.compile("[^?&#]++");

  /** The built-in names, with no names of the service's own. */
  private final SensitiveFieldNames fieldNames = new SensitiveFieldNames(List.of());

  /** The built-in rules that mask a value by what it looks like. */
  private final UnaryOperator<String> contentRules = ContentMaskingRules.builtInRules(fieldNames);

  /**
   * Returns the query string and fragment with each parameter's value masked.
   *
   * @param queryAndFragment the URL from its {@code ?} or {@code #}, or an empty text
   * @return the same text with the values masked
   */
  String mask(String queryAndFragment) {
    return PARAMETER
        .matcher(queryAndFragment)
        .replaceAll(parameter -> Matcher.quoteReplacement(maskedParameter(parameter.group())));
  }

  /**
   * Returns one parameter, such as {@code token=abc}, with its value masked by its name or by what
   * it looks like; a parameter with no {@code =} is masked by what it looks like.
   */
  private String maskedParameter(String parameter) {
    var nameEnd = parameter.indexOf('=');
    if (nameEnd < 0) {
      return contentRules.apply(parameter);
    }
    var name = parameter.substring(0, nameEnd);
    var value = parameter.substring(nameEnd + 1);
    return name
        + "="
        + fieldNames.mask(name, () -> value).orElseGet(() -> contentRules.apply(value));
  }
}
