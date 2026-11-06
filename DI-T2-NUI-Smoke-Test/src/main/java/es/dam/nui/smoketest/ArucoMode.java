package es.dam.nui.smoketest;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC1;
import static org.bytedeco.opencv.global.opencv_objdetect.DICT_4X4_50;
import static org.bytedeco.opencv.global.opencv_objdetect.getPredefinedDictionary;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.bytedeco.javacpp.PointerScope;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.javacpp.indexer.IntIndexer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.MatVector;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_objdetect.ArucoDetector;
import org.bytedeco.opencv.opencv_objdetect.DetectorParameters;
import org.bytedeco.opencv.opencv_objdetect.Dictionary;
import org.bytedeco.opencv.opencv_objdetect.RefineParameters;

/**
 * Marcadores fiduciales ArUco (modulo {@code objdetect} de OpenCV, desde OpenCV 4.7): el ancla
 * estandar de RA y robotica, porque dan 4 esquinas precisas y un id robusto.
 *
 * <pre>{@code
 *   Marcador DICT_4X4_50, id 23              Deteccion
 *   +--+--+--+--+--+--+                       imagen --> umbral adaptativo --> contornos de 4 lados
 *   |##|##|##|##|##|##|  borde negro            --> quita la perspectiva --> lee la rejilla de 4x4 bits
 *   |##|  |  |##|  |##|  4x4 bits = id          --> busca el id en el diccionario (50 marcadores)
 *   |##|##|  |  |##|##|  (ilustrativo)         corners: 1x4 puntos (x, y) por marcador ; ids: Nx1
 *   +--+--+--+--+--+--+                       esquinas:  0 --- 1
 *                                                         |     |
 *                                                         3 --- 2     (siguen el giro del marcador)
 * }</pre>
 *
 * <p>Memoria nativa: {@code Mat} y {@code MatVector} viven fuera del heap de Java. Se agrupan en un
 * {@code PointerScope}, que los libera todos al cerrar el bloque en vez de esperar al recolector
 * (a 30 fps se acumularian). REGLA: el detector y el diccionario se crean FUERA de cualquier
 * scope ({@link #detector()}), porque deben sobrevivir a los fotogramas.
 *
 * <p>Nota: {@code RefineParameters} solo interviene en {@code refineDetectedMarkers}, que aqui no
 * se llama; pasarlo al constructor es inocuo pero no influye en {@code detectMarkers}.
 */
final class ArucoMode implements LiveMode {

    /** Un marcador detectado: su id y las coordenadas de sus 4 esquinas. */
    record Marker(int id, float[] x, float[] y) {

        int centerX() {
            return average(x);
        }

        int centerY() {
            return average(y);
        }

        private static int average(float[] values) {
            float sum = 0;
            for (float v : values) {
                sum += v;
            }
            return (int) (sum / values.length);
        }
    }

    private static final int MARKER_ID = 23;
    private static final int MARKER_PX = 200;
    private static final int BORDER_BITS = 1;
    private static final int SHEET_PX = 400;
    private static final int MARKER_OFFSET = (SHEET_PX - MARKER_PX) / 2;
    private static final double WHITE = 255.0;
    private static final int CORNERS = 4;
    private static final int GRAY_SHIFT_RED = 16;
    private static final int GRAY_SHIFT_GREEN = 8;
    private static final int BYTE_MASK = 0xFF;
    private static final float REFINE_MIN_REPETITION_DISTANCE = 10f;
    private static final float REFINE_ERROR_CORRECTION_RATE = 3f;
    private static final boolean REFINE_CHECK_ALL_ORDERS = true;
    private static final int STROKE_WIDTH = 4;
    private static final int LABEL_OFFSET = 10;
    private static final int MIN_LABEL_Y = 15;
    private static final Path SHEET_FILE = Path.of("aruco-" + MARKER_ID + ".png");

    private Dictionary dictionary;
    private ArucoDetector detector;

    @Override
    public String selfTest() {
        List<Marker> found = detect(sheet());
        if (found.stream().noneMatch(m -> m.id() == MARKER_ID)) {
            throw new IllegalStateException("no se detecto el marcador " + MARKER_ID + " (detectados: " + found.size() + ")");
        }
        return "marcador " + MARKER_ID + " generado y detectado";
    }

    @Override
    public boolean process(BufferedImage frame) {
        List<Marker> markers = detect(frame);
        if (!markers.isEmpty()) {
            overlay(frame, markers);
        }
        return !markers.isEmpty();
    }

    @Override
    public String hint() {
        return "Ensena el marcador " + SHEET_FILE + " (en el movil o impreso)";
    }

    /** Guarda el marcador en el directorio de trabajo, si no existe, para poder imprimirlo o verlo en el movil. */
    @Override
    public void prepareLive() throws IOException {
        if (!Files.exists(SHEET_FILE)) {
            ImageIO.write(sheet(), "png", SHEET_FILE.toFile());
        }
    }

    /** Detecta marcadores en una imagen RGB (la de la camara o la hoja de prueba). */
    List<Marker> detect(BufferedImage image) {
        ArucoDetector arucoDetector = detector();                 // fuera del scope: debe sobrevivir
        List<Marker> found = new ArrayList<>();
        try (var _ = new PointerScope()) {
            Mat img = Camera.toMat(image);
            MatVector corners = new MatVector();
            Mat ids = new Mat();
            arucoDetector.detectMarkers(img, corners, ids);
            if (ids.empty()) {
                return found;
            }
            IntIndexer idx = ids.createIndexer();
            for (int i = 0; i < ids.rows(); i++) {
                FloatIndexer fi = corners.get(i).createIndexer();
                float[] x = new float[CORNERS];
                float[] y = new float[CORNERS];
                for (int j = 0; j < CORNERS; j++) {
                    x[j] = fi.get(0, j, 0);                       // (fila 0, esquina j, canal 0 = x)
                    y[j] = fi.get(0, j, 1);                       // canal 1 = y
                }
                found.add(new Marker(idx.get(i, 0), x, y));
            }
        }
        return found;
    }

    /** Marcador {@value #MARKER_ID} centrado sobre un lienzo blanco (con zona de silencio), como imagen RGB. */
    private BufferedImage sheet() {
        Dictionary dict = dictionary();                           // fuera del scope
        try (var _ = new PointerScope()) {
            Mat marker = new Mat();
            dict.generateImageMarker(MARKER_ID, MARKER_PX, marker, BORDER_BITS);
            try (Mat canvas = new Mat(SHEET_PX, SHEET_PX, CV_8UC1, new Scalar(WHITE))) {
				marker.copyTo(canvas.apply(new Rect(MARKER_OFFSET, MARKER_OFFSET, MARKER_PX, MARKER_PX)));
				byte[] data = new byte[SHEET_PX * SHEET_PX];
				canvas.data().get(data);                              // el lienzo es continuo: SHEET_PX bytes por fila
				BufferedImage image = new BufferedImage(SHEET_PX, SHEET_PX, BufferedImage.TYPE_INT_RGB);
				for (int y = 0; y < SHEET_PX; y++) {
				    for (int x = 0; x < SHEET_PX; x++) {
				        int v = data[y * SHEET_PX + x] & BYTE_MASK;
				        image.setRGB(x, y, (v << GRAY_SHIFT_RED) | (v << GRAY_SHIFT_GREEN) | v);
				    }
				}
				return image;
			}
        }
    }

    private static void overlay(BufferedImage image, List<Marker> markers) {
        Graphics2D g = image.createGraphics();
        g.setStroke(new BasicStroke(STROKE_WIDTH));
        for (Marker m : markers) {
            g.setColor(Color.MAGENTA);
            for (int j = 0; j < CORNERS; j++) {
                int k = (j + 1) % CORNERS;
                g.drawLine((int) m.x()[j], (int) m.y()[j], (int) m.x()[k], (int) m.y()[k]);
            }
            g.setColor(Color.YELLOW);
            g.drawString("RA id=" + m.id(), (int) m.x()[0], Math.max(MIN_LABEL_Y, (int) m.y()[0] - LABEL_OFFSET));
        }
        g.dispose();
    }

    private synchronized ArucoDetector detector() {
        if (detector == null) {
            dictionary = getPredefinedDictionary(DICT_4X4_50);    // 50 marcadores de 4x4 bits
            detector = new ArucoDetector(dictionary, new DetectorParameters(),
                    new RefineParameters(REFINE_MIN_REPETITION_DISTANCE, REFINE_ERROR_CORRECTION_RATE, REFINE_CHECK_ALL_ORDERS));
        }
        return detector;
    }

    private synchronized Dictionary dictionary() {
        detector();
        return dictionary;
    }
}
