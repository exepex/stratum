package com.exepex.stratum.observability;

import com.exepex.stratum.redaction.ContentMaskingRules;
import com.exepex.stratum.redaction.SensitiveFieldNames;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.util.UriUtils;

/**
 * Masks the values of a URL's query string and fragment with the logging starter's built-in rules,
 * one whole parameter at a time: a value is masked in full by its name, such as {@code
 * api_key=abc,def}, or else by what it looks like, such as a card number. Each parameter ends only
 * at {@code &} or {@code #}, so a comma, semicolon, bracket or {@code ?} inside a value never ends
 * it early, as it would in a log line. Names and values are percent-decoded before the rules see
 * them, so {@code to=jane%40example.com} is masked like the email address it is.
 */
final class QueryValueMasker {

  /**
   * One parameter: the text after the query's {@code ?} or a separator {@code &} or {@code #}, up
   * to the next {@code &} or {@code #}.
   */
  private static final Pattern PARAMETER = Pattern.compile("(?<=[?&#])[^&#]++");

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
      return maskedText(parameter, contentRules);
    }
    var name = decoded(parameter.substring(0, nameEnd));
    return parameter.substring(0, nameEnd + 1)
        + maskedText(
            parameter.substring(nameEnd + 1),
            value -> fieldNames.mask(name, () -> value).orElseGet(() -> contentRules.apply(value)));
  }

  /**
   * Returns the text as sent when the rules leave its decoded form unchanged, so ordinary values
   * keep their encoding, or else the masked form encoded again for a query.
   */
  private String maskedText(String text, UnaryOperator<String> rules) {
    var decodedText = decoded(text);
    var maskedText = rules.apply(decodedText);
    return maskedText.equals(decodedText)
        ? text
        : UriUtils.encodeQueryParam(maskedText, StandardCharsets.UTF_8);
  }

  /**
   * Returns the text percent-decoded, or as sent when an escape is broken; Spring's URI builders
   * never write a broken escape, so the rules then see the raw text.
   */
  private String decoded(String text) {
    if (text.indexOf('%') < 0) {
      return text;
    }
    try {
      return UriUtils.decode(text, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException brokenEscape) {
      return text;
    }
  }
}
