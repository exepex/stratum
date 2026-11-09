package com.exepex.stratum.restclient;

/**
 * Which interface method a mocked call came from, put on the request as {@link
 * MockAnswers#METHOD_ATTRIBUTE} so that the fake request finds its answer.
 *
 * @param method the method's name
 * @param bodyless whether its return type reads no body, such as {@code void} or {@code
 *     HttpHeaders}, so that it needs no answer file
 */
record MockCall(String method, boolean bodyless) {}
