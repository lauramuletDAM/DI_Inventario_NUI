package es.dam.nui.smoketest;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Ventana del launcher: una fila por app ({@link AppId}) con su boton "Abrir", y "Comprobar todo"
 * para validar el equipo completo de una vez.
 *
 * <pre>{@code
 *   +---------------------------------------------------------------+
 *   | App        Necesita              [Abrir]   estado               |
 *   | Entorno    Internet, camara, 3D  [Abrir]   OK - Red: ... | ...   |
 *   | ...                                                             |
 *   | [Comprobar todo]    N de M apps OK                              |
 *   +---------------------------------------------------------------+
 *
 *   Abrir          -> AppRunner.open(key)            ventana de la app, proceso propio
 *   Comprobar todo -> en serie, para cada app: AppRunner.check(key, plazo) -> Report
 * }</pre>
 *
 * <p>Las comprobaciones van EN SERIE: camara y microfono son exclusivos. Durante ellas se abren
 * ventanas y el raton se mueve solo (picking 3D), de ahi el aviso previo.
 */
public class LauncherApp extends Application {

    private static final int GAP = 10;
    private static final int STATE_MAX_WIDTH = 520;
    private static final int WINDOW_WIDTH = 900;
    private static final int WINDOW_HEIGHT = 360;
    private static final String PENDING = "-";

    private final Map<AppId, Label> states = new EnumMap<>(AppId.class);
    private final Label total = new Label();
    private final Button checkAll = new Button("Comprobar todo");

    @Override
    public void start(Stage stage) {
        GridPane grid = new GridPane();
        grid.setHgap(GAP);
        grid.setVgap(GAP);
        int row = 0;
        for (AppId app : AppId.values()) {
            Label state = new Label(PENDING);
            state.setWrapText(true);
            state.setMaxWidth(STATE_MAX_WIDTH);
            states.put(app, state);
            Button open = new Button("Abrir");
            open.setOnAction(e -> open(app));
            grid.addRow(row++, new Label(app.title()), new Label(app.requirements()), open, state);
        }
        checkAll.setOnAction(e -> confirmAndCheckAll());

        VBox root = new VBox(GAP, grid, checkAll, total);
        root.setPadding(new Insets(GAP));
        stage.setTitle("NUI smoke test");
        stage.setScene(new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT));
        stage.show();
    }

    private void open(AppId app) {
        try {
            AppRunner.open(app.key());
        } catch (IOException e) {
            states.get(app).setText("FALLO - " + e);
        }
    }

    private void confirmAndCheckAll() {
        Alert warning = new Alert(Alert.AlertType.CONFIRMATION,
                "Se abriran ventanas y el raton se movera solo. No lo toques hasta que termine.");
        if (warning.showAndWait().filter(button -> button == ButtonType.OK).isEmpty()) {
            return;
        }
        checkAll.setDisable(true);
        total.setText("");
        Thread worker = new Thread(this::checkAllInSeries, "check-all");
        worker.setDaemon(true);
        worker.start();
    }

    private void checkAllInSeries() {
        int okCount = 0;
        for (AppId app : AppId.values()) {
            Platform.runLater(() -> states.get(app).setText("comprobando..."));
            Report report = AppRunner.check(app.key(), app.checkTimeoutSeconds());
            Platform.runLater(() -> states.get(app).setText(report.text()));
            if (report.ok()) {
                okCount++;
            }
        }
        String summary = okCount + " de " + AppId.values().length + " apps OK";
        Platform.runLater(() -> {
            total.setText(summary);
            checkAll.setDisable(false);
        });
    }
}
