package es.dam.nui.smoketest;

/**
 * Gesto de pellizco con tiempo de fijacion (dwell time): el "clic" solo se dispara si el pellizco
 * se mantiene {@link #DWELL_MS}. Evita el "toque de Midas" (cualquier roce accidental activaria la
 * accion). Cuenta pellizcos instantaneos (lo que haria un sistema sin fijacion) y confirmados.
 *
 * <pre>{@code
 *                  pellizco (ema menor que 0.25)       progreso llega a 1.0 (800 ms)
 *     +------+ ---------------------------------> +----------+ ---------------> +-----------+
 *     | IDLE |                                    | CHARGING |                  | CONFIRMED |
 *     +------+ <--------------------------------- +----------+                  +-----------+
 *        ^           suelta antes de 800 ms                                           |
 *        +----------------------------------------------------------------------------+
 *                    suelta durante 300 ms seguidos (rearme)
 *
 *   Histeresis sobre el ratio suavizado (ema):
 *      ratio:  0.0 ----------- 0.25 ========= 0.35 ----------- 1.0
 *                  pellizco     ^ENTRA   zona muerta    EXIT^   mano abierta
 *
 *   Traza del autotest (valores obtenidos ejecutando el motor):
 *      t = 1066 ms   empieza un pellizco accidental            (instant=1)
 *      t = 1133 ms   se suelta a los ~70 ms: no confirma
 *      t = 1566 ms   empieza un pellizco sostenido             (instant=2)
 *      t = 2391 ms   ~800 ms despues: CONFIRMA                 (confirmed=1)
 *      t = 2930 ms   300 ms con la mano abierta: rearma (IDLE)
 *      t = 3166 ms   empieza el tercer pellizco                (instant=3)
 *      t = 3991 ms   CONFIRMA                                  (confirmed=2)
 * }</pre>
 *
 * <p>Suavizado: media movil exponencial {@code ema = 0.5*ema + 0.5*ratio}. Pura: sin camara, y el
 * instante actual entra como parametro, asi que se prueba con datos sinteticos.
 */
final class DwellEngine {

    enum State { IDLE, CHARGING, CONFIRMED }

    static final long DWELL_MS = 800;
    static final long REARM_MS = 300;
    private static final double ENTER = 0.25;
    private static final double EXIT = 0.35;
    private static final double SMOOTHING = 0.5;
    private static final long FRAME_MS = 33;                      // ~30 fps en el autotest

    State state = State.IDLE;
    double progress;
    int instant;
    int confirmed;

    private double ema;
    private boolean pinching;
    private boolean first = true;
    private long since;
    private long releaseSince = -1;

    /** Mano ausente: todo vuelve al estado inicial. */
    private void reset() {
        state = State.IDLE;
        pinching = false;
        progress = 0;
        first = true;
        releaseSince = -1;
    }

    /**
     * Avanza la maquina un fotograma.
     *
     * @param handPresent false si no se ve la mano (se reinicia todo)
     * @param ratio       pellizco instantaneo (menor = mas cerrado)
     * @param now         instante actual en ms
     * @return true exactamente en el instante en que el gesto queda confirmado
     */
    boolean update(boolean handPresent, double ratio, long now) {
        if (!handPresent) {
            reset();
            return false;
        }
        ema = first ? ratio : SMOOTHING * ema + (1 - SMOOTHING) * ratio;
        first = false;
        boolean was = pinching;
        if (!pinching && ema < ENTER) {
            pinching = true;                                      // histeresis: entra por debajo de ENTER...
        } else if (pinching && ema > EXIT) {
            pinching = false;                                     // ...y sale por encima de EXIT
        }
        if (pinching && !was) {                                   // flanco de subida: pellizco nuevo
            instant++;
            since = now;
        }
        if (!pinching) {
            progress = 0;
            if (state == State.CONFIRMED) {                       // hay que mantener la suelta REARM_MS antes de rearmar
                if (releaseSince < 0) {
                    releaseSince = now;
                }
                if (now - releaseSince >= REARM_MS) {
                    state = State.IDLE;
                    releaseSince = -1;
                }
            } else {
                state = State.IDLE;
            }
            return false;
        }
        releaseSince = -1;
        if (state == State.IDLE) {
            state = State.CHARGING;
        }
        if (state == State.CHARGING) {
            progress = Math.min(1.0, (now - since) / (double) DWELL_MS);
            if (progress >= 1.0) {                                // fijacion completada: se confirma una sola vez
                state = State.CONFIRMED;
                confirmed++;
                return true;
            }
        } else {
            progress = 1.0;
        }
        return false;
    }

    /** Alimenta el motor con un ratio constante a ~30 fps entre dos instantes. */
    private static void feed(DwellEngine e, double ratio, long from, long to) {
        for (long t = from; t <= to; t += FRAME_MS) {
            e.update(true, ratio, t);
        }
    }

    /**
     * Escenario sintetico: pellizco accidental de 100 ms (cuenta como instantaneo, no confirma),
     * pellizco sostenido de 1 s (confirma), soltar y rearmar, segundo pellizco sostenido (confirma).
     * Esperado: instant=3, confirmed=2.
     */
    static String selfTest() {
        DwellEngine e = new DwellEngine();
        feed(e, 0.8, 0, 900);
        feed(e, 0.1, 1000, 1100);      // pellizco accidental breve
        feed(e, 0.8, 1133, 1400);
        feed(e, 0.1, 1500, 2500);      // pellizco sostenido: confirma
        feed(e, 0.8, 2600, 3000);      // soltar y rearmar
        feed(e, 0.1, 3100, 4100);      // segundo pellizco sostenido: confirma
        if (e.instant != 3 || e.confirmed != 2) {
            throw new IllegalStateException("esperado instant=3 confirmed=2, obtenido " + e.instant + "/" + e.confirmed);
        }
        return e.instant + " pellizcos, " + e.confirmed + " confirmados con " + DWELL_MS + " ms de fijacion";
    }
}
