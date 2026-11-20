package com.exepex.stratum.restclient.testsupport.mapped;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * An application whose package holds only the stock client, so that the other tests' applications
 * need no address for it.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class MappedApplication {}
