package com.nip.app.service.richtext;

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
                    .header("User-Agent", "NIP-Remote-Image-Importer/1.0")
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
                throw new IOException("远程图片请求失败，HTTP " + status);
            }

            String contentType = response.headers().firstValue("content-type")
                    .map(value -> value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT))
                    .orElse("");
            String extension = EXTENSIONS.get(contentType);
            if (extension == null) {
                response.body().close();
                throw new IOException("远程资源不是受支持的图片类型");
            }
            long declaredLength = response.headers().firstValueAsLong("content-length").orElse(-1);
            if (declaredLength > MAX_IMAGE_SIZE) {
                response.body().close();
                throw new IOException("远程图片超过 10MB 限制");
            }
            byte[] bytes;
            try (InputStream inputStream = response.body()) {
                bytes = inputStream.readNBytes(MAX_IMAGE_SIZE + 1);
            }
            if (bytes.length == 0 || bytes.length > MAX_IMAGE_SIZE) {
                throw new IOException("远程图片为空或超过 10MB 限制");
            }
            return new ByteArrayMultipartFile(bytes, "remote-image" + extension, contentType);
        }
        throw new IOException("远程图片重定向次数过多");
    }

    private void validateUri(URI uri) throws IOException {
        if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IOException("仅支持不包含用户凭据的 HTTP 图片地址");
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
