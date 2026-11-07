package tecnodam.inventario;

import ai.onnxruntime.*;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.Map;

final class OnnxModel implements AutoCloseable {

    private final String modelUrl;
    private final Path modelFile;
    private final int inputSize;
    private final int outputValues;

    private OrtEnvironment env;
    private OrtSession session;

    record Output(float[] landmarks, float conf) {}

    OnnxModel(String modelUrl, Path modelFile, int inputSize, int outputValues) {
        this.modelUrl = modelUrl;
        this.modelFile = modelFile;
        this.inputSize = inputSize;
        this.outputValues = outputValues;
    }

    private synchronized void init() throws Exception {
        if (session == null) {
            Downloads.ensureFile(modelUrl, modelFile);
            env = OrtEnvironment.getEnvironment();
            session = env.createSession(modelFile.toString(), new OrtSession.SessionOptions());
        }
    }

    String selfTest() throws Exception {
        init();
        return "Modelo ONNX de manos cargado correctamente";
    }

    Output infer(BufferedImage subImage) throws Exception {
        init();

        // Redimensionar la imagen recortada a 224x224 para el modelo
        BufferedImage resized = new BufferedImage(inputSize, inputSize, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.drawImage(subImage, 0, 0, inputSize, inputSize, null);
        g.dispose();

        // Preparar tensor de entrada normalizado [1, 224, 224, 3]
        float[] tensorData = new float[inputSize * inputSize * 3];
        int idx = 0;
        for (int y = 0; y < inputSize; y++) {
            for (int x = 0; x < inputSize; x++) {
                int rgb = resized.getRGB(x, y);
                tensorData[idx++] = ((rgb >> 16) & 0xFF) / 255.0f;
                tensorData[idx++] = ((rgb >> 8) & 0xFF) / 255.0f;
                tensorData[idx++] = (rgb & 0xFF) / 255.0f;
            }
        }

        long[] shape = {1, inputSize, inputSize, 3};
        OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(tensorData), shape);
        String inputName = session.getInputNames().iterator().next();

        try (OrtSession.Result result = session.run(Map.of(inputName, tensor))) {
            float[] landmarks = null;
            float conf = 1.0f;

            if (result.iterator().hasNext()) {
                // Obtener el primer resultado de la sesión de manera segura mediante iterador
                Object firstVal = result.iterator().next().getValue().getValue();
                if (firstVal instanceof float[][]) {
                    landmarks = ((float[][]) firstVal)[0];
                } else if (firstVal instanceof float[]) {
                    landmarks = (float[]) firstVal;
                }
            }

            return new Output(landmarks, conf);
        } finally {
            tensor.close();
        }
    }

    @Override
    public void close() {
        try {
            if (session != null) session.close();
            if (env != null) env.close();
        } catch (Exception ignored) {}
    }
}