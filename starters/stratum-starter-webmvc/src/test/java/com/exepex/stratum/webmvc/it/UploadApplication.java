package com.exepex.stratum.webmvc.it;

import com.exepex.stratum.web.RequestBodyLimit;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.util.unit.DataUnit;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A service that accepts orders as text, forms, and uploads as a stream. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(UploadApplication.UploadController.class)
public class UploadApplication {

  /** The endpoints, each answering with how many bytes its body had. */
  @RestController
  static class UploadController {

    /**
     * Reads the body as text, as most APIs do.
     *
     * @param order the body
     * @return its length
     */
    @PostMapping("/orders")
    String order(@RequestBody String order) {
      return String.valueOf(order.length());
    }

    /**
     * Reads a larger body as text, with a limit of its own.
     *
     * @param orders the body
     * @return its length
     */
    @RequestBodyLimit(value = 8, unit = DataUnit.KILOBYTES)
    @PostMapping("/orders/imports")
    String importOrders(@RequestBody String orders) {
      return String.valueOf(orders.length());
    }

    /**
     * Reads a form, which the server parses itself, without Stratum's counting wrapper.
     *
     * @param fields the form's fields
     * @return how many fields it had
     */
    @RequestMapping(
        path = "/forms",
        method = {RequestMethod.POST, RequestMethod.PUT})
    String form(@RequestParam Map<String, String> fields) {
      return String.valueOf(fields.size());
    }

    /**
     * Reads the body as a stream, as an upload does.
     *
     * @param upload the body
     * @return its length
     * @throws IOException if the body cannot be read
     */
    @PostMapping("/uploads/raw")
    String upload(InputStream upload) throws IOException {
      return String.valueOf(upload.transferTo(OutputStream.nullOutputStream()));
    }
  }
}
