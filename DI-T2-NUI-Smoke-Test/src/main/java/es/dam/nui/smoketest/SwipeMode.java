package es.dam.nui.smoketest;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;

/**
 * Gesto dinamico (deslizamiento horizontal) sobre {@link HandModel}, usando la muneca (punto 0).
 * La logica esta en {@link SwipeEngine}; este modo la alimenta y dibuja la estela de la muneca.
 *
 * <pre>{@code
 *   frame --> Hand --> muneca (x, y) normalizada --> SwipeEngine.update --> "DERECHA" | "IZQUIERDA" | nada
 *
 *   estela naranja: ultimas 15 posiciones de la muneca     texto verde SWIPE ... durante 1 s
 * }</pre>
 */
final class SwipeMode implements LiveMode {

    private static final int SQUARE_STROKE = 3;
    private static final int TRAIL_LENGTH = 15;
    private static final long LABEL_MS = 1000;
    private static final int COUNTER_FONT_SIZE = 13;
    private static final int LABEL_FONT_SIZE = 36;
    private static final int TEXT_X = 8;
    private static final int TEXT_Y = 18;
    private static final int LABEL_X = 20;

    private final HandModel hands;
    private SwipeEngine engine = new SwipeEngine();
    private final ArrayDeque<int[]> trail = new ArrayDeque<>();    // posiciones de la muneca en pixeles
    private int right;
    private int left;
    private String lastLabel = "";
    private long labelUntil;

    SwipeMode(HandModel hands) {
        this.hands = hands;
    }

    @Override
    public String selfTest() {
        return SwipeEngine.selfTest();
    }

    @Override
    public void prepareLive() {
        engine = new SwipeEngine();                                // contadores a cero en cada sesion
        trail.clear();
        right = 0;
        left = 0;
        lastLabel = "";
        labelUntil = 0;
    }

    @Override
    public boolean process(BufferedImage frame) throws Exception {
        HandModel.Hand hand = hands.detect(frame);
        long now = System.currentTimeMillis();
        Graphics2D g = frame.createGraphics();
        g.setStroke(new BasicStroke(SQUARE_STROKE));
        hand.drawSquare(g);
        if (hand.present()) {
            trail.addLast(new int[] {hand.px(HandModel.WRIST), hand.py(HandModel.WRIST)});
            while (trail.size() > TRAIL_LENGTH) {
                trail.removeFirst();
            }
            String swipe = engine.update(true, hand.nx(HandModel.WRIST), hand.ny(HandModel.WRIST), now);
            if (swipe != null) {
                if (swipe.equals(SwipeEngine.RIGHT)) {
                    right++;
                } else {
                    left++;
                }
                lastLabel = swipe;
                labelUntil = now + LABEL_MS;
            }
        } else {
            trail.clear();
            engine.update(false, 0, 0, now);
        }
        g.setColor(Color.ORANGE);
        int[] prev = null;
        for (int[] p : trail) {
            if (prev != null) {
                g.drawLine(prev[0], prev[1], p[0], p[1]);
            }
            prev = p;
        }
        g.setColor(Color.YELLOW);
        g.setFont(new Font("SansSerif", Font.PLAIN, COUNTER_FONT_SIZE));
        g.drawString("deslizamientos: derecha=" + right + " izquierda=" + left, hand.cx() + TEXT_X, hand.cy() + TEXT_Y);
        if (now < labelUntil) {
            g.setColor(Color.GREEN);
            g.setFont(new Font("SansSerif", Font.BOLD, LABEL_FONT_SIZE));
            g.drawString("SWIPE " + lastLabel, hand.cx() + LABEL_X, hand.cy() + hand.side() / 2);
        }
        g.dispose();
        return hand.present();
    }

    @Override
    public String hint() {
        return "Mano abierta en el cuadrado central: desliza la mano rapido a tu derecha y a tu izquierda";
    }
}
