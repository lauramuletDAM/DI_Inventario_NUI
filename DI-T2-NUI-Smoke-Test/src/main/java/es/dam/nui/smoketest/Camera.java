package es.dam.nui.smoketest;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.function.BooleanSupplier;

import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.javacv.OpenCVFrameGrabber;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Captura de webcam en bucle y conversiones de imagen compartidas por las apps con camara.
 *
 * <pre>{@code
 *   Webcam --OpenCVFrameGrabber.grab()--> Frame (JavaCV)
 *                                           |  Java2DFrameConverter.convert
 *                                           v
 *                                     BufferedImage --copy()--> INT_RGB independiente --> handler.onFrame
 *
 *   BufferedImage --toMat()--> Mat BGR (OpenCV)      para ArUco y Haar
 * }</pre>
 *
 * <p>Sustituye a {@code Cv.stream}: el bucle ya no tiene duracion fija, corre mientras
 * {@code running} sea true (boton Iniciar/Detener) y no depende de la UI ni de estado compartido.
 */
final class Camera {

    private static final int CAMERA_INDEX = 1;

    /** Logica que se aplica a cada fotograma; puede dibujar encima (la imagen es una copia propia). */
    interface FrameHandler {
        void onFrame(BufferedImage frame) throws Exception;
    }

    private Camera() {
    }

    /** Bloquea el hilo actual capturando fotogramas hasta que {@code running} devuelva false. Libera la camara al salir. */
    static void stream(BooleanSupplier running, FrameHandler handler) throws Exception {
        try (OpenCVFrameGrabber grabber = new OpenCVFrameGrabber(CAMERA_INDEX);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            grabber.start();
            while (running.getAsBoolean()) {
                Frame f = grabber.grab();
                if (f == null || f.image == null) {
                    continue;                      // fotograma perdido: se salta
                }
                handler.onFrame(copy(converter.convert(f)));
            }
        }
    }

    /**
     * Copia a un {@code BufferedImage} nuevo de tipo {@code TYPE_INT_RGB}. El convertidor de
     * JavaCV puede devolver una imagen que comparte memoria con el {@code Frame} que el grabber
     * reutiliza; copiar la hace independiente y permite dibujar con {@code Graphics2D}.
     */
    static BufferedImage copy(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    /**
     * BufferedImage -> Mat (BGR), copia independiente. OpenCV usa BGR: {@code flipChannels=true}
     * pasa de RGB a BGR. El {@code clone()} es necesario porque el Mat del convertidor es una
     * vista sobre su memoria interna, que se libera al cerrar el try-with-resources.
     */
    static Mat toMat(BufferedImage image) {
        try (Java2DFrameConverter j = new Java2DFrameConverter();
             OpenCVFrameConverter.ToMat m = new OpenCVFrameConverter.ToMat()) {
            return m.convert(j.getFrame(image, 1.0, true)).clone();
        }
    }
}
