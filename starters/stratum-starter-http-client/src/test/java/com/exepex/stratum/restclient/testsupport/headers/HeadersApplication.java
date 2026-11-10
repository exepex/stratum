package com.exepex.stratum.restclient.testsupport.headers;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/** An application whose package holds the signed, plain and lone clients, with the interceptors. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(InterceptorBeans.class)
public class HeadersApplication {}
