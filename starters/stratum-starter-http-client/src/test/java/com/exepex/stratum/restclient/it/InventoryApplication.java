package com.exepex.stratum.restclient.it;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.web.service.registry.ImportHttpServices;

/** A service that calls the inventory service through an HTTP interface group. */
@SpringBootConfiguration
@EnableAutoConfiguration
@ImportHttpServices(group = InventoryApplication.INVENTORY, types = InventoryClient.class)
public class InventoryApplication {

  /**
   * The name of the inventory client's group, as in {@code spring.http.serviceclient.inventory}.
   */
  public static final String INVENTORY = "inventory";

  /** The key of the inventory client's address. */
  public static final String INVENTORY_BASE_URL = "spring.http.serviceclient.inventory.base-url";
}
