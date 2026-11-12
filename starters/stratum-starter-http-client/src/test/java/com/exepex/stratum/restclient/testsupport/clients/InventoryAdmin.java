package com.exepex.stratum.restclient.testsupport.clients;

/**
 * An interface that extends the inventory client without {@code @StratumClient} of its own, so
 * Stratum must not make it a client.
 */
public interface InventoryAdmin extends InventoryClient {}
