package es.dam.nui.smoketest;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.HybridBinarizer;

/**
 * QR con ZXing: generar, leer y usar el codigo como ANCLA de realidad aumentada. Une las antiguas
 * pruebas 3 (QR) y 6 (RA con QR). ZXing no solo devuelve el texto, tambien la posicion del QR en
 * la imagen, que es lo que permite dibujar contenido "pegado" al codigo.
 *
 * <pre>{@code
 *   GENERAR                                      LEER
 *   texto --MultiFormatWriter.encode--> BitMatrix    BufferedImage
 *                                          |            |  BufferedImageLuminanceSource  (a grises)
 *                       MatrixToImageWriter|            v
 *                                          v         HybridBinarizer  (blanco/negro con umbral local)
 *                                    BufferedImage      |
 *                                          '---------> BinaryBitmap --MultiFormatReader.decode--> Result
 *
 *   Result.getResultPoints():   TL ------ TR     centros de los 3 patrones de busqueda
 *                               |                y, si el QR lo tiene, el de alineacion
 *                               BL                (el orden lo fija ZXing)
 * }</pre>
 *
 * <p>Pistas ({@code DecodeHintType}): {@code POSSIBLE_FORMATS} limita la busqueda a QR y
 * {@code TRY_HARDER} aumenta la tolerancia a ruido y perspectiva a costa de CPU.
 */
final class QrMode implements LiveMode {

    private static final String SAMPLE_TEXT = "2 DAM - Desarrollo de Interfaces - NUI";
    private static final String ANCHOR_TEXT = "ANCLA-RA";
    private static final int QR_SIZE = 300;
    private static final int CANVAS_WIDTH = 500;
    private static final int CANVAS_HEIGHT = 400;
    private static final int QR_X = 100;
    private static final int QR_Y = 50;
    private static final int MIN_CORNER_POINTS = 3;
    private static final int STROKE_WIDTH = 4;
    private static final int POINT_RADIUS = 6;
    private static final int LABEL_OFFSET = 12;
    private static final int MIN_LABEL_Y = 15;
    private static final Map<DecodeHintType, Object> HINTS = Map.of(
            DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER, Boolean.TRUE);

    @Override
    public String selfTest() throws Exception {
        String decoded = decode(encode(SAMPLE_TEXT)).getText();       // ida y vuelta
        if (!SAMPLE_TEXT.equals(decoded)) {
            throw new IllegalStateException("lectura distinta: " + decoded);
        }
        BufferedImage canvas = new BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);
        g.drawImage(encode(ANCHOR_TEXT), QR_X, QR_Y, null);
        g.dispose();
        int points = decode(canvas).getResultPoints().length;           // sin esquinas no hay ancla util
        if (points < MIN_CORNER_POINTS) {
            throw new IllegalStateException("ZXing no devolvio puntos de esquina");
        }
        return "ida y vuelta OK, " + points + " puntos de esquina";
    }

    @Override
    public boolean process(BufferedImage frame) {
        try {
            overlay(frame, decode(frame));
            return true;
        } catch (NotFoundException e) {
            return false;                                              // fotograma sin QR: caso normal
        }
    }

    @Override
    public String hint() {
        return "Ensena un QR (p. ej. en el movil) a la camara";
    }

    private static BufferedImage encode(String text) throws WriterException {
        return MatrixToImageWriter.toBufferedImage(
                new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE));
    }

    private static Result decode(BufferedImage image) throws NotFoundException {
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        return new MultiFormatReader().decode(bitmap, HINTS);
    }

    /** Une cada punto con el siguiente (y el ultimo con el primero), marca los vertices y escribe "RA: contenido". */
    private static void overlay(BufferedImage image, Result result) {
        ResultPoint[] p = result.getResultPoints();
        Graphics2D g = image.createGraphics();
        g.setStroke(new BasicStroke(STROKE_WIDTH));
        g.setColor(Color.GREEN);
        for (int i = 0; i < p.length; i++) {
            ResultPoint a = p[i];
            ResultPoint b = p[(i + 1) % p.length];
            g.drawLine((int) a.getX(), (int) a.getY(), (int) b.getX(), (int) b.getY());
            g.fillOval((int) a.getX() - POINT_RADIUS, (int) a.getY() - POINT_RADIUS, 2 * POINT_RADIUS, 2 * POINT_RADIUS);
        }
        g.setColor(Color.YELLOW);
        g.drawString("RA: " + result.getText(), (int) p[0].getX(), Math.max(MIN_LABEL_Y, (int) p[0].getY() - LABEL_OFFSET));
        g.dispose();
    }
}
