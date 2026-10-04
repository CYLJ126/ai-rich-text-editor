package com.arte.app.web.aspect;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class WebAspectTest {

    private final WebAspect aspect = new WebAspect();

    @Test
    public void shouldSummarizeMultipartMetadataWithoutReadingContent() {
        MultipartFile file = new MultipartFileWithUnreadableContent();

        String summary = summarizeValue(file);

        assertEquals(
                "MultipartFile{name=file, originalFilename=image.png, contentType=image/png, size=1024}",
                summary);
    }

    @Test
    public void shouldIgnoreBinaryAndStreamValues() {
        assertEquals("", summarizeValue((Object) new byte[]{1, 2, 3}));
        assertEquals("", summarizeValue(new ByteArrayInputStream(new byte[]{1, 2, 3})));
        assertEquals("", summarizeValue(new ByteArrayResource(new byte[]{1, 2, 3})));
    }

    @Test
    public void shouldIgnoreUnserializableValueAndKeepOtherArguments() {
        assertEquals("", summarizeValue(new BeanWithFailingGetter()));

        String summary = ReflectionTestUtils.invokeMethod(
                aspect, "summarizeArguments", (Object) new Object[]{new BeanWithFailingGetter(), "visible"});

        assertEquals("[visible]", summary);
    }

    @Test
    public void shouldNotSerializeBinaryResponseBody() {
        ResponseEntity<ByteArrayResource> response = ResponseEntity.ok(
                new ByteArrayResource(new byte[]{1, 2, 3}));

        String summary = ReflectionTestUtils.invokeMethod(aspect, "summarizeResult", response);

        assertEquals("ResponseEntity{status=200 OK}", summary);
    }

    private String summarizeValue(Object value) {
        return ReflectionTestUtils.invokeMethod(aspect, "summarizeValue", value);
    }

    public static class BeanWithFailingGetter {

        private final String value = "value";
        private final String name = "readable";

        public String getValue() {
            throw new IllegalStateException("cannot read value");
        }

        public String getName() {
            return name;
        }
    }

    private static class MultipartFileWithUnreadableContent implements MultipartFile {

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return "image.png";
        }

        @Override
        public String getContentType() {
            return "image/png";
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public long getSize() {
            return 1024;
        }

        @Override
        public byte[] getBytes() throws IOException {
            throw new IOException("content must not be read");
        }

        @Override
        public InputStream getInputStream() throws IOException {
            throw new IOException("content must not be read");
        }

        @Override
        public void transferTo(File dest) throws IOException, IllegalStateException {
            throw new IOException("content must not be transferred");
        }
    }
}
