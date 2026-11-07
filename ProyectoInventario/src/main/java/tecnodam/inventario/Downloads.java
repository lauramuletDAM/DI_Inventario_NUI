package tecnodam.inventario;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

final class Downloads {

    private static final int HTTP_OK = 200;

    private Downloads() {}

    static Path ensureFile(String url, Path target) throws Exception {
        if (Files.exists(target)) {
            return target;
        }
        Files.createDirectories(target.getParent() != null ? target.getParent() : target);
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<Path> response = client.send(
            HttpRequest.newBuilder(URI.create(url)).build(),
            HttpResponse.BodyHandlers.ofFile(target)
        );
        if (response.statusCode() != HTTP_OK) {
            Files.deleteIfExists(target);
            throw new IllegalStateException("Descarga fallida: HTTP " + response.statusCode());
        }
        return target;
    }
}