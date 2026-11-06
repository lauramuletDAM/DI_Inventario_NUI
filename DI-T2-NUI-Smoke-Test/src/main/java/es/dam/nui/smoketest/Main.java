package es.dam.nui.smoketest;

import java.util.Arrays;

import javafx.application.Application;

/**
 * Punto de entrada unico. NO hereda de {@code Application} (sustituye al antiguo {@code Launcher}):
 * asi JavaFX puede cargarse desde el classpath sin el error "JavaFX runtime components are missing".
 *
 * <pre>{@code
 *   Main                    -> LauncherApp   lista de apps y "Comprobar todo"
 *   Main entorno            -> EntornoApp    ventana interactiva
 *   Main entorno --check    -> EntornoApp    autotest sin ventana; escribe "RESULT ..." y termina
 * }</pre>
 *
 * <p>JavaFX solo permite un {@code Application.launch} por JVM; por eso cada app es un proceso
 * distinto (ver {@link AppRunner}).
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            Application.launch(LauncherApp.class);
            return;
        }
        AppId app = AppId.fromKey(args[0]);
        Application.launch(app.appClass(), Arrays.copyOfRange(args, 1, args.length));
    }
}
