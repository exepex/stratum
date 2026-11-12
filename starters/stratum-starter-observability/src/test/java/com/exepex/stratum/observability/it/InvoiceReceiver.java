package com.exepex.stratum.observability.it;

import com.exepex.stratum.observability.testsupport.ReceivedLoggingContexts;
import lombok.RequiredArgsConstructor;
import org.springframework.jms.annotation.JmsListener;

/** Handles invoices through the service's own listener factory, as a service's listener would. */
@RequiredArgsConstructor
public class InvoiceReceiver {

  /** Keeps the logging context of each received invoice. */
  private final ReceivedLoggingContexts receivedInvoices;

  /**
   * Handles an invoice from the factory's queue.
   *
   * @param invoice the invoice
   */
  @JmsListener(
      destination = ServiceJmsApplication.FACTORY_QUEUE,
      containerFactory = ServiceJmsApplication.INVOICE_LISTENER_FACTORY)
  void receiveInvoice(String invoice) {
    receivedInvoices.keepCurrent();
  }
}
