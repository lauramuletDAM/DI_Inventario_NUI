package es.dam.nui.smoketest;

import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.equalizeHist;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import org.bytedeco.javacpp.PointerScope;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.RectVector;
import org.bytedeco.opencv.opencv_objdetect.CascadeClassifier;

/**
 * Deteccion de rostros con un clasificador en cascada Haar (OpenCV) y un "sombrero" dibujado como
 * RA. No usa marcador fisico: el ancla es la cara.
 *
 * <pre>{@code
 *   Frame --toMat--> BGR --cvtColor--> gris --equalizeHist--> gris con contraste uniforme
 *                                                                 |  detectMultiScale (ventana deslizante, varias escalas)
 *                                                                 v
 *                                                        RectVector (x, y, ancho, alto)
 *
 *   Sombrero:        /\      punta = (x + w/2, y - h/2)
 *                   /  \
 *             +----------+   base  = borde superior del rostro
 *             |  rostro  |
 *             +----------+
 * }</pre>
 *
 * <p>Viola-Jones: caracteristicas Haar (diferencias de brillo entre rectangulos, calculadas con una
 * imagen integral) y una cascada AdaBoost que descarta rapido lo que no es rostro. Rapido y sin
 * GPU, pero solo rostros frontales y sensible a la luz. El modelo
 * {@code haarcascade_frontalface_default.xml} (~0,9 MB) se descarga a ./models la primera vez.
 *
 * <p>El clasificador se carga la primera vez fuera de cualquier {@code PointerScope}, porque debe
 * sobrevivir a los fotogramas.
 */
final class FaceMode implements LiveMode {

    private static final String XML_URL =
            "https://raw.githubusercontent.com/opencv/opencv/4.x/data/haarcascades/haarcascade_frontalface_default.xml";
    private static final Path XML_FILE = Path.of("models", "haarcascade_frontalface_default.xml");
    private static final int STROKE_WIDTH = 3;
    private static final int HAT_HEIGHT_DIVISOR = 2;               // sombrero = mitad del alto del rostro
    private static final Color HAT = new Color(160, 20, 20);

    private CascadeClassifier cascade;

    @Override
    public String selfTest() throws Exception {
        cascade();
        return "clasificador Haar cargado";
    }

    @Override
    public boolean process(BufferedImage frame) throws Exception {
        CascadeClassifier classifier = cascade();                 // fuera del scope
        try (var _ = new PointerScope()) {
            Mat gray = new Mat();
            cvtColor(Camera.toMat(frame), gray, COLOR_BGR2GRAY);  // las cascadas Haar trabajan en grises
            equalizeHist(gray, gray);                             // mejora el contraste con luz irregular
            RectVector faces = new RectVector();
            classifier.detectMultiScale(gray, faces);             // parametros por defecto de OpenCV
            Graphics2D g = frame.createGraphics();
            g.setStroke(new BasicStroke(STROKE_WIDTH));
            for (long i = 0; i < faces.size(); i++) {
                Rect r = faces.get(i);
                g.setColor(Color.GREEN);
                g.drawRect(r.x(), r.y(), r.width(), r.height());
                g.setColor(HAT);
                int hatHeight = r.height() / HAT_HEIGHT_DIVISOR;
                g.fillPolygon(new int[] {r.x(), r.x() + r.width(), r.x() + r.width() / 2},
                        new int[] {r.y(), r.y(), r.y() - hatHeight}, 3);
            }
            g.dispose();
            return faces.size() > 0;
        }
    }

    @Override
    public String hint() {
        return "Ponte frente a la camara";
    }

    private synchronized CascadeClassifier cascade() throws Exception {
        if (cascade == null) {
            Path xml = Downloads.ensureFile(XML_URL, XML_FILE);
            CascadeClassifier loaded = new CascadeClassifier(xml.toAbsolutePath().toString());
            if (loaded.empty()) {
                loaded.close();
                throw new IllegalStateException("No se pudo cargar el clasificador Haar");
            }
            cascade = loaded;
        }
        return cascade;
    }

    @Override
    public synchronized void close() {
        if (cascade != null) {
            cascade.close();
            cascade = null;
        }
    }
}
