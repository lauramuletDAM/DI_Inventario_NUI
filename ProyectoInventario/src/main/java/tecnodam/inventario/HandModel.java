package tecnodam.inventario;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * 21 puntos de la mano (MediaPipe Handpose del OpenCV Zoo, ONNX). UNA instancia sirve a todos los
 * modos que miran la mano: pellizco, dwell, swipe y picking 3D son solo interpretaciones distintas
 * de los mismos puntos.
 *
 * <pre>{@code
 *   Fotograma w x h                    recorte cuadrado central         OnnxModel (entrada [1,224,224,3])
 *   +---------------------+            +--------+                       salidas: 63 valores (21 x x,y,z en px de 224)
 *   |     +---------+     |  side=     | 224x224| ----------------->           1 valor (confianza de mano)
 *   |     | cuadrado|     |  min(w,h)  +--------+                                    |
 *   |     +---------+     |                                                           v
 *   +---------------------+  cx=(w-side)/2, cy=(h-side)/2       Hand: puntos / 224 (0..1 dentro del cuadrado)
 *
 *   Puntos (convencion MediaPipe)                   Pellizco
 *    Pulgar  Indice  Medio  Anular  Menique         ratio = dist(4,8) / dist(0,9)
 *      4       8      12     16      20  <- puntas         punta pulgar-punta indice
 *      |       |       |      |       |                    ---------------------------
 *      3       7      11     15      19                    muneca - nudillo del dedo medio  (tamano de la mano)
 *      |       |       |      |       |                  independiente de la distancia a la camara
 *      2       6      10     14      18
 *      |       |       |      |       |
 *      1       5-------9------13------17  <- nudillos
 *       \      |                       |
 *        +-----+-----------0-----------+      0 = muneca (conecta con 1, 5 y 17)
 * }</pre>
 *
 * <p>Simplificacion deliberada: no hay detector de palma previo. La mano debe estar DENTRO del
 * cuadrado central y razonablemente grande.
 */
final class HandModel implements AutoCloseable {

    private static final String MODEL_URL =
            "https://huggingface.co/opencv/handpose_estimation_mediapipe/resolve/main/handpose_estimation_mediapipe_2023feb.onnx";
    private static final Path MODEL_FILE = Path.of("models", "handpose_estimation_mediapipe_2023feb.onnx");
    private static final int SIZE = 224;
    private static final int LANDMARKS = 21;
    private static final int VALUES = 3;                           // x, y, z por punto
    private static final int LANDMARK_VALUES = LANDMARKS * VALUES;
    private static final float MIN_CONFIDENCE = 0.5f;
    private static final double MIN_HAND_SIZE = 1e-6;
    private static final int POINT_RADIUS = 4;

    static final int WRIST = 0;
    static final int THUMB_TIP = 4;
    static final int INDEX_TIP = 8;
    static final int MIDDLE_MCP = 9;                               // nudillo del dedo medio

    /** Pares de puntos que se unen con una linea para dibujar el esqueleto (21 huesos). */
    private static final int[][] BONES = {
            {0, 1}, {1, 2}, {2, 3}, {3, 4}, {0, 5}, {5, 6}, {6, 7}, {7, 8}, {5, 9}, {9, 10}, {10, 11}, {11, 12},
            {9, 13}, {13, 14}, {14, 15}, {15, 16}, {13, 17}, {17, 18}, {18, 19}, {19, 20}, {0, 17}};

    /**
     * Resultado de mirar un fotograma: el cuadrado de recorte (en pixeles del fotograma) y los
     * puntos normalizados (0..1 dentro del cuadrado), o null si el modelo no devolvio puntos.
     */
    record Hand(int cx, int cy, int side, float[] lm, float conf) {

        /** Hay mano si el modelo devolvio puntos con confianza suficiente. */
        boolean present() {
            return lm != null && conf > MIN_CONFIDENCE;
        }

        double nx(int i) {
            return lm[i * VALUES];
        }

        double ny(int i) {
            return lm[i * VALUES + 1];
        }

        /** Coordenadas del punto i en pixeles del fotograma. */
        int px(int i) {
            return cx + (int) (nx(i) * side);
        }

        int py(int i) {
            return cy + (int) (ny(i) * side);
        }

        /** Pellizco: punta del pulgar a punta del indice, entre muneca y nudillo medio (menor = mas cerrado). */
        double pinchRatio() {
            return dist(THUMB_TIP, INDEX_TIP) / Math.max(MIN_HAND_SIZE, dist(WRIST, MIDDLE_MCP));
        }

        private double dist(int a, int b) {
            return Math.hypot(nx(a) - nx(b), ny(a) - ny(b));        // solo x e y: se ignora z
        }

        /** Marco blanco del cuadrado donde busca el modelo. */
        void drawSquare(Graphics2D g) {
            g.setColor(Color.WHITE);
            g.drawRect(cx, cy, side - 1, side - 1);
        }

        void drawBones(Graphics2D g) {
            g.setColor(Color.CYAN);
            for (int[] b : BONES) {
                g.drawLine(px(b[0]), py(b[0]), px(b[1]), py(b[1]));
            }
        }

        void drawPoints(Graphics2D g) {
            g.setColor(Color.RED);
            for (int i = 0; i < LANDMARKS; i++) {
                g.fillOval(px(i) - POINT_RADIUS, py(i) - POINT_RADIUS, 2 * POINT_RADIUS, 2 * POINT_RADIUS);
            }
        }
    }

    private OnnxModel model;

    /** Descarga (1a vez, ~4 MB) y carga el modelo cuando hace falta. */
    private synchronized OnnxModel model() throws Exception {
        if (model == null) {
            model = new OnnxModel(MODEL_URL, MODEL_FILE, SIZE, LANDMARK_VALUES);
        }
        return model;
    }

    String selfTest() throws Exception {
        return model().selfTest();
    }

    /** Busca una mano en el cuadrado central del fotograma. Siempre devuelve el cuadrado; {@link Hand#present()} dice si hay mano. */
    Hand detect(BufferedImage frame) throws Exception {
        int side = Math.min(frame.getWidth(), frame.getHeight());
        int cx = (frame.getWidth() - side) / 2;
        int cy = (frame.getHeight() - side) / 2;
        OnnxModel.Output out = model().infer(frame.getSubimage(cx, cy, side, side));
        float[] lm = null;
        if (out.landmarks() != null) {
            lm = out.landmarks().clone();
            for (int i = 0; i < LANDMARKS; i++) {                   // de pixeles de 224 a 0..1
                lm[i * VALUES] /= SIZE;
                lm[i * VALUES + 1] /= SIZE;
            }
        }
        return new Hand(cx, cy, side, lm, out.conf());
    }

    @Override
    public synchronized void close() {
        if (model != null) {
            model.close();
            model = null;
        }
    }
}
