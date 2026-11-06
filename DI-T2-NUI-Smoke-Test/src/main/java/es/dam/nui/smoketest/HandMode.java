package es.dam.nui.smoketest;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Esqueleto de la mano y deteccion de pellizco con un unico umbral. Es el modo mas simple sobre
 * {@link HandModel}; sirve para comprobar que el modelo ONNX funciona y que la mano se ve.
 *
 * <pre>{@code
 *   frame --HandModel.detect--> Hand --present?--> huesos (cian) + puntos (rojo) + ratio de pellizco
 *                                                  ratio menor que 0.25  =>  "PELLIZCO" (verde)
 * }</pre>
 *
 * <p>Un solo umbral basta aqui; {@link DwellMode} lo mejora con histeresis y fijacion.
 */
final class HandMode implements LiveMode {

    private static final double PINCH_THRESHOLD = 0.25;
    private static final int STROKE_WIDTH = 2;
    private static final int TEXT_X = 8;
    private static final int TEXT_Y = 18;

    private final HandModel hands;

    HandMode(HandModel hands) {
        this.hands = hands;
    }

    @Override
    public String selfTest() throws Exception {
        return hands.selfTest();
    }

    @Override
    public boolean process(BufferedImage frame) throws Exception {
        HandModel.Hand hand = hands.detect(frame);
        Graphics2D g = frame.createGraphics();
        g.setStroke(new BasicStroke(STROKE_WIDTH));
        hand.drawSquare(g);
        if (hand.present()) {
            hand.drawBones(g);
            hand.drawPoints(g);
            double ratio = hand.pinchRatio();
            boolean pinch = ratio < PINCH_THRESHOLD;
            g.setColor(pinch ? Color.GREEN : Color.YELLOW);
            g.drawString(String.format("conf=%.2f pellizco=%.2f %s", hand.conf(), ratio, pinch ? "PELLIZCO" : ""),
                    hand.cx() + TEXT_X, hand.cy() + TEXT_Y);
        }
        g.dispose();
        return hand.present();
    }

    @Override
    public String hint() {
        return "Pon la mano abierta DENTRO del cuadrado central y prueba a pellizcar";
    }
}
