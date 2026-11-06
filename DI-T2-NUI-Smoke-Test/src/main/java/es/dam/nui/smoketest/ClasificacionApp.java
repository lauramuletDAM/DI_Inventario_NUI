package es.dam.nui.smoketest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import ai.djl.Application;
import ai.djl.inference.Predictor;
import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;


/**
 * App "Clasificacion": que Deep Java Library (DJL) puede cargar el motor nativo de PyTorch,
 * descargar un modelo preentrenado (ResNet18, ImageNet) y clasificar una imagen. Sustituye a la
 * antigua prueba 5. Es la comprobacion mas pesada de la primera ejecucion, por eso va en su propia
 * app (y con su propio plazo largo en el launcher).
 *
 * <pre>{@code
 *   Criteria.builder()   "un clasificador de imagenes, motor PyTorch, resnet, 18 capas"
 *      |  loadModel()  -> 1a vez: descarga libtorch nativo y los pesos (cache: ~/.djl.ai)
 *      v
 *   ZooModel<Image, Classifications>  (se carga UNA vez y se reutiliza)
 *      |  newPredictor()
 *      v
 *   Predictor --predict(Image)--> Classifications --topK(3)--> "clase (xx.x%), clase (xx.x%), ..."
 *                ^
 *   URL --ImageFactory.fromUrl--> Image
 * }</pre>
 *
 * <p>Piezas de DJL: {@code Criteria} (que modelo quiero), {@code ZooModel} (modelo cargado, con su
 * preproceso y postproceso), {@code Predictor} (inferencia; NO es seguro entre hilos, por eso se
 * crea uno por llamada), {@code Classifications} (clases con probabilidad). Modelo y predictor son
 * AutoCloseable (memoria nativa).
 *
 * <p>Si el filtro {@code layers=18} no existe en otra version de DJL ({@code ModelNotFoundException}),
 * hay que ajustar {@link #LAYERS}. Solo se exige que el modelo devuelva clases; no se valida que
 * sean las correctas.
 */
public class ClasificacionApp extends NuiApp {

    private static final String IMAGE_URL = "https://raw.githubusercontent.com/pytorch/hub/master/images/dog.jpg";
    private static final String ENGINE = "PyTorch";
    private static final String ARTIFACT_ID = "resnet";
    private static final String LAYERS_FILTER = "layers";
    private static final String LAYERS = "18";
    private static final int TOP_K = 3;
    private static final int PERCENT = 100;
    private static final int WINDOW_WIDTH = 560;
    private static final int WINDOW_HEIGHT = 300;
    private static final int GAP = 10;

    private ZooModel<Image, Classifications> model;
    private Label statusLabel;                                     // null en modo --check

    @Override
    protected Map<String, Callable<String>> steps() {
        Map<String, Callable<String>> steps = new LinkedHashMap<>();
        steps.put("ResNet18", this::clasificar);
        return steps;
    }

    /** Clasifica la foto de prueba y devuelve las {@value #TOP_K} mejores clases. */
    private String clasificar() throws Exception {
        ZooModel<Image, Classifications> loaded = model();
        try (Predictor<Image, Classifications> predictor = loaded.newPredictor()) {
            Image img = ImageFactory.getInstance().fromUrl(IMAGE_URL);
            return predictor.predict(img).topK(TOP_K).stream()
                    .map(c -> c.getClassName() + String.format(" (%.1f%%)", c.getProbability() * PERCENT))
                    .collect(Collectors.joining(", "));
        }
    }

    /** Carga el modelo la primera vez que hace falta (varios minutos si hay que descargar PyTorch y los pesos). */
    private synchronized ZooModel<Image, Classifications> model() throws Exception {
        if (model == null) {
            status("Cargando modelo (la primera vez descarga PyTorch nativo y ResNet, varios minutos)...");
            Criteria<Image, Classifications> criteria = Criteria.builder()
                    .optApplication(Application.CV.IMAGE_CLASSIFICATION)
                    .setTypes(Image.class, Classifications.class)
                    .optEngine(ENGINE)
                    .optArtifactId(ARTIFACT_ID)
                    .optFilter(LAYERS_FILTER, LAYERS)
                    .build();
            model = criteria.loadModel();
        }
        return model;
    }

    @Override
    public void stop() {
        if (model != null) {
            model.close();
        }
    }

    private void status(String text) {
        Platform.runLater(() -> {
            if (statusLabel != null) {
                statusLabel.setText(text);
            }
        });
    }

    @Override
    protected void buildUi(Stage stage) {
        statusLabel = new Label();
        statusLabel.setWrapText(true);
        TextArea out = new TextArea();
        out.setEditable(false);
        out.setWrapText(true);
        Button classify = new Button("Clasificar la foto de prueba");
        classify.setOnAction(e -> {
            classify.setDisable(true);
            out.setText("Trabajando...");
            CompletableFuture.supplyAsync(() -> {
                try {
                    return clasificar();
                } catch (Throwable ex) {                           // Throwable: tambien errores nativos
                    return "FALLO - " + ex;
                }
            }).thenAccept(text -> Platform.runLater(() -> {
                out.setText(text);
                statusLabel.setText("");
                classify.setDisable(false);
            }));
        });

        VBox root = new VBox(GAP, classify, statusLabel, out);
        root.setPadding(new Insets(GAP));
        stage.setTitle("Clasificacion (DJL)");
        stage.setScene(new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT));
        stage.show();
    }
}
