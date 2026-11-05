package com.exepex.stratum.cache.testsupport.values;

import com.exepex.stratum.cache.CacheJsonValues;
import com.exepex.stratum.cache.CachedJson;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.experimental.UtilityClass;

/** The values, packages and stored JSON that the JSON value tests share. */
@UtilityClass
public class CachedValuesTestData {

  /** The package whose types the tests allow: this one. */
  public static final String ALLOWED_PACKAGE = CachedValuesTestData.class.getPackageName();

  /** The gadget class of the package that is not allowed. */
  public static final String GADGET_CLASS = ALLOWED_PACKAGE + "evil.Gadget";

  /** The identifier of every test order. */
  public static final String ORDER_ID = "order-1";

  /** An order. */
  public static final Order ORDER = new Order(ORDER_ID, 3);

  /** The order as a fresh load returns it, which differs from the stored one. */
  public static final Order LOADED_ORDER = new Order(ORDER_ID, 5);

  /** The order with the field a later release added. */
  public static final OrderWithPriority ORDER_WITH_PRIORITY = new OrderWithPriority(ORDER_ID, 3, 9);

  /** The stored order as the later release reads it: the added field at its default. */
  public static final OrderWithPriority ORDER_READ_LATER = new OrderWithPriority(ORDER_ID, 3, 0);

  /** A count, as a method returns it boxed. */
  public static final Long COUNT = 42L;

  /** A quantity, as a method returns it as a primitive. */
  public static final Integer QUANTITY = 7;

  /** How deep the hostile JSON nests: past Jackson's limit of 500. */
  public static final int NESTING_PAST_LIMIT = 1_000;

  /** An order with a card payment, whose JSON names the payment's class. */
  public static final PaidOrder PAID_ORDER = new PaidOrder("order-2", new CardPayment("4242"));

  /** An order whose amounts' JSON names a {@code java} number class. */
  public static final MeasuredOrder MEASURED_ORDER =
      new MeasuredOrder("order-3", List.of(new BigDecimal("1.5")));

  /**
   * Returns the JSON values that allow only this package beside {@code java}.
   *
   * @return the JSON values
   */
  public static CacheJsonValues jsonValues() {
    return new CacheJsonValues(List.of(ALLOWED_PACKAGE));
  }

  /**
   * Returns stored JSON with the given text, as Redis would hand it back.
   *
   * @param json the JSON text
   * @return the stored value
   */
  public static CachedJson stored(String json) {
    return new CachedJson(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * Returns JSON arrays nested past Jackson's depth limit.
   *
   * @return the stored value
   */
  public static CachedJson nestedPastDepthLimit() {
    return stored("[".repeat(NESTING_PAST_LIMIT) + "]".repeat(NESTING_PAST_LIMIT));
  }

  /**
   * Returns the stored JSON of the paid order, with its payment naming the gadget class of the
   * package that is not allowed, whose name starts like the allowed one.
   *
   * @return the stored value
   */
  public static CachedJson paidWithGadget() {
    var json = new String(jsonValues().write(PAID_ORDER).array(), StandardCharsets.UTF_8);
    return stored(json.replace(CardPayment.class.getName(), GADGET_CLASS));
  }
}
