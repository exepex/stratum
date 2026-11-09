package com.exepex.stratum.restclient.testsupport.clients;

import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.context.annotation.Configuration;

/** Makes this package the one Stratum scans for clients, in tests without the application. */
@Configuration(proxyBeanMethods = false)
@AutoConfigurationPackage
public class InventoryClients {}
