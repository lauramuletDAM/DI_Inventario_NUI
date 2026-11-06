package es.dam.nui.smoketest;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Pellizco con tiempo de fijacion (anti "toque de Midas") sobre {@link HandModel}. La logica esta
 * en {@link DwellEngine}; este modo solo la alimenta y pinta el anillo de progreso.
 *
 * <pre>{@code
 *   frame --> Hand --> ratio de pellizco --> DwellEngine.update(ratio, ahora) --> estado, progreso
 *
 *   anillo en el punto medio entre pulgar (4) e indice (8):
 *      naranja = CHARGING   verde = CONFIRMED   arco = progreso (sentido horario desde arriba)
 *   texto: estado, pellizcos instantaneos, confirmados y ">>> SELECCIONAR_ELEMENTO" al confirmar
 * }</pre>
 *
 * <p>Cuantos mas pellizcos instantaneos que confirmados, mas "toques de Midas" evita la fijacion.
 */
final class DwellMode implements LiveMode {

    private static final int SQUARE_STROKE = 3;
    private static final int RING_STROKE = 6;
    private static final int RING_RADIUS = 30;
    private static final int ARC_START_DEGREES = 90;               // 90 = arriba; el arco negativo gira en sentido horario
    private static final int FULL_CIRCLE_DEGREES = 360;
    private static final int TEXT_X = 8;
    private static final int TEXT_Y = 18;

    private final HandModel hands;
    private DwellEngine engine = new DwellEngine();

    DwellMode(HandModel hands) {
        this.hands = hands;
    }

    @Override
    public String selfTest() {
        return DwellEngine.selfTest();
    }

    @Override
    public void prepareLive() {
        engine = new DwellEngine();                                // contadores a cero en cada sesion
    }

    @Override
    public boolean process(BufferedImage frame) throws Exception {
        HandModel.Hand hand = hands.detect(frame);
        Graphics2D g = frame.createGraphics();
        g.setStroke(new BasicStroke(SQUARE_STROKE));
        hand.drawSquare(g);
        boolean fired = false;
        int px = 0;
        int py = 0;
        if (hand.present()) {
            hand.drawBones(g);
            fired = engine.update(true, hand.pinchRatio(), System.currentTimeMillis());
            px = (hand.px(HandModel.THUMB_TIP) + hand.px(HandModel.INDEX_TIP)) / 2;
            py = (hand.py(HandModel.THUMB_TIP) + hand.py(HandModel.INDEX_TIP)) / 2;
        } else {
            engine.update(false, 1.0, System.currentTimeMillis());
        }
        if (engine.state != DwellEngine.State.IDLE) {
            g.setStroke(new BasicStroke(RING_STROKE));
            g.setColor(engine.state == DwellEngine.State.CONFIRMED ? Color.GREEN : Color.ORANGE);
            g.drawArc(px - RING_RADIUS, py - RING_RADIUS, 2 * RING_RADIUS, 2 * RING_RADIUS,
                    ARC_START_DEGREES, (int) (-FULL_CIRCLE_DEGREES * engine.progress));
        }
        g.setColor(Color.YELLOW);
        g.drawString("estado=" + engine.state + "  instantaneos=" + engine.instant + "  confirmados=" + engine.confirmed
                + (fired ? "  >>> SELECCIONAR_ELEMENTO" : ""), hand.cx() + TEXT_X, hand.cy() + TEXT_Y);
        g.dispose();
        return hand.present();
    }

    @Override
    public String hint() {
        return "Pellizca RAPIDO (no debe disparar) y luego MANTEN el pellizco ~1 s hasta completar el anillo";
    }
}
