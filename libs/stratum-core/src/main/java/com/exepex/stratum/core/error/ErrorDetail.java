package com.exepex.stratum.core.error;

import java.io.Serializable;

/**
 * One problem with one field of the input, such as a request body or a command-line option. It is
 * shown outside the application, so the message never quotes the rejected value.
 *
 * @param field the field's name as the caller knows it, such as {@code quantity} or {@code
 *     address.city}
 * @param message what is wrong with the field, such as {@code must be greater than 0}
 */
public record ErrorDetail(String field, String message) implements Serializable {}
