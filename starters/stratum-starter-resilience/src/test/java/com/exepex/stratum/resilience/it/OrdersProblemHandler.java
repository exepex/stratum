package com.exepex.stratum.resilience.it;

import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** The service's own subclass of Spring's Problem Details handler. */
@RestControllerAdvice
class OrdersProblemHandler extends ResponseEntityExceptionHandler {}
