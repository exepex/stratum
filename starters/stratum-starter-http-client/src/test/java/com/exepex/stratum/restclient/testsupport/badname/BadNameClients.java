package com.exepex.stratum.restclient.testsupport.badname;

import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.context.annotation.Configuration;

/** Makes this package the one Stratum scans for clients. */
@Configuration(proxyBeanMethods = false)
@AutoConfigurationPackage
public class BadNameClients {}
