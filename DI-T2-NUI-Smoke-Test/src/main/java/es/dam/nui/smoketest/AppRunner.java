package es.dam.nui.smoketest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Lanza cada app en un PROCESO (JVM) propio. Sin dependencias de JavaFX.
 *
 * <pre>{@code
 *   LauncherApp --open(key)-----------> java ... Main <key>            ventana interactiva
 *   LauncherApp --check(key, plazo)---> java ... Main <key> --check    sin ventana
 *                    ^                        | stdout + stderr
 *                    |                        v
 *                    +--- Report <---- logs/<key>-check.log   (se busca la linea "RESULT ...")
 * }</pre>
 *
 * <p>El proceso hijo recibe el MISMO classpath que el padre ({@code java.class.path}); por eso el
 * proyecto debe ejecutarse con todo en el classpath (Eclipse lo hace; con Maven, via
 * {@code runtimePathOption=CLASSPATH} en el pom). Un proceso por app tambien aisla las librerias
 * nativas (OpenCV, ONNX, libtorch) y libera camara y microfono al cerrar la app.
 */
final class AppRunner {

    private static final String MAIN_CLASS = "es.dam.nui.smoketest.Main";
    private static final String NATIVE_ACCESS = "--enable-native-access=ALL-UNNAMED";
    private static final Path LOG_DIR = Path.of("logs");

    private AppRunner() {
    }

    /** Abre la app con su ventana interactiva. No espera a que termine. */
    static void open(String key) throws IOException {
        command(key).inheritIO().start();
    }

    /** Ejecuta la app en modo {@code --check} y espera su informe, como maximo {@code timeoutSeconds}. */
    static Report check(String key, long timeoutSeconds) {
        try {
            Files.createDirectories(LOG_DIR);
            Path log = LOG_DIR.resolve(key + "-check.log");
            Process process = command(key, "--check")
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Report(false, "sin respuesta en " + timeoutSeconds + " s (ver " + log + ")");
            }
            return lastReport(log)
                    .orElse(new Report(false, "sin informe, codigo de salida " + process.exitValue() + " (ver " + log + ")"));
        } catch (IOException e) {
            return new Report(false, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Report(false, "interrumpido");
        }
    }

    private static ProcessBuilder command(String key, String... extraArgs) {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                NATIVE_ACCESS,
                "-cp", System.getProperty("java.class.path"),
                MAIN_CLASS, key));
        command.addAll(List.of(extraArgs));
        return new ProcessBuilder(command);
    }

    /** Ultima linea del protocolo del fichero. ISO-8859-1 nunca falla al decodificar, aunque haya ruido nativo. */
    private static Optional<Report> lastReport(Path log) throws IOException {
        Report last = null;
        for (String line : Files.readAllLines(log, StandardCharsets.ISO_8859_1)) {
            last = Report.fromLine(line).orElse(last);
        }
        return Optional.ofNullable(last);
    }
}
