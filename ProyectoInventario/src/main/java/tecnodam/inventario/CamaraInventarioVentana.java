package tecnodam.inventario;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class CamaraInventarioVentana {

    private CamaraInventarioVentana() {}

    static void abrir(ObservableList<Articulo> catalogo, Consumer<Articulo> onConfirmarRecepcion) {
        Stage stage = new Stage();
        stage.setTitle("TecnoDAM - Recepción por Cámara e Interfaz Natural");

        BorderPane root = new BorderPane();
        ImageView imageView = new ImageView();
        imageView.setFitWidth(640);
        imageView.setFitHeight(480);
        imageView.setPreserveRatio(true);
        root.setCenter(imageView);

        Label lblMensaje = new Label("Enseña el código QR del componente a la cámara...");
        lblMensaje.setStyle("-fx-font-size: 14px; -fx-text-fill: white; -fx-background-color: rgba(0,0,0,0.85); -fx-padding: 14px; -fx-alignment: center;");
        lblMensaje.setMaxWidth(Double.MAX_VALUE);
        root.setBottom(lblMensaje);

        Scene scene = new Scene(root, 640, 535);
        stage.setScene(scene);

        AtomicBoolean running = new AtomicBoolean(true);
        HandModel hands = new HandModel(); // Inicializa el modelo ONNX de manos

        QrMode qrMode = new QrMode(
            catalogo, 
            articulo -> Platform.runLater(() -> onConfirmarRecepcion.accept(articulo)),
            () -> Platform.runLater(stage::close) // Cierra ventana al hacer swipe de derecha a izquierda
        );

        Thread hiloCamara = new Thread(() -> {
            try {
                Camera.stream(running::get, frame -> {
                    BufferedImage copiaFrame = Camera.copy(frame);
                    
                    qrMode.process(copiaFrame, mensaje -> {
                        Platform.runLater(() -> lblMensaje.setText(mensaje));
                    }, hands);

                    javafx.scene.image.Image fxImg = SwingFXUtils.toFXImage(copiaFrame, null);
                    Platform.runLater(() -> imageView.setImage(fxImg));
                });
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                hands.close(); // Libera los recursos nativos del modelo al salir
            }
        });

        hiloCamara.setDaemon(true);
        hiloCamara.start();

        stage.setOnCloseRequest(e -> {
            running.set(false);
            hands.close();
        });
        
        stage.show();
    }
}