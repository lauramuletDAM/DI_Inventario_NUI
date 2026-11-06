package es.dam.nui.smoketest;

import java.awt.image.BufferedImage;

/**
 * Un modo de una app con camara: sabe comprobarse solo y sabe procesar un fotograma en directo.
 * Lo implementan las apps {@link MarcadoresApp} y {@link GestosApp} (ambas heredan de
 * {@link LiveApp}, que aporta la ventana y el bucle).
 *
 * <pre>{@code
 *   LiveApp
 *     |-- --check:  para cada modo   selfTest()                              (sin camara ni persona)
 *     '-- en vivo:   prepareLive() -> Camera.stream: process(frame) ... -> endLive()
 *
 *   Marcadores y RA:  QrMode  ArucoMode  FaceMode  CubeMode
 *   Manos y cuerpo:   HandMode  DwellMode  SwipeMode  PoseMode  PickingMode
 * }</pre>
 *
 * <p>Los modos que guardan recursos nativos (clasificador, detector, modelo ONNX) los crean la
 * primera vez que se usan y los liberan en {@link #close()}.
 */
interface LiveMode extends AutoCloseable {

    /** Autotest automatico: devuelve un resumen corto o lanza excepcion. */
    String selfTest() throws Exception;

    /** Dibuja sobre el fotograma lo detectado; devuelve true si hubo deteccion. */
    boolean process(BufferedImage frame) throws Exception;

    /** Que debe hacer el usuario delante de la camara. */
    String hint();

    /** Prepara lo necesario antes de empezar en directo (guardar el marcador, abrir una ventana, reiniciar contadores). */
    default void prepareLive() throws Exception {
    }

    /** Se llama siempre al terminar el directo, aunque falle (cerrar ventanas auxiliares). */
    default void endLive() throws Exception {
    }

    @Override
    default void close() {
    }
}
