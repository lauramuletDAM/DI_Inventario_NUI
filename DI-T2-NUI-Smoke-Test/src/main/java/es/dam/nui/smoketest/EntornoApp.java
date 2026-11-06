package es.dam.nui.smoketest;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameGrabber;

import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * App "Entorno": que el equipo esta listo (red, camara y soporte 3D). Sustituye a las antiguas
 * pruebas 1 (NetCheck) y 2 (CameraCheck) y a la comprobacion SCENE3D de la 9.
 *
 * <pre>{@code
 *   steps():  Red ----> HttpClient GET example.com
 *             Camara -> OpenCVFrameGrabber(0) -> 20 fotogramas -> el ultimo valido -> BufferedImage
 *             3D -----> Platform.isSupported(ConditionalFeature.SCENE3D)
 *
 *   ventana:  [Comprobar] -> check() en segundo plano -> texto con un paso por linea + vista previa
 * }</pre>
 *
 * <p>El render 3D completo se prueba en la app de marcadores y RA; aqui solo se pregunta si el
 * equipo lo soporta.
 */
public class EntornoApp extends NuiApp {

    private static final String TEST_URL = "https://example.com";
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int CAMERA_INDEX = 0;
    private static final int WARMUP_FRAMES = 20;
    private static final int PREVIEW_WIDTH = 480;
    private static final int WINDOW_WIDTH = 520;
    private static final int WINDOW_HEIGHT = 560;
    private static final int GAP = 10;

    /** Ultimo fotograma de la camara; solo para la vista previa de la ventana. */
    private volatile BufferedImage frame;

    @Override
    protected Map<String, Callable<String>> steps() {
        Map<String, Callable<String>> steps = new LinkedHashMap<>();
        steps.put("Red", this::red);
        steps.put("Camara", this::camara);
        steps.put("3D", this::escena3d);
        return steps;
    }

    private String red() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(TEST_URL)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return "HTTP " + response.statusCode() + " (" + response.body().length() + " caracteres)";
    }

    private String camara() throws Exception {
        try (OpenCVFrameGrabber grabber = new OpenCVFrameGrabber(CAMERA_INDEX);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            grabber.start();
            Frame last = null;
            for (int i = 0; i < WARMUP_FRAMES; i++) {          // descarta fotogramas de calentamiento
                Frame f = grabber.grab();
                if (f != null && f.image != null) {
                    last = f;
                }
            }
            if (last == null) {
                throw new IllegalStateException("La camara no entrego fotogramas");
            }
            BufferedImage image = converter.convert(last);
            // Copia independiente: la imagen del convertidor puede compartir memoria con el Frame.
            BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = copy.createGraphics();
            g.drawImage(image, 0, 0, null);
            g.dispose();
            frame = copy;
            return copy.getWidth() + "x" + copy.getHeight();
        }
    }

    private String escena3d() {
        if (!Platform.isSupported(ConditionalFeature.SCENE3D)) {
            throw new IllegalStateException("SCENE3D no soportado en este equipo");
        }
        return "soportado";
    }

    @Override
    protected void buildUi(Stage stage) {
        Button run = new Button("Comprobar");
        TextArea out = new TextArea();
        out.setEditable(false);
        out.setWrapText(true);
        ImageView preview = new ImageView();
        preview.setFitWidth(PREVIEW_WIDTH);
        preview.setPreserveRatio(true);

        run.setOnAction(e -> {
            run.setDisable(true);
            out.setText("Comprobando...");
            CompletableFuture.supplyAsync(this::check).thenAccept(report -> Platform.runLater(() -> {
                out.setText(report.text().replace(SEPARATOR, "\n"));
                if (frame != null) {
                    preview.setImage(SwingFXUtils.toFXImage(frame, null));
                }
                run.setDisable(false);
            }));
        });

        VBox root = new VBox(GAP, run, out, preview);
        root.setPadding(new Insets(GAP));
        stage.setTitle("Entorno");
        stage.setScene(new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT));
        stage.show();
    }
}
