package es.dam.nui.smoketest;

import java.awt.Color;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maquina de estados de los comandos de voz: palabra de activacion (wake word), intencion y
 * entidad. Mitiga el "toque de Midas" (activar cosas sin querer): sin "oye asistente" los
 * comandos se ignoran, y tras la activacion hay una ventana de tiempo limitada.
 *
 * <pre>{@code
 *                      "oye asistente"
 *      +---------+ ---------------------------> +-------------+
 *      | REPOSO  |                              | ESCUCHANDO  |  ventana de 6 s
 *      +---------+ <--- comando ejecutado ----- +-------------+
 *        |   ^     <--- tiempo agotado (tick) ---     |
 *        |   +-- cada comando exige nueva activacion
 *        +-- comando sin activar: IGNORADO
 *
 *   Frase                          Intencion   Entidad
 *   "modo oscuro" / "modo claro"   TEMA        oscuro | claro
 *   "pon fondo rojo|verde|azul"    FONDO       rojo | verde | azul
 *   "guardar documento"            GUARDAR     (ninguna)
 * }</pre>
 *
 * <p>Pura: sin audio ni UI, y el instante actual entra como parametro. Por eso {@link #selfTest()}
 * es determinista y no necesita microfono.
 *
 * <p>Limitacion: si la wake word y el comando van en la misma frase, sin pausa, solo se registra
 * la activacion y el comando se descarta.
 */
final class CommandEngine {

    enum State { REPOSO, ESCUCHANDO }

    record Intent(String name, String entity) { }

    static final long WINDOW_MS = 6000;
    private static final String WAKE_WORD = "oye asistente";
    private static final Pattern BACKGROUND = Pattern.compile("pon fondo (rojo|verde|azul)");
    private static final Color RED = new Color(180, 40, 40);
    private static final Color GREEN = new Color(40, 140, 60);
    private static final Color BLUE = new Color(40, 80, 180);

    State state = State.REPOSO;
    long deadline;
    boolean dark;
    Color background;
    int wakeWords;
    int executed;
    int ignored;
    String last = "Estado de reposo. Esperando 'oye asistente'";

    /** Caduca la ventana de escucha si ha pasado el plazo. */
    void tick(long now) {
        if (state == State.ESCUCHANDO && now > deadline) {
            state = State.REPOSO;
            last = "Tiempo agotado: vuelvo a reposo";
        }
    }

    /** Texto -> intencion y entidad; null si no es un comando conocido. */
    static Intent parse(String text) {
        if (text.contains("modo oscuro")) return new Intent("TEMA", "oscuro");
        if (text.contains("modo claro")) return new Intent("TEMA", "claro");
        if (text.contains("guardar documento")) return new Intent("GUARDAR", null);
        Matcher m = BACKGROUND.matcher(text);
        if (m.find()) return new Intent("FONDO", m.group(1));
        return null;
    }

    /** Procesa un texto reconocido en el instante {@code now}; devuelve el mensaje para la UI o null si no hay nada que mostrar. */
    String handle(String text, long now) {
        tick(now);
        if (text == null || text.isBlank()) return null;
        if (text.contains(WAKE_WORD)) {            // 1) wake word: abre (o renueva) la ventana y termina
            state = State.ESCUCHANDO;
            deadline = now + WINDOW_MS;
            wakeWords++;
            last = "Te escucho...";
            return last;
        }
        Intent in = parse(text);
        if (in == null) return null;
        if (state == State.REPOSO) {               // 2) comando sin activar: se ignora
            ignored++;
            last = "IGNORADO (falta la palabra de activacion): " + text;
            return last;
        }
        switch (in.name()) {                       // 3) comando valido en ventana: se ejecuta
            case "TEMA" -> { dark = in.entity().equals("oscuro"); background = null; last = "Tema " + in.entity(); }
            case "FONDO" -> {
                background = switch (in.entity()) {
                    case "rojo" -> RED;
                    case "verde" -> GREEN;
                    default -> BLUE;
                };
                last = "Fondo " + in.entity();
            }
            default -> last = "Documento guardado (simulado)";
        }
        executed++;
        state = State.REPOSO;                      // cada comando exige una nueva activacion
        last = "EJECUTADO [" + in.name() + (in.entity() != null ? ":" + in.entity() : "") + "] -> " + last;
        return last;
    }

    /**
     * Autotest con tiempos simulados: (1) comando sin wake word -> ignorado; (2) wake word +
     * comando -> ejecutado; (3) comando en reposo -> ignorado; (4) comando tras caducar la
     * ventana -> ignorado. Esperado: 1 ejecutado, 3 ignorados, 2 activaciones.
     *
     * @return resumen de contadores; lanza excepcion si algo no es lo esperado
     */
    static String selfTest() {
        CommandEngine e = new CommandEngine();
        long t = 0;
        String r1 = e.handle("pon fondo rojo", t);
        if (r1 == null || !r1.startsWith("IGNORADO")) throw new IllegalStateException("un comando sin wake word debia ignorarse");
        e.handle(WAKE_WORD, t + 100);
        if (e.state != State.ESCUCHANDO) throw new IllegalStateException("la wake word debia activar la escucha");
        e.handle("pon fondo rojo", t + 200);
        if (e.executed != 1 || e.background == null) throw new IllegalStateException("el comando debia ejecutarse");
        e.handle("modo oscuro", t + 300);                              // ya en reposo: se ignora
        e.handle(WAKE_WORD, t + 400);
        e.handle("guardar documento", t + 400 + WINDOW_MS + 1);        // ventana caducada: se ignora
        if (e.executed != 1 || e.ignored != 3 || e.wakeWords != 2)
            throw new IllegalStateException("contadores inesperados " + e.executed + "/" + e.ignored + "/" + e.wakeWords);
        return e.executed + " ejecutado, " + e.ignored + " ignorados, " + e.wakeWords + " activaciones";
    }
}
