package es.dam.nui.smoketest;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Dibuja el estado de un {@link CommandEngine} como imagen: luz verde/gris, cuenta atras, ultimo
 * mensaje y contadores. El fondo cambia con el tema o el color elegido por voz.
 *
 * <pre>{@code
 *   +--------------------------------------------------+
 *   | (o) ESCUCHANDO (4 s)                              |   luz verde = escuchando, gris = reposo
 *   |  EJECUTADO [FONDO:rojo] -> Fondo rojo             |   ultimo mensaje
 *   |  Activaciones: 2   Ejecutados: 1   Ignorados: 3   |
 *   |  Di: oye asistente, y despues: (comandos)         |
 *   +--------------------------------------------------+
 * }</pre>
 */
final class CommandView {

    static final int WIDTH = 560;
    private static final int HEIGHT = 240;
    private static final int MARGIN = 20;
    private static final int LED_SIZE = 28;
    private static final int TITLE_X = 60;
    private static final int TITLE_Y = 42;
    private static final int LAST_Y = 90;
    private static final int COUNTERS_Y = 130;
    private static final int HELP_Y = 190;
    private static final int HELP_LINE_HEIGHT = 22;
    private static final int TITLE_FONT_SIZE = 20;
    private static final int TEXT_FONT_SIZE = 14;
    private static final long MS_PER_SECOND = 1000;
    private static final Color LIGHT_BACKGROUND = new Color(238, 238, 238);
    private static final Color DARK_BACKGROUND = new Color(34, 34, 34);
    private static final Color LISTENING = new Color(60, 200, 90);

    private CommandView() {
    }

    static BufferedImage render(CommandEngine e, long now) {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Color bg = e.background != null ? e.background : (e.dark ? DARK_BACKGROUND : LIGHT_BACKGROUND);
        Color fg = (e.dark || e.background != null) ? Color.WHITE : Color.BLACK;
        g.setColor(bg);
        g.fillRect(0, 0, WIDTH, HEIGHT);
        boolean listening = e.state == CommandEngine.State.ESCUCHANDO;
        g.setColor(listening ? LISTENING : Color.GRAY);
        g.fillOval(MARGIN, MARGIN, LED_SIZE, LED_SIZE);
        g.setColor(fg);
        g.setFont(new Font("SansSerif", Font.BOLD, TITLE_FONT_SIZE));
        long left = Math.max(0, e.deadline - now);
        g.drawString(listening ? "ESCUCHANDO (" + (left / MS_PER_SECOND + 1) + " s)" : "REPOSO", TITLE_X, TITLE_Y);
        g.setFont(new Font("SansSerif", Font.PLAIN, TEXT_FONT_SIZE));
        g.drawString(e.last, MARGIN, LAST_Y);
        g.drawString("Activaciones: " + e.wakeWords + "   Ejecutados: " + e.executed + "   Ignorados: " + e.ignored, MARGIN, COUNTERS_Y);
        g.drawString("Di: oye asistente, y despues:", MARGIN, HELP_Y);
        g.drawString("modo oscuro | modo claro | pon fondo rojo/verde/azul | guardar documento", MARGIN, HELP_Y + HELP_LINE_HEIGHT);
        g.dispose();
        return img;
    }
}
