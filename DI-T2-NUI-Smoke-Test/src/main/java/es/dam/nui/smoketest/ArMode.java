package es.dam.nui.smoketest;

import java.awt.image.BufferedImage;

/**
 * Un modo de la app de marcadores y realidad aumentada: sabe comprobarse solo y sabe procesar un
 * fotograma en directo.
 *
 * <pre>{@code
 *   MarcadoresApp
 *     |-- --check:  para cada modo   selfTest()               (sin camara ni persona)
 *     '-- en vivo:   Camera.stream --> process(frame)          (dibuja encima del fotograma)
 *
 *   QrMode      QR como ancla                      ZXing
 *   ArucoMode   marcador fiducial ArUco            OpenCV objdetect
 *   FaceMode    rostro + sombrero                  OpenCV Haar
 *   CubeMode    cubo 3D (anclado al ArUco)         JavaFX 3D
 * }</pre>
 *
 * <p>Los modos que guardan recursos nativos (clasificador, detector) los crean la primera vez que
 * se usan y los liberan en {@link #close()}.
 */
interface ArMode extends AutoCloseable {

    /** Autotest automatico: devuelve un resumen corto o lanza excepcion. */
    String selfTest() throws Exception;

    /** Dibuja sobre el fotograma lo detectado; devuelve true si hubo deteccion. */
    boolean process(BufferedImage frame) throws Exception;

    /** Que debe hacer el usuario delante de la camara. */
    String hint();

    /** Prepara lo necesario antes de empezar en directo (p. ej. guardar el marcador para ensenarlo). */
    default void prepareLive() throws Exception {
    }

    @Override
    default void close() {
    }
}
