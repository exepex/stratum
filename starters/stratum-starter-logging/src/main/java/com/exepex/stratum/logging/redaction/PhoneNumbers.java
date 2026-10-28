package com.exepex.stratum.logging.redaction;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.Leniency;
import lombok.experimental.UtilityClass;

/**
 * Masks international phone numbers in free text, keeping the last four digits. Only numbers with a
 * country code, such as {@code +44 20 7946 0958}, are found, so ordinary numbers and IDs stay.
 */
@UtilityClass
class PhoneNumbers {

  /** Google's phone number library, which knows every country's numbering plan. */
  private static final PhoneNumberUtil PHONE_NUMBERS = PhoneNumberUtil.getInstance();

  /**
   * Masks every phone number in the text.
   *
   * @param text the text to mask
   * @return the text with each number masked
   */
  String mask(String text) {
    // A number with a country code starts with '+'; other text skips the costly search.
    if (text.indexOf('+') < 0) {
      return text;
    }
    var masked = new StringBuilder(text.length());
    var end = 0;
    for (var number : PHONE_NUMBERS.findNumbers(text, null, Leniency.VALID, Long.MAX_VALUE)) {
      masked.append(text, end, number.start()).append(Masks.keepingLastFour(number.rawString()));
      end = number.end();
    }
    return masked.append(text, end, text.length()).toString();
  }
}
