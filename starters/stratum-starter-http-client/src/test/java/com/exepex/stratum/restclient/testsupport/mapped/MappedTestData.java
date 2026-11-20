package com.exepex.stratum.restclient.testsupport.mapped;

import com.exepex.stratum.httpclient.HttpClientConstants;
import lombok.experimental.UtilityClass;

/** The names, keys and codes of the tests of a client's own error codes. */
@UtilityClass
public class MappedTestData {

  /** The stock client's name. */
  public static final String STOCK = "stock";

  /** The start of the stock client's settings. */
  public static final String STOCK_SETTINGS = HttpClientConstants.CLIENTS + "." + STOCK;

  /** The stock client's address. */
  public static final String STOCK_URL = STOCK_SETTINGS + ".url";

  /** The stock client's error codes. */
  public static final String STOCK_ERRORS = STOCK_SETTINGS + ".errors";

  /** The shared error codes. */
  public static final String DEFAULT_ERRORS = HttpClientConstants.DEFAULTS + ".errors";

  /** The operation that lists orders. */
  public static final String LIST_ORDERS = "list-orders";

  /** The start of the list-orders operation's settings. */
  public static final String LIST_ORDERS_SETTINGS = STOCK_SETTINGS + ".operations." + LIST_ORDERS;

  /** The list-orders operation's error codes. */
  public static final String LIST_ORDERS_ERRORS = LIST_ORDERS_SETTINGS + ".errors";

  /** The path of the orders, which both methods of the stock client call. */
  public static final String ORDERS_PATH = "/orders";

  /** The service's code for a 409 of the stock client. */
  public static final String CONFLICT_CODE = "ORD-0409";

  /** The shared code for a 409, which the stock client's own code replaces. */
  public static final String SHARED_CONFLICT_CODE = "ORD-9409";

  /** The shared code for a 401, which only the defaults map. */
  public static final String UNAUTHORIZED_CODE = "ORD-0401";

  /** The stock client's code for a 404. */
  public static final String NOT_FOUND_CODE = "ORD-0404";

  /** The list-orders operation's code for a 404, which wins over the client's. */
  public static final String LIST_NOT_FOUND_CODE = "ORD-4040";

  /** The shared code for a 404, which the client's and the operation's replace. */
  public static final String SHARED_NOT_FOUND_CODE = "ORD-9404";

  /** The stock client's code for a 503, which the client still retries. */
  public static final String UNAVAILABLE_CODE = "ORD-0503";

  /** The stock client's code for a 418, which a customizer replaces with its own exception. */
  public static final String TEAPOT_CODE = "ORD-0418";
}
