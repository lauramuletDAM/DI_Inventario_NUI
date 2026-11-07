package tecnodam.inventario;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import javafx.collections.ObservableList;

final class QrMode {

    private final ObservableList<Articulo> catalogo;
    private final Consumer<Articulo> onConfirmarRecepcion;
    private final Runnable onCerrarVentana;
    private final MultiFormatReader reader = new MultiFormatReader();

    private Articulo articuloPendiente = null;
    private String ultimoCodigoDetectado = "";
    private long ultimaLecturaTimestamp = 0;
    private static final long COOLDOWN_QR_MS = 3000;

    // Control del mensaje de éxito en pantalla
    private String mensajeExito = "";
    private long tiempoExito = 0;
    private static final long DURACION_MENSAJE_EXITO_MS = 3000;

    // Control de inactividad basado estrictamente en el tiempo sin leer QRs (3.5 segundos)
    private long ultimoEscaneoOAccionTimestamp = System.currentTimeMillis();
    private static final long PAUSE_TIMEOUT_MS = 3500;

    // Control de pinza con mayor sensibilidad y rapidez (0.24 y 200ms)
    private long timestampQrLeido = 0;
    private boolean manoAbiertaDetectada = false;
    private static final double PINCH_THRESHOLD = 0.24;
    private long pinchStartTime = 0;
    private static final long PINCH_HOLD_DURATION_MS = 200;
    private long ultimaPinzaTimestamp = 0;

    // Control de Swipe (derecha a izquierda) protegido
    private int ultimaXMuñeca = -1;
    private long ultimoSwipeTimestamp = 0;

    QrMode(ObservableList<Articulo> catalogo, Consumer<Articulo> onConfirmarRecepcion, Runnable onCerrarVentana) {
        this.catalogo = catalogo;
        this.onConfirmarRecepcion = onConfirmarRecepcion;
        this.onCerrarVentana = onCerrarVentana;
    }

    boolean process(BufferedImage frame, Consumer<String> actualizadorMensaje, HandModel hands) {
        long ahora = System.currentTimeMillis();

        // 1. Priorizar el mensaje de éxito si se acaba de confirmar una recepción
        if (!mensajeExito.isEmpty() && (ahora - tiempoExito) < DURACION_MENSAJE_EXITO_MS) {
            actualizadorMensaje.accept(mensajeExito);
            return true;
        } else if (!mensajeExito.isEmpty()) {
            mensajeExito = "";
            articuloPendiente = null; 
            ultimoEscaneoOAccionTimestamp = ahora;
        }

        String textoQr = null;
        try {
            LuminanceSource source = new BufferedImageLuminanceSource(frame);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Result resultado = reader.decode(bitmap);
            textoQr = resultado.getText();
        } catch (NotFoundException ignored) {
        } catch (Exception ignored) {}

        Graphics2D g2d = frame.createGraphics();
        boolean detectado = false;
        int ancho = frame.getWidth();
        int alto = frame.getHeight();

        // 2. LECTURA DEL QR (Queda pendiente sin sumar automáticamente)
        if (textoQr != null && !textoQr.isBlank() && articuloPendiente == null && mensajeExito.isEmpty()) {
            String codigoLeido = textoQr.trim();
            Articulo encontrado = null;

            for (Articulo art : catalogo) {
                if (art.getCodigo().equalsIgnoreCase(codigoLeido)) {
                    encontrado = art;
                    break;
                }
            }

            if (encontrado != null) {
                detectado = true;
                g2d.setColor(Color.GREEN);
                g2d.setStroke(new BasicStroke(6));
                g2d.drawRect(ancho / 4, alto / 4, ancho / 2, alto / 2);

                if (!codigoLeido.equals(ultimoCodigoDetectado) || (ahora - ultimaLecturaTimestamp) > COOLDOWN_QR_MS) {
                    ultimoCodigoDetectado = codigoLeido;
                    ultimaLecturaTimestamp = ahora;
                    articuloPendiente = encontrado;
                    timestampQrLeido = ahora;
                    manoAbiertaDetectada = false;
                    pinchStartTime = 0;
                    ultimoEscaneoOAccionTimestamp = ahora; // Reinicia la pausa al leer un QR válido
                    Toolkit.getDefaultToolkit().beep(); // Sonido BIP de lectura correcta
                }
            } else {
                g2d.setColor(Color.RED);
                g2d.setStroke(new BasicStroke(6));
                g2d.drawRect(ancho / 4, alto / 4, ancho / 2, alto / 2);
                actualizadorMensaje.accept("Código QR no reconocido: " + codigoLeido);
                ultimoEscaneoOAccionTimestamp = ahora; // Reinicia la pausa al intentar leer un QR inválido
            }
        }

        // 3. PROCESAMIENTO DE GESTOS (HandModel)
        if (hands != null) {
            try {
                HandModel.Hand hand = hands.detect(frame);

                if (hand.present()) {
                    if (articuloPendiente != null) {
                        hand.drawSquare(g2d);
                        hand.drawBones(g2d);
                        hand.drawPoints(g2d);
                    }

                    double ratioPinza = hand.pinchRatio();

                    if (ratioPinza > 0.32) {
                        manoAbiertaDetectada = true;
                    }

                    boolean esPinzaCerrada = ratioPinza < PINCH_THRESHOLD;
                    boolean transcurrioTiempoSeguridad = (ahora - timestampQrLeido) > 600;

                    // A) CONFIRMACIÓN POR PINZA (Sensible y rápida)
                    if (esPinzaCerrada && articuloPendiente != null && transcurrioTiempoSeguridad && manoAbiertaDetectada) {
                        if (pinchStartTime == 0) {
                            pinchStartTime = ahora;
                        } else if ((ahora - pinchStartTime) >= PINCH_HOLD_DURATION_MS) {
                            if ((ahora - ultimaPinzaTimestamp) > 2000) {
                                ultimaPinzaTimestamp = ahora;
                                pinchStartTime = 0;
                                manoAbiertaDetectada = false;

                                Articulo aConfirmar = articuloPendiente;
                                articuloPendiente = null;

                                if (onConfirmarRecepcion != null) {
                                    onConfirmarRecepcion.accept(aConfirmar);
                                }

                                Toolkit.getDefaultToolkit().beep();

                                mensajeExito = "¡ÉXITO! Se ha añadido 1 unidad de [" + aConfirmar.getNombre() + 
                                               "] al inventario.\nStock actual total: " + aConfirmar.getStock();
                                tiempoExito = ahora;
                                ultimoEscaneoOAccionTimestamp = ahora;
                            }
                        }
                    } else {
                        pinchStartTime = 0;
                    }

                    // B) GESTO DE SWIPE (Derecha a izquierda) PARA SALIR
                    int xMuñeca = hand.px(HandModel.WRIST);
                    if (!esPinzaCerrada && articuloPendiente == null && mensajeExito.isEmpty() && ultimaXMuñeca != -1) {
                        int deltaX = xMuñeca - ultimaXMuñeca;
                        if (deltaX < -60 && (ahora - ultimoSwipeTimestamp) > 1000) {
                            ultimoSwipeTimestamp = ahora;
                            if (onCerrarVentana != null) {
                                onCerrarVentana.run(); 
                            }
                        }
                    }
                    ultimaXMuñeca = xMuñeca;
                } else {
                    ultimaXMuñeca = -1;
                    pinchStartTime = 0;
                }
            } catch (Exception ignored) {}
        }

        // 4. TEXTOS GUIADOS EN LA INTERFAZ
        if (mensajeExito.isEmpty()) {
            if (articuloPendiente != null) {
                g2d.setColor(Color.YELLOW);
                g2d.drawString("¡HAZ PINZA PARA CONFIRMAR!", ancho / 6, (alto / 4) - 15);
                
                String estadoPinza = (pinchStartTime > 0) ? " [⏳ Confirmando...]" : "";
                
                actualizadorMensaje.accept("Leído: " + articuloPendiente.getNombre() + 
                        " (" + articuloPendiente.getCategoria() + ")" + estadoPinza + "\n" +
                        "-> Haz una PINZA clara frente a la cámara para confirmar.");
            } else if (!detectado) {
                long tiempoPausa = ahora - ultimoEscaneoOAccionTimestamp;
                if (tiempoPausa > PAUSE_TIMEOUT_MS) {
                    actualizadorMensaje.accept("Mueve la mano de derecha a izquierda enseñando la palma de la mano para salir de esta pantalla y visualizar el inventario.");
                } else {
                    actualizadorMensaje.accept("1. Enseña el QR a la cámara.\n2. Haz PINZA para sumar al stock.\n3. Swipe izda. para salir.");
                }
            }
        }

        g2d.dispose();
        return detectado;
    }
}