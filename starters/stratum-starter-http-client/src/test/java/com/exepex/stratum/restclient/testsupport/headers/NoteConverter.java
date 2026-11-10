package com.exepex.stratum.restclient.testsupport.headers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;

/**
 * Writes a {@link Note} straight to the request's body stream, as a converter that does not extend
 * Spring's base classes does, instead of handing a body to stream.
 */
public class NoteConverter implements HttpMessageConverter<Note> {

  @Override
  public boolean canRead(Class<?> type, @Nullable MediaType mediaType) {
    return false;
  }

  @Override
  public boolean canWrite(Class<?> type, @Nullable MediaType mediaType) {
    return Note.class == type;
  }

  @Override
  public List<MediaType> getSupportedMediaTypes() {
    return List.of(MediaType.TEXT_PLAIN);
  }

  @Override
  public Note read(Class<? extends Note> type, HttpInputMessage message) {
    throw new HttpMessageNotReadableException("A note is never read", message);
  }

  @Override
  public void write(Note note, @Nullable MediaType contentType, HttpOutputMessage message)
      throws IOException {
    message.getHeaders().setContentType(MediaType.TEXT_PLAIN);
    message.getBody().write(note.text().getBytes(StandardCharsets.UTF_8));
  }
}
