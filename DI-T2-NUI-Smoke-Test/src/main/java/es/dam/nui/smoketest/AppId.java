package es.dam.nui.smoketest;

import java.util.Arrays;

/**
 * Catalogo de apps: la lista que muestra el launcher y la tabla que usa {@link Main} para saber
 * que clase arrancar. Anadir una app = crear su clase (extiende {@link NuiApp}) y una linea aqui.
 */
enum AppId {

    ENTORNO("entorno", "Entorno", "Internet, camara, 3D", EntornoApp.class, 60),
    VOZ("voz", "Voz", "Microfono, Internet (1a vez)", VozApp.class, 180),
    MARCADORES("marcadores", "Marcadores y RA", "Camara (en vivo), Internet (1a vez), 3D", MarcadoresApp.class, 120),
    GESTOS("gestos", "Manos y cuerpo", "Camara (en vivo), Internet (1a vez); mueve el raton", GestosApp.class, 180),
    CLASIFICACION("clasificacion", "Clasificacion", "Internet (1a vez: PyTorch + ResNet, varios minutos)", ClasificacionApp.class, 900);

    private final String key;
    private final String title;
    private final String requirements;
    private final Class<? extends NuiApp> appClass;
    private final long checkTimeoutSeconds;

    AppId(String key, String title, String requirements, Class<? extends NuiApp> appClass, long checkTimeoutSeconds) {
        this.key = key;
        this.title = title;
        this.requirements = requirements;
        this.appClass = appClass;
        this.checkTimeoutSeconds = checkTimeoutSeconds;
    }

    /** Nombre corto usado en la linea de comandos ({@code Main entorno}). */
    String key() {
        return key;
    }

    String title() {
        return title;
    }

    /** Hardware o red que necesita, para mostrarlo en el launcher. */
    String requirements() {
        return requirements;
    }

    Class<? extends NuiApp> appClass() {
        return appClass;
    }

    /** Plazo maximo de {@code --check}; mayor en las apps que descargan modelos la primera vez. */
    long checkTimeoutSeconds() {
        return checkTimeoutSeconds;
    }

    static AppId fromKey(String key) {
        return Arrays.stream(values())
                .filter(app -> app.key.equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "App desconocida: " + key + ". Disponibles: "
                                + Arrays.stream(values()).map(AppId::key).toList()));
    }
}
