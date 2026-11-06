package es.dam.nui.smoketest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

/**
 * Base de las apps del smoke test. Cada app declara:
 * <ul>
 *   <li>{@link #steps()}: sus comprobaciones AUTOMATICAS (sin persona delante), en orden;</li>
 *   <li>{@link #buildUi}: su ventana interactiva.</li>
 * </ul>
 *
 * <pre>{@code
 *   start(stage)
 *     |-- sin argumentos   -> buildUi(stage)                       modo interactivo
 *     '-- con --check      -> check() en un hilo -> "RESULT ..." -> System.exit(0 | 1)
 *
 *   check():  paso 1 --OK--> "nombre: resumen"
 *             paso 2 --excepcion--> "nombre: FALLO (...)"   (se siguen ejecutando los demas)
 *             => Report(ok = todos los pasos OK, resumen = pasos unidos por " | ")
 * }</pre>
 *
 * <p>Los pasos se ejecutan fuera del hilo de JavaFX, pero con el toolkit ya arrancado: pueden
 * usar {@code Platform.runLater}, {@code Robot}, etc. Falta de camara o microfono es FALLO: se
 * valida el equipo, no el codigo.
 */
public abstract class NuiApp extends Application {

    static final String CHECK_FLAG = "--check";
    static final String SEPARATOR = " | ";
    private static final int EXIT_OK = 0;
    private static final int EXIT_FAIL = 1;

    /** Comprobaciones automaticas, en el orden en que se ejecutan; cada una devuelve un resumen corto o lanza excepcion. */
    protected abstract Map<String, Callable<String>> steps();

    /** Construye y muestra la ventana interactiva. */
    protected abstract void buildUi(Stage stage);

    @Override
    public final void start(Stage stage) {
        if (getParameters().getRaw().contains(CHECK_FLAG)) {
            runCheckAndExit();
        } else {
            buildUi(stage);
        }
    }

    /** Ejecuta todos los pasos y devuelve el informe; nunca lanza. Bloquea: llamarlo fuera del hilo de JavaFX. */
    protected final Report check() {
        List<String> lines = new ArrayList<>();
        boolean ok = true;
        for (Map.Entry<String, Callable<String>> step : steps().entrySet()) {
            try {
                lines.add(step.getKey() + ": " + step.getValue().call());
            } catch (Throwable e) {   // Throwable: incluye UnsatisfiedLinkError de librerias nativas
                ok = false;
                lines.add(step.getKey() + ": FALLO (" + e + ")");
                e.printStackTrace();
            }
        }
        return new Report(ok, String.join(SEPARATOR, lines));
    }

    private void runCheckAndExit() {
        Thread worker = new Thread(() -> {
            Report report = check();
            System.out.println(report.toLine());
            Platform.exit();
            System.exit(report.ok() ? EXIT_OK : EXIT_FAIL);   // exit explicito: hay hilos nativos que no son daemon
        }, "check");
        worker.start();
    }
}
