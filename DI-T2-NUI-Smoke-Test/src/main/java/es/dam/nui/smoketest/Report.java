package es.dam.nui.smoketest;

import java.util.Optional;

/**
 * Resultado de la comprobacion automatica de una app y, a la vez, el protocolo con el que la app
 * se lo comunica al launcher: en modo {@code --check} escribe UNA linea con este formato, y el
 * launcher la busca en la salida del proceso (puede haber ruido de OpenCV, Vosk, DJL...).
 *
 * <pre>{@code
 *   RESULT OK - Red: HTTP 200 (...) | Camara: 640x480 | 3D: soportado
 *   RESULT FALLO - Red: FALLO (java.net.ConnectException) | Camara: 640x480
 * }</pre>
 *
 * <p>Sin dependencias de JavaFX: la usan tanto las apps como {@link AppRunner}.
 */
record Report(boolean ok, String summary) {

    static final String PREFIX = "RESULT ";
    private static final String OK = "OK - ";
    private static final String FAIL = "FALLO - ";

    /** Texto para mostrar en la UI: "OK - ..." o "FALLO - ...". */
    String text() {
        return (ok ? OK : FAIL) + summary;
    }

    /** Linea del protocolo; el resumen va en una sola linea. */
    String toLine() {
        return PREFIX + (ok ? OK : FAIL) + summary.replaceAll("\\s+", " ");
    }

    /** Interpreta una linea de la salida del proceso; vacio si no es una linea del protocolo. */
    static Optional<Report> fromLine(String line) {
        if (!line.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String body = line.substring(PREFIX.length());
        if (body.startsWith(OK)) {
            return Optional.of(new Report(true, body.substring(OK.length())));
        }
        if (body.startsWith(FAIL)) {
            return Optional.of(new Report(false, body.substring(FAIL.length())));
        }
        return Optional.empty();
    }
}
