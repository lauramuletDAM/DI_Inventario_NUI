package tecnodam.inventario;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.function.BooleanSupplier;

import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.javacv.OpenCVFrameGrabber;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Captura de webcam en bucle y conversiones de imagen compartidas.
 * Adaptado desde el smoke test de TecnoDAM.
 */
final class Camera {

    private static final int CAMERA_INDEX = 0; // Cambia a 1 si usas una cámara externa/secundaria

    /** Lógica que se aplica a cada fotograma. */
    interface FrameHandler {
        void onFrame(BufferedImage frame) throws Exception;
    }

    private Camera() {
    }

    /** Bloquea el hilo actual capturando fotogramas hasta que {@code running} devuelva false. */
    static void stream(BooleanSupplier running, FrameHandler handler) throws Exception {
        try (OpenCVFrameGrabber grabber = new OpenCVFrameGrabber(CAMERA_INDEX);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            grabber.start();
            while (running.getAsBoolean()) {
                Frame f = grabber.grab();
                if (f == null || f.image == null) {
                    continue; // fotograma perdido: se salta
                }
                handler.onFrame(copy(converter.convert(f)));
            }
        }
    }

    /** Copia a un BufferedImage nuevo de tipo TYPE_INT_RGB para hacerlo independiente. */
    static BufferedImage copy(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    /** BufferedImage -> Mat (BGR), copia independiente para OpenCV. */
    static Mat toMat(BufferedImage image) {
        try (Java2DFrameConverter j = new Java2DFrameConverter();
             OpenCVFrameConverter.ToMat m = new OpenCVFrameConverter.ToMat()) {
            return m.convert(j.getFrame(image, 1.0, true)).clone();
        }
    }
}
