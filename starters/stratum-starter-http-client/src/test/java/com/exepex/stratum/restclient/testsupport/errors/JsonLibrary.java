package com.exepex.stratum.restclient.testsupport.errors;

import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.GsonHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

/** The JSON libraries a client can read bodies with: Jackson 3, the default, Jackson 2 or Gson. */
public enum JsonLibrary {

  /** Jackson 2, whose read failures extend {@link java.io.IOException}. */
  JACKSON_2,

  /** Jackson 3, Spring Boot's default, whose read failures are unchecked. */
  JACKSON_3,

  /** Gson, which wraps every read failure, the network's included, in its own unchecked one. */
  GSON;

  /**
   * Returns a new JSON converter of this library, as a client is given it.
   *
   * @return the converter
   */
  public HttpMessageConverter<Object> converter() {
    return switch (this) {
      case JACKSON_2 -> new MappingJackson2HttpMessageConverter();
      case JACKSON_3 -> new JacksonJsonHttpMessageConverter();
      case GSON -> new GsonHttpMessageConverter();
    };
  }
}
