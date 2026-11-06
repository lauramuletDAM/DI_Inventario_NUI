package es.dam.nui.smoketest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/** Descarga de ficheros con cache en disco (carpeta ./models). */
final class Downloads {

    private static final int HTTP_OK = 200;

    private Downloads() {
    }

    /**
     * Descarga {@code url} en {@code target} solo si todavia no existe. {@code followRedirects}
     * es necesario porque GitHub raw y Hugging Face redirigen a un CDN; si el estado no es 200
     * se borra el fichero.
     *
     * <p>Limitacion: no hay verificacion de integridad; una descarga cortada por un error de red
     * podria dejar un fichero parcial que la siguiente ejecucion daria por bueno.
     */
    static Path ensureFile(String url, Path target) throws Exception {
        if (Files.exists(target)) {
            return target;
        }
        Files.createDirectories(target.getParent());
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<Path> response = client.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() != HTTP_OK) {
            Files.deleteIfExists(target);
            throw new IllegalStateException("Descarga fallida: HTTP " + response.statusCode());
        }
        return target;
    }
}
