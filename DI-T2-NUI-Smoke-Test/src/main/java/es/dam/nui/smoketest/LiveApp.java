package es.dam.nui.smoketest;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Base de las apps con camara: una ventana con un selector de {@link LiveMode}, el bucle de
 * camara en vivo y la comprobacion automatica. Cada subclase solo registra sus modos.
 *
 * <pre>{@code
 *   ChoiceBox: modo 1 | modo 2 | ...         [Iniciar / Detener]   [Comprobar]
 *        |
 *        v  modes.get(nombre) -> LiveMode
 *   Camera.stream(running, frame -> { detected = mode.process(frame); mostrar(frame); contadores })
 *        (hilo de trabajo: la UI solo recibe la imagen ya procesada)
 *
 *   --check (sin camara ni persona):  para cada modo -> mode.selfTest()
 * }</pre>
 *
 * <p>Los modos se registran en el constructor de la subclase, en el orden en que se ejecutan en
 * {@code --check}. La camara en si se valida en la app Entorno.
 */
public abstract class LiveApp extends NuiApp {

    private static final int WINDOW_WIDTH = 720;
    private static final int WINDOW_HEIGHT = 700;
    private static final int PREVIEW_WIDTH = 640;
    private static final int OUTPUT_ROWS = 5;
    private static final int GAP = 10;
    private static final String START = "Iniciar";
    private static final String STOP = "Detener";

    /** Modos de la app, en orden de registro. */
    protected final Map<String, LiveMode> modes = new LinkedHashMap<>();

    private volatile boolean running;
    private ChoiceBox<String> choice;
    private Button startButton;
    private Button verifyButton;
    private Label statusLabel;
    private TextArea out;
    private ImageView preview;

    /** Titulo de la ventana. */
    protected abstract String title();

    @Override
    protected Map<String, Callable<String>> steps() {
        Map<String, Callable<String>> steps = new LinkedHashMap<>();
        modes.forEach((name, mode) -> steps.put(name, mode::selfTest));
        return steps;
    }

    // ------------------------------------------------------------------ en directo

    private void toggleLive() {
        if (running) {
            running = false;                                       // el bucle termina en el siguiente fotograma
            return;
        }
        LiveMode mode = modes.get(choice.getValue());
        running = true;
        startButton.setText(STOP);
        choice.setDisable(true);
        verifyButton.setDisable(true);
        out.clear();
        CompletableFuture.supplyAsync(() -> {
            try {
                live(mode);
                return "";
            } catch (Throwable e) {                                // Throwable: tambien errores nativos
                return "FALLO - " + e;
            }
        }).thenAccept(error -> Platform.runLater(() -> {
            running = false;
            startButton.setText(START);
            choice.setDisable(false);
            verifyButton.setDisable(false);
            out.setText(error);
        }));
    }

    /** Bucle de camara del modo elegido; cuenta fotogramas y detecciones. */
    private void live(LiveMode mode) throws Exception {
        try {
            mode.prepareLive();
            AtomicInteger frames = new AtomicInteger();
            AtomicInteger hits = new AtomicInteger();
            Camera.stream(() -> running, frame -> {
                if (mode.process(frame)) {
                    hits.incrementAndGet();
                }
                frames.incrementAndGet();
                show(frame);
                status(mode.hint() + "   [" + hits + " de " + frames + " fotogramas con deteccion]");
            });
        } finally {
            mode.endLive();
        }
    }

    private void show(BufferedImage frame) {
        Image image = SwingFXUtils.toFXImage(frame, null);         // conversion fuera del hilo de JavaFX
        Platform.runLater(() -> preview.setImage(image));
    }

    private void status(String text) {
        Platform.runLater(() -> statusLabel.setText(text));
    }

    // ------------------------------------------------------------------ interfaz

    @Override
    protected void buildUi(Stage stage) {
        choice = new ChoiceBox<>();
        choice.getItems().addAll(modes.keySet());
        choice.setValue(modes.keySet().iterator().next());
        startButton = new Button(START);
        startButton.setOnAction(e -> toggleLive());
        verifyButton = new Button("Comprobar");
        verifyButton.setOnAction(e -> verify());
        statusLabel = new Label();
        statusLabel.setWrapText(true);
        out = new TextArea();
        out.setEditable(false);
        out.setWrapText(true);
        out.setPrefRowCount(OUTPUT_ROWS);
        preview = new ImageView();
        preview.setFitWidth(PREVIEW_WIDTH);
        preview.setPreserveRatio(true);

        VBox root = new VBox(GAP, new HBox(GAP, choice, startButton, verifyButton), statusLabel, out, preview);
        root.setPadding(new Insets(GAP));
        stage.setTitle(title());
        stage.setScene(new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT));
        stage.show();
    }

    /** Autotests de todos los modos, sin camara. */
    private void verify() {
        startButton.setDisable(true);
        verifyButton.setDisable(true);
        out.setText("Comprobando...");
        CompletableFuture.supplyAsync(this::check).thenAccept(report -> Platform.runLater(() -> {
            out.setText(report.text().replace(SEPARATOR, "\n"));
            startButton.setDisable(false);
            verifyButton.setDisable(false);
        }));
    }

    @Override
    public void stop() {
        running = false;
        modes.values().forEach(LiveMode::close);
    }
}
