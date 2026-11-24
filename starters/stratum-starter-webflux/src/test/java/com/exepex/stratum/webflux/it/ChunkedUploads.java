package com.exepex.stratum.webflux.it;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.experimental.UtilityClass;
import org.springframework.http.HttpHeaders;

/** Sends multipart uploads to the test application's {@code /files} endpoint. */
@UtilityClass
class ChunkedUploads {

  /** The boundary between the parts of an upload. */
  private static final String BOUNDARY = "stratum-upload";

  /**
   * Sends a multipart upload to {@code /files} in chunks, with no {@code Content-Length}, so that
   * only Spring's multipart limits apply. Each part has its own name, so the endpoint counts each.
   *
   * @param port the application's port
   * @param partCount how many parts the upload has
   * @param bytesPerPart how many bytes each part holds
   * @return the response, with its body as text
   * @throws IOException if the request cannot be sent or the response read
   * @throws InterruptedException if the thread is interrupted while it waits
   */
  HttpResponse<String> postMultipartInChunks(int port, int partCount, int bytesPerPart)
      throws IOException, InterruptedException {
    var parts =
        IntStream.range(0, partCount)
            .mapToObj(index -> partOf(index, bytesPerPart))
            .collect(Collectors.joining());
    var multipart = (parts + "--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII);
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/files"))
            .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + BOUNDARY)
            .POST(
                HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(multipart)))
            .build();
    try (var client = HttpClient.newHttpClient()) {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }

  /** Returns one part of an upload, named after its index. */
  private String partOf(int index, int bytesPerPart) {
    return "--"
        + BOUNDARY
        + "\r\n"
        + "Content-Disposition: form-data; name=\"file"
        + index
        + "\"; filename=\"orders.csv\"\r\n"
        + "Content-Type: text/plain\r\n\r\n"
        + "x".repeat(bytesPerPart)
        + "\r\n";
  }
}
