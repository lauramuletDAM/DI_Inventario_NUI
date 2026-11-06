package es.dam.nui.smoketest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Gesto DINAMICO: deslizamiento horizontal de la mano. A diferencia del pellizco, que es una
 * postura, aqui importa la trayectoria en el tiempo.
 *
 * <pre>{@code
 *   Muneca (punto 0) -> (x, y) normalizada 0..1 -> ventana deslizante de 500 ms -> update
 *
 *   Los puntos mas viejos que 500 ms se descartan
 *        x ^
 *    0.7 - o o o o o
 *          .          o
 *    0.4 -              o   <- dx = x_actual - x_mas_antiguo = -0.30 o menos  =>  swipe
 *    0.3 -                o o o
 *          +------------------------> tiempo (ms)
 *
 *   Dispara si  |dx| >= 0.30  y  |dy| menor que 0.5 * |dx|   (rapido, largo y casi horizontal)
 *   Despues: enfriamiento (cooldown) de 800 ms y se vacia la ventana (evita dobles disparos).
 *
 *   Direccion: la camara ve al usuario de frente y la imagen no esta reflejada; mover la mano a SU
 *   derecha REDUCE x en la imagen (dx negativo) -> "DERECHA"; a su izquierda -> "IZQUIERDA".
 *
 *   Traza del autotest (ejecutando el motor):  t=1231 ms DERECHA ; t=2731 ms IZQUIERDA ; la
 *   diagonal final (dx=-0.4, dy=0.8) no dispara porque 0.8 no es menor que 0.5 * 0.4.
 * }</pre>
 *
 * <p>Pura: sin camara y con el tiempo como parametro, se prueba con trayectorias sinteticas.
 */
final class SwipeEngine {

    static final String RIGHT = "DERECHA";
    static final String LEFT = "IZQUIERDA";
    private static final long WINDOW_MS = 500;
    private static final long COOLDOWN_MS = 800;
    private static final double MIN_DX = 0.30;                    // 30 % del lado del cuadrado central
    private static final double MAX_SLOPE = 0.5;
    private static final long FRAME_MS = 33;

    private static final int TIME = 0;                            // posiciones dentro de cada punto {t, x, y}
    private static final int X = 1;
    private static final int Y = 2;

    private final ArrayDeque<double[]> points = new ArrayDeque<>();
    private long cooldownUntil;
    int swipes;

    /**
     * Registra la posicion de la mano y detecta un deslizamiento.
     *
     * @param x x normalizada (0..1) en la imagen de la camara
     * @param y y normalizada (0..1) en la imagen de la camara
     * @return "DERECHA" o "IZQUIERDA" (desde el punto de vista del usuario), o null si no hay swipe
     */
    String update(boolean present, double x, double y, long now) {
        if (!present) {
            points.clear();                                       // perder la mano no genera falsos swipes
            return null;
        }
        if (now < cooldownUntil) {
            return null;                                          // durante el enfriamiento se ignoran los puntos
        }
        points.addLast(new double[] {now, x, y});
        while (!points.isEmpty() && now - points.peekFirst()[TIME] > WINDOW_MS) {
            points.removeFirst();
        }
        double[] oldest = points.peekFirst();                     // referencia para medir el desplazamiento
        double dx = x - oldest[X];
        double dy = y - oldest[Y];
        if (Math.abs(dx) >= MIN_DX && Math.abs(dy) < MAX_SLOPE * Math.abs(dx)) {
            swipes++;
            cooldownUntil = now + COOLDOWN_MS;
            points.clear();
            return dx < 0 ? RIGHT : LEFT;
        }
        return null;
    }

    /** Trayectoria lineal entre dos puntos a ~30 fps; recoge los eventos del motor. */
    private static void move(SwipeEngine e, List<String> events, double x0, double y0, double x1, double y1, long t0, long t1) {
        for (long t = t0; t <= t1; t += FRAME_MS) {
            double k = t1 == t0 ? 1 : (double) (t - t0) / (t1 - t0);
            String r = e.update(true, x0 + (x1 - x0) * k, y0 + (y1 - y0) * k, t);
            if (r != null) {
                events.add(r);
            }
        }
    }

    /**
     * Escenario sintetico: quieta, swipe rapido a x menor (DERECHA), quieta, swipe rapido a x
     * mayor (IZQUIERDA), quieta, diagonal (no cuenta). Esperado: [DERECHA, IZQUIERDA].
     */
    static String selfTest() {
        SwipeEngine e = new SwipeEngine();
        List<String> ev = new ArrayList<>();
        move(e, ev, 0.7, 0.1, 0.7, 0.1, 0, 1000);                // quieta
        move(e, ev, 0.7, 0.1, 0.3, 0.1, 1000, 1300);             // rapido hacia x menor -> DERECHA
        move(e, ev, 0.3, 0.1, 0.3, 0.1, 1300, 2500);
        move(e, ev, 0.3, 0.1, 0.7, 0.1, 2500, 2800);             // rapido hacia x mayor -> IZQUIERDA
        move(e, ev, 0.7, 0.1, 0.7, 0.1, 2800, 3600);
        move(e, ev, 0.7, 0.1, 0.3, 0.9, 3600, 3900);             // diagonal: no es deslizamiento horizontal
        if (!ev.equals(List.of(RIGHT, LEFT))) {
            throw new IllegalStateException("eventos esperados [DERECHA, IZQUIERDA], obtenidos " + ev);
        }
        return "eventos " + ev;
    }
}
