package es.dam.nui.smoketest;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * Pose corporal (MediaPipe Pose del OpenCV Zoo, ONNX), angulos articulares con producto escalar y
 * accion "brazo levantado". Comparte {@link OnnxModel} y la camara con las manos, pero es otro modelo.
 *
 * <pre>{@code
 *   Recorte central 256x256 --RGB/255, NHWC--> modelo ONNX
 *      entrada  input_1   [1,256,256,3]
 *      salidas  Identity   [1,195]       39 puntos x 5 valores (x, y, z, visibilidad, presencia)  <- se usa
 *               Identity_1 [1,1]         confianza de persona                                     <- se usa
 *               (mascara de segmentacion, mapas de calor y puntos 3D: se ignoran)
 *
 *   Puntos usados (de los 33 de MediaPipe; izquierda/derecha del SUJETO)     Brazo levantado (y crece hacia abajo)
 *              0 nariz                                                        y(muneca) < y(hombro) - 0.05
 *       12 ----- 11   hombros (12 dcho, 11 izq)
 *       |         |
 *      14        13   codos
 *       |         |
 *      16        15   munecas
 *       24 ----- 23   caderas
 *
 *   Angulo en el vertice b (producto escalar):    u = a - b ; v = c - b
 *        a                                        cos(theta) = (u . v) / (|u| |v|)
 *         \          90 grados = codo recto
 *          b----c    180 grados = brazo estirado
 * }</pre>
 *
 * <p>Simplificacion: no hay detector de personas; hay que alejarse para que la parte superior del
 * cuerpo quepa en el cuadrado central.
 */
final class PoseMode implements LiveMode {

    private static final String MODEL_URL =
            "https://huggingface.co/opencv/pose_estimation_mediapipe/resolve/main/pose_estimation_mediapipe_2023mar.onnx";
    private static final Path MODEL_FILE = Path.of("models", "pose_estimation_mediapipe_2023mar.onnx");
    private static final int SIZE = 256;
    private static final int STRIDE = 5;                           // valores por punto: x, y, z, visibilidad, presencia
    private static final int POINTS = 39;                          // 33 del cuerpo + 6 auxiliares
    private static final int BODY_POINTS = 33;
    private static final float MIN_CONFIDENCE = 0.5f;
    private static final double PIXEL_THRESHOLD = 2.0;             // si algun valor lo supera, estan en pixeles
    private static final double RAISED_MARGIN = 0.05;              // muneca por encima del hombro, en fraccion del lado
    private static final double MIN_LENGTH = 1e-9;
    private static final double ANGLE_TOLERANCE = 0.5;
    private static final int POINT_RADIUS = 5;
    private static final int STROKE_WIDTH = 3;
    private static final int TEXT_X = 8;
    private static final int TEXT_Y = 18;

    private static final int NOSE = 0;
    private static final int L_SHOULDER = 11;
    private static final int R_SHOULDER = 12;
    private static final int L_ELBOW = 13;
    private static final int R_ELBOW = 14;
    private static final int L_WRIST = 15;
    private static final int R_WRIST = 16;
    private static final int L_HIP = 23;
    private static final int R_HIP = 24;
    private static final int[] KEY_POINTS = {NOSE, L_SHOULDER, R_SHOULDER, L_ELBOW, R_ELBOW, L_WRIST, R_WRIST, L_HIP, R_HIP};
    private static final int[][] BONES = {{L_SHOULDER, R_SHOULDER}, {L_SHOULDER, L_ELBOW}, {L_ELBOW, L_WRIST},
            {R_SHOULDER, R_ELBOW}, {R_ELBOW, R_WRIST}, {L_SHOULDER, L_HIP}, {R_SHOULDER, R_HIP}, {L_HIP, R_HIP}};

    private OnnxModel model;

    @Override
    public String selfTest() throws Exception {
        String angles = selfTestAngles();
        return angles + "; " + model().selfTest();
    }

    @Override
    public boolean process(BufferedImage frame) throws Exception {
        int side = Math.min(frame.getWidth(), frame.getHeight());
        int cx = (frame.getWidth() - side) / 2;
        int cy = (frame.getHeight() - side) / 2;
        OnnxModel.Output out = model().infer(frame.getSubimage(cx, cy, side, side));
        Graphics2D g = frame.createGraphics();
        g.setStroke(new BasicStroke(STROKE_WIDTH));
        g.setColor(Color.WHITE);
        g.drawRect(cx, cy, side - 1, side - 1);
        boolean person = out.landmarks() != null && out.conf() > MIN_CONFIDENCE;
        if (person) {
            float[] p = out.landmarks().clone();
            double max = 0;
            for (int i = 0; i < BODY_POINTS; i++) {
                max = Math.max(max, Math.max(Math.abs(p[i * STRIDE]), Math.abs(p[i * STRIDE + 1])));
            }
            float div = max > PIXEL_THRESHOLD ? SIZE : 1f;         // pixeles sobre 256, o ya normalizadas
            for (int i = 0; i < BODY_POINTS; i++) {
                p[i * STRIDE] /= div;
                p[i * STRIDE + 1] /= div;
            }
            g.setColor(Color.CYAN);
            for (int[] b : BONES) {
                g.drawLine(cx + (int) (p[b[0] * STRIDE] * side), cy + (int) (p[b[0] * STRIDE + 1] * side),
                        cx + (int) (p[b[1] * STRIDE] * side), cy + (int) (p[b[1] * STRIDE + 1] * side));
            }
            g.setColor(Color.RED);
            for (int i : KEY_POINTS) {
                g.fillOval(cx + (int) (p[i * STRIDE] * side) - POINT_RADIUS, cy + (int) (p[i * STRIDE + 1] * side) - POINT_RADIUS,
                        2 * POINT_RADIUS, 2 * POINT_RADIUS);
            }
            double elbowL = angle(p, L_SHOULDER, L_ELBOW, L_WRIST);
            double elbowR = angle(p, R_SHOULDER, R_ELBOW, R_WRIST);
            boolean upL = p[L_WRIST * STRIDE + 1] < p[L_SHOULDER * STRIDE + 1] - RAISED_MARGIN;
            boolean upR = p[R_WRIST * STRIDE + 1] < p[R_SHOULDER * STRIDE + 1] - RAISED_MARGIN;
            g.setColor(Color.YELLOW);
            g.drawString(String.format("conf=%.2f codo izq=%.0f dcho=%.0f %s%s", out.conf(), elbowL, elbowR,
                    upL ? "BRAZO IZQ LEVANTADO " : "", upR ? "BRAZO DCHO LEVANTADO" : ""), cx + TEXT_X, cy + TEXT_Y);
        }
        g.dispose();
        return person;
    }

    @Override
    public String hint() {
        return "Alejate para que se vea la parte superior del cuerpo en el cuadrado central y levanta un brazo, luego el otro";
    }

    /** Angulo en grados en el vertice b formado por los puntos a, b, c (solo x e y; cada punto ocupa STRIDE floats). */
    static double angle(float[] p, int a, int b, int c) {
        double ux = p[a * STRIDE] - p[b * STRIDE];
        double uy = p[a * STRIDE + 1] - p[b * STRIDE + 1];
        double vx = p[c * STRIDE] - p[b * STRIDE];
        double vy = p[c * STRIDE + 1] - p[b * STRIDE + 1];
        // max(MIN_LENGTH) evita dividir por cero; el clamp a [-1, 1] evita NaN por redondeo en acos
        double cos = (ux * vx + uy * vy) / Math.max(MIN_LENGTH, Math.hypot(ux, uy) * Math.hypot(vx, vy));
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
    }

    /** Autotest geometrico sin modelo: (0,0)-(0,1)-(1,1) debe dar 90 grados y (0,0)-(0,1)-(0,2) 180. */
    static String selfTestAngles() {
        float[] p = new float[STRIDE * 3];
        p[0] = 0; p[1] = 0; p[STRIDE] = 0; p[STRIDE + 1] = 1; p[2 * STRIDE] = 1; p[2 * STRIDE + 1] = 1;
        double a90 = angle(p, 0, 1, 2);
        p[2 * STRIDE] = 0; p[2 * STRIDE + 1] = 2;
        double a180 = angle(p, 0, 1, 2);
        if (Math.abs(a90 - 90) > ANGLE_TOLERANCE || Math.abs(a180 - 180) > ANGLE_TOLERANCE) {
            throw new IllegalStateException("angulos " + a90 + " y " + a180);
        }
        return "angulos 90/180 OK";
    }

    private synchronized OnnxModel model() throws Exception {
        if (model == null) {
            model = new OnnxModel(MODEL_URL, MODEL_FILE, SIZE, POINTS * STRIDE);
        }
        return model;
    }

    @Override
    public synchronized void close() {
        if (model != null) {
            model.close();
            model = null;
        }
    }
}
