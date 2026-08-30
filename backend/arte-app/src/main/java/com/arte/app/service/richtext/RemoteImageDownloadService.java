package com.arte.app.service.richtext;

import com.arte.core.i18n.MessageUtils;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

@Service
public class RemoteImageDownloadService {

    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_IMAGE_SIZE = 50 * 1024 * 1024;
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/png", ".png",
            "image/jpeg", ".jpg",
            "image/gif", ".gif",
            "image/webp", ".webp",
            "image/svg+xml", ".svg"
    );

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public MultipartFile download(String sourceUrl) throws IOException, InterruptedException {
        URI uri = URI.create(sourceUrl);
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            validateUri(uri);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "ARTE-Remote-Image-Importer/1.0")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                response.body().close();
                String location = response.headers().firstValue("location")
                        .orElseThrow(() -> new IOException("远程图片重定向缺少 Location"));
                uri = uri.resolve(location);
                continue;
            }
            if (status < 200 || status >= 300) {
                response.body().close();
                throw new IOException(MessageUtils.get("error.image.requestFailed", status));
            }

            String contentType = response.headers().firstValue("content-type")
                    .map(value -> value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT))
                    .orElse("");
            String extension = EXTENSIONS.get(contentType);
            if (extension == null) {
                response.body().close();
                throw new IOException(MessageUtils.get("error.image.unsupportedType"));
            }
            long declaredLength = response.headers().firstValueAsLong("content-length").orElse(-1);
            if (declaredLength > MAX_IMAGE_SIZE) {
                response.body().close();
                throw new IOException(MessageUtils.get("error.image.overSize"));
            }
            byte[] bytes;
            try (InputStream inputStream = response.body()) {
                bytes = inputStream.readNBytes(MAX_IMAGE_SIZE + 1);
            }
            if (bytes.length == 0 || bytes.length > MAX_IMAGE_SIZE) {
                throw new IOException(MessageUtils.get("error.image.emptyOrOverSize"));
            }
            return new ByteArrayMultipartFile(bytes, "remote-image" + extension, contentType);
        }
        throw new IOException(MessageUtils.get("error.image.tooManyRedirects"));
    }

    private void validateUri(URI uri) throws IOException {
        if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IOException(MessageUtils.get("error.image.onlyHttpNoCredentials"));
        }
    }

    private static class ByteArrayMultipartFile implements MultipartFile {
        private final byte[] bytes;
        private final String filename;
        private final String contentType;

        private ByteArrayMultipartFile(byte[] bytes, String filename, String contentType) {
            this.bytes = bytes;
            this.filename = filename;
            this.contentType = contentType;
        }

        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return filename; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes.clone(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File dest) throws IOException { Files.write(dest.toPath(), bytes); }
        @Override public void transferTo(Path dest) throws IOException { Files.write(dest, bytes); }
    }
}
