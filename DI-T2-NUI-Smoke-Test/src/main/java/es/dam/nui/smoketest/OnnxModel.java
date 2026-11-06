package es.dam.nui.smoketest;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/**
 * Modelo de puntos clave (manos, pose) ejecutado con ONNX Runtime. Lo comparten las apps de gestos:
 * descarga el fichero la primera vez, crea la sesion UNA vez y ofrece {@link #infer}.
 *
 * <pre>{@code
 *   recorte cuadrado --reescala bilineal--> size x size --RGB/255--> tensor float
 *                                                                        |  OrtSession.run({entrada: tensor})
 *                                                                        v
 *   salidas del modelo, identificadas por su NUMERO TOTAL de elementos (producto de la forma):
 *      landmarkValues  -> puntos clave   (manos: 63 = 21 x 3 ; pose: 195 = 39 x 5)
 *      1               -> confianza
 *      cualquier otro  -> se ignora (lateralidad, mascara, mapas de calor, puntos 3D "de mundo")
 * }</pre>
 *
 * <p>El formato de entrada se detecta leyendo la forma: NHWC [1,H,W,3] o NCHW [1,3,H,W]. Los dos
 * modelos usados (MediaPipe Handpose y Pose del OpenCV Zoo) son NHWC; la rama NCHW es defensiva.
 *
 * <p>API de ONNX Runtime: {@code OrtEnvironment} (singleton), {@code OrtSession} (modelo cargado),
 * {@code OnnxTensor} (datos), {@code TensorInfo} (forma). Tensores y resultados son AutoCloseable.
 */
final class OnnxModel implements AutoCloseable {

    /** Resultado de una inferencia: puntos (null si ninguna salida los trae), confianza (-1 si no hay) y formas de salida. */
    record Output(float[] landmarks, float conf, String shapes) { }

    private static final float NO_CONFIDENCE = -1f;
    private static final float RGB_MAX = 255f;
    private static final int CHANNELS = 3;
    private static final int BYTE_MASK = 0xFF;
    private static final int RED_SHIFT = 16;
    private static final int GREEN_SHIFT = 8;

    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final String inputName;
    private final long[] inputShape;
    private final boolean nchw;
    private final int size;
    private final int landmarkValues;

    /** @param size lado de la entrada (px); @param landmarkValues numero de valores de la salida de puntos */
    OnnxModel(String url, Path file, int size, int landmarkValues) throws Exception {
        this.size = size;
        this.landmarkValues = landmarkValues;
        Path model = Downloads.ensureFile(url, file);
        session = env.createSession(model.toAbsolutePath().toString(), new OrtSession.SessionOptions());
        inputName = session.getInputNames().iterator().next();
        inputShape = ((TensorInfo) session.getInputInfo().get(inputName).getInfo()).getShape();
        nchw = inputShape.length == 4 && inputShape[1] == CHANNELS;   // si la dimension 1 vale 3: canales primero
    }

    /** Autotest con una imagen negra: el modelo debe devolver la salida de puntos. */
    String selfTest() throws Exception {
        Output out = infer(new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB));
        if (out.landmarks() == null) {
            throw new IllegalStateException("Ninguna salida tiene " + landmarkValues + " valores. Entrada "
                    + Arrays.toString(inputShape) + ", salidas: " + out.shapes());
        }
        return "modelo OK (entrada " + Arrays.toString(inputShape) + ")";
    }

    /** Preprocesa un recorte de cualquier tamano, ejecuta el modelo y extrae puntos y confianza. */
    Output infer(BufferedImage crop) throws Exception {
        BufferedImage in = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = in.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(crop, 0, 0, size, size, null);
        g.dispose();

        // Tensor plano con cada canal normalizado a 0..1. NCHW: tres planos (R, G, B); NHWC: R,G,B intercalados.
        float[] data = new float[CHANNELS * size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int rgb = in.getRGB(x, y);
                float r = ((rgb >> RED_SHIFT) & BYTE_MASK) / RGB_MAX;
                float gr = ((rgb >> GREEN_SHIFT) & BYTE_MASK) / RGB_MAX;
                float b = (rgb & BYTE_MASK) / RGB_MAX;
                if (nchw) {
                    data[y * size + x] = r;
                    data[size * size + y * size + x] = gr;
                    data[2 * size * size + y * size + x] = b;
                } else {
                    int i = (y * size + x) * CHANNELS;
                    data[i] = r;
                    data[i + 1] = gr;
                    data[i + 2] = b;
                }
            }
        }
        long[] dims = nchw ? new long[] {1, CHANNELS, size, size} : new long[] {1, size, size, CHANNELS};
        float[] landmarks = null;
        float conf = NO_CONFIDENCE;
        StringBuilder shapes = new StringBuilder();
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), dims);
             OrtSession.Result result = session.run(Map.of(inputName, tensor))) {
            for (Map.Entry<String, OnnxValue> e : result) {
                OnnxTensor out = (OnnxTensor) e.getValue();
                long[] shape = out.getInfo().getShape();
                shapes.append(e.getKey()).append(Arrays.toString(shape)).append(' ');
                long elements = Arrays.stream(shape).reduce(1, (a, b) -> a * b);
                if (elements == landmarkValues && landmarks == null) {
                    landmarks = flat(out);
                } else if (elements == 1 && conf == NO_CONFIDENCE) {
                    conf = flat(out)[0];
                }
            }
        }
        return new Output(landmarks, conf, shapes.toString().trim());
    }

    private static float[] flat(OnnxTensor tensor) throws OrtException {
        FloatBuffer fb = tensor.getFloatBuffer();
        float[] a = new float[fb.remaining()];
        fb.get(a);
        return a;
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException e) {
            throw new IllegalStateException(e);
        }
    }
}
