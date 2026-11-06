package es.dam.nui.smoketest;

/**
 * App "Manos y cuerpo": gestos con la camara. Une las antiguas pruebas 10 (manos), 12 (dwell),
 * 13 (swipe), 14 (pose) y 15 (picking 3D). Un unico {@link HandModel} (una inferencia por
 * fotograma) alimenta cuatro modos que solo se diferencian en como INTERPRETAN los 21 puntos.
 *
 * <pre>{@code
 *                       +--> HandMode     esqueleto + pellizco con un umbral
 *   Camera --> HandModel +--> DwellMode    pellizco con fijacion de 800 ms        (DwellEngine)
 *   (1 inferencia)       +--> SwipeMode    deslizamiento de la muneca             (SwipeEngine)
 *                        '--> PickingMode  yema del indice -> cursor -> picking 3D (ventana auxiliar)
 *
 *   Camera --> PoseMode (otro modelo: pose corporal, angulos del codo, brazo levantado)
 * }</pre>
 *
 * <p>La ventana y el bucle de camara los aporta {@link LiveApp}. Atencion: {@link PickingMode}
 * mueve el cursor real del sistema (tambien durante {@code --check}).
 */
public class GestosApp extends LiveApp {

    private final HandModel hands = new HandModel();

    public GestosApp() {
        modes.put("Manos", new HandMode(hands));
        modes.put("Dwell", new DwellMode(hands));
        modes.put("Swipe", new SwipeMode(hands));
        modes.put("Pose", new PoseMode());
        modes.put("Picking 3D", new PickingMode(hands));         // el ultimo: mueve el raton
    }

    @Override
    protected String title() {
        return "Manos y cuerpo";
    }

    @Override
    public void stop() {
        super.stop();
        hands.close();                                            // el modelo de manos es compartido: lo cierra la app
    }
}
