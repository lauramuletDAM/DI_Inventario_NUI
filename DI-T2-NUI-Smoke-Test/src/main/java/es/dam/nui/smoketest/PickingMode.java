package es.dam.nui.smoketest;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Point2D;
import javafx.geometry.Point3D;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.Scene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.robot.Robot;
import javafx.scene.shape.Sphere;
import javafx.stage.Stage;

/**
 * Seleccion 3D con el picking NATIVO de JavaFX, dirigido con la yema del indice. En vez de calcular
 * rayos a mano, el dedo mueve el cursor REAL del sistema con {@code javafx.scene.robot.Robot};
 * JavaFX genera eventos de raton y resuelve el impacto.
 *
 * <pre>{@code
 *   Camara -> HandModel.detect -> punta del indice (punto 8)
 *                                    |  coordenadas de imagen -> ventana (x * W/w, y * H/h)
 *                                    v
 *                    view.localToScreen(x, y)  -> posicion de pantalla
 *                                    |
 *                          robot.mouseMove(...)       (mueve el cursor del sistema)
 *                                    |
 *                                    v
 *            JavaFX: evento de raton -> picking -> Sphere.onMouseEntered(e)
 *                                                    e.getPickResult().getIntersectedPoint()  (Point3D local)
 *
 *   Ventana auxiliar (StackPane = capas apiladas; la ultima queda encima)
 *   +-----------------------------------+
 *   |  SubScene 3D (fondo transparente) |  <- arriba: recibe el raton, 3 esferas
 *   |    (ROJA)   (VERDE)   (AZUL)      |
 *   |  ImageView (fotograma de camara)  |  <- abajo
 *   +-----------------------------------+
 *   Esferas de radio 40, centros x = -120, 0, 120; camara a 500 de distancia, FOV 30
 *
 *   Hilos:  hilo de la camara --onFx(tarea)--> Platform.runLater + CompletableFuture --> hilo de JavaFX
 * }</pre>
 *
 * <p>Conceptos: {@code PickResult} (que nodo y en que punto 3D impacta el rayo, en coordenadas locales
 * del nodo), {@code localToScreen} (nodo -> pantalla), {@code Robot} (solo desde el hilo de JavaFX).
 *
 * <p>AVISO: mientras este modo esta activo el cursor del sistema lo controla la app; no hay que
 * tocar el raton. La ventana auxiliar se abre en {@link #prepareLive()} y se cierra en {@link #endLive()}.
 */
final class PickingMode implements LiveMode {

    private static final int W = 640;
    private static final int H = 480;
    private static final double RADIUS = 40;
    private static final double DISTANCE = 500;
    private static final double[] CENTER_X = {-120, 0, 120};
    private static final String[] NAMES = {"ROJA", "VERDE", "AZUL"};
    private static final Color[] BASE = {Color.CRIMSON, Color.LIMEGREEN, Color.DODGERBLUE};
    private static final int NONE = -1;

    private static final double FIELD_OF_VIEW = 30;
    private static final double NEAR_CLIP = 0.1;
    private static final double FAR_CLIP = 2000;
    private static final double LIGHT_X = -300;
    private static final double LIGHT_Y = -300;
    private static final double LIGHT_Z = -500;
    private static final double AMBIENT_LEVEL = 0.4;

    private static final int FX_TIMEOUT_SECONDS = 30;
    private static final long WINDOW_SETTLE_MS = 600;              // el sistema pinta la ventana antes de mover el cursor
    private static final long CORNER_SETTLE_MS = 300;
    private static final long SPHERE_SETTLE_MS = 350;
    private static final double CORNER_OFFSET = 6;
    private static final int CROSS_ARM = 10;
    private static final int TEXT_X = 8;
    private static final int TEXT_Y = 18;

    private final HandModel hands;
    private final AtomicIntegerArray entered = new AtomicIntegerArray(NAMES.length);
    private volatile int hovered = NONE;
    private volatile String lastPoint = "";
    private Stage stage;
    private ImageView view;
    private Sphere[] spheres;
    private Robot robot;

    PickingMode(HandModel hands) {
        this.hands = hands;
    }

    @Override
    public String selfTest() throws Exception {
        resetCounters();
        buildWindow();
        try {
            checkPicking();
        } finally {
            endLive();
        }
        return "el picking nativo detecta las " + NAMES.length + " esferas y el vacio";
    }

    @Override
    public void prepareLive() throws Exception {
        resetCounters();
        buildWindow();
    }

    @Override
    public boolean process(BufferedImage frame) throws Exception {
        HandModel.Hand hand = hands.detect(frame);
        int w = frame.getWidth();
        int h = frame.getHeight();
        Graphics2D g = frame.createGraphics();
        hand.drawSquare(g);
        int fx = 0;
        int fy = 0;
        if (hand.present()) {
            fx = hand.px(HandModel.INDEX_TIP);                     // yema del indice en pixeles del fotograma
            fy = hand.py(HandModel.INDEX_TIP);
            g.setColor(java.awt.Color.YELLOW);
            g.drawLine(fx - CROSS_ARM, fy, fx + CROSS_ARM, fy);
            g.drawLine(fx, fy - CROSS_ARM, fx, fy + CROSS_ARM);
        }
        int over = hovered;
        g.setColor(java.awt.Color.YELLOW);
        g.drawString("sobre: " + (over == NONE ? "-" : NAMES[over]) + "   entradas: " + NAMES[0] + "=" + entered.get(0)
                + " " + NAMES[1] + "=" + entered.get(1) + " " + NAMES[2] + "=" + entered.get(2)
                + (lastPoint.isEmpty() ? "" : "   impacto 3D " + lastPoint), hand.cx() + TEXT_X, hand.cy() + TEXT_Y);
        g.dispose();

        // fotograma -> ventana de W x H (el ImageView estira la imagen exactamente a ese tamano)
        double tx = (double) fx * W / w;
        double ty = (double) fy * H / h;
        boolean useHand = hand.present();
        Image img = SwingFXUtils.toFXImage(frame, null);
        ImageView target = view;
        Robot pointer = robot;
        Platform.runLater(() -> {                                  // imagen y cursor solo desde el hilo de JavaFX
            target.setImage(img);
            if (useHand) {
                Point2D screen = target.localToScreen(tx, ty);
                if (screen != null) {
                    pointer.mouseMove(screen);                     // el cursor real sigue al dedo; JavaFX hace el picking
                }
            }
        });
        return hand.present();
    }

    @Override
    public String hint() {
        return "Mano abierta en el cuadrado central: el cursor sigue la yema de tu indice. Apunta a cada esfera de la ventana 3D. No uses el raton";
    }

    @Override
    public void endLive() throws Exception {
        if (stage != null) {
            Stage closing = stage;
            stage = null;
            onFx(() -> {
                closing.close();                                   // se cierra siempre, aunque falle el modo
                return null;
            });
        }
    }

    @Override
    public void close() {
        try {
            endLive();
        } catch (Exception e) {
            // la ventana ya estaba cerrada
        }
    }

    // ------------------------------------------------------------------ ventana 3D

    private void resetCounters() {
        for (int i = 0; i < NAMES.length; i++) {
            entered.set(i, 0);
        }
        hovered = NONE;
        lastPoint = "";
    }

    /** Ejecuta una tarea en el hilo de JavaFX y espera su resultado (o la excepcion). */
    private static <T> T onFx(Callable<T> task) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future.get(FX_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** Crea la ventana con la camara (ImageView) y la escena 3D (SubScene) superpuestas, y el Robot. */
    private void buildWindow() throws Exception {
        onFx(() -> {
            spheres = new Sphere[NAMES.length];
            PhongMaterial[] materials = new PhongMaterial[NAMES.length];
            Group root = new Group();
            for (int i = 0; i < NAMES.length; i++) {
                final int idx = i;
                materials[i] = new PhongMaterial(BASE[i]);
                spheres[i] = new Sphere(RADIUS);
                spheres[i].setMaterial(materials[i]);
                spheres[i].setTranslateX(CENTER_X[i]);
                spheres[i].setOnMouseEntered(e -> {                // el puntero entra: se marca, se cuenta, se pone blanca
                    hovered = idx;
                    entered.incrementAndGet(idx);
                    materials[idx].setDiffuseColor(Color.WHITE);
                    Point3D p = e.getPickResult().getIntersectedPoint();   // impacto en coordenadas locales de la esfera
                    lastPoint = p == null ? "" : String.format("(%.0f, %.0f, %.0f)", p.getX(), p.getY(), p.getZ());
                });
                spheres[i].setOnMouseExited(e -> {                 // sale: se restaura el color
                    if (hovered == idx) {
                        hovered = NONE;
                    }
                    materials[idx].setDiffuseColor(BASE[idx]);
                });
                root.getChildren().add(spheres[i]);
            }
            PointLight light = new PointLight(Color.WHITE);
            light.setTranslateX(LIGHT_X);
            light.setTranslateY(LIGHT_Y);
            light.setTranslateZ(LIGHT_Z);
            root.getChildren().addAll(light, new AmbientLight(Color.color(AMBIENT_LEVEL, AMBIENT_LEVEL, AMBIENT_LEVEL)));

            SubScene sub = new SubScene(root, W, H, true, SceneAntialiasing.BALANCED);   // aqui si hay antialiasing: no hay chroma key
            PerspectiveCamera cam = new PerspectiveCamera(true);
            cam.setFieldOfView(FIELD_OF_VIEW);
            cam.setTranslateZ(-DISTANCE);
            cam.setNearClip(NEAR_CLIP);
            cam.setFarClip(FAR_CLIP);
            sub.setCamera(cam);
            sub.setFill(Color.TRANSPARENT);                        // para que se vea la camara que hay debajo

            view = new ImageView();
            view.setFitWidth(W);
            view.setFitHeight(H);
            StackPane pane = new StackPane(view, sub);             // el ultimo hijo queda encima: la SubScene tapa al ImageView

            stage = new Stage();
            stage.setTitle("Picking 3D nativo");
            stage.setScene(new Scene(pane, W, H));
            stage.setAlwaysOnTop(true);                            // otra ventana encima impediria el picking
            stage.show();
            robot = new Robot();                                   // requisito: crearlo en el hilo de JavaFX
            return null;
        });
        Thread.sleep(WINDOW_SETTLE_MS);
    }

    private void moveTo(Point2D p) throws Exception {
        onFx(() -> {
            robot.mouseMove(p);
            return null;
        });
    }

    /** Mueve el cursor con Robot a una esquina y al centro de cada esfera, y comprueba que JavaFX informa del impacto. */
    private void checkPicking() throws Exception {
        Point2D corner = onFx(() -> view.localToScreen(CORNER_OFFSET, CORNER_OFFSET));    // zona sin esferas
        moveTo(corner);
        Thread.sleep(CORNER_SETTLE_MS);
        if (hovered != NONE) {
            throw new IllegalStateException("el cursor en la esquina no deberia apuntar a ninguna esfera");
        }
        for (int i = 0; i < NAMES.length; i++) {
            final int idx = i;
            Point2D p = onFx(() -> spheres[idx].localToScreen(new Point3D(0, 0, 0)));      // centro de la esfera en pantalla
            moveTo(p);
            Thread.sleep(SPHERE_SETTLE_MS);
            if (hovered != idx) {
                throw new IllegalStateException("el picking nativo no detecto la esfera " + NAMES[idx]
                        + " (hovered=" + hovered + "). Revisa que la ventana sea visible y no la tape otra");
            }
        }
        moveTo(corner);
        Thread.sleep(CORNER_SETTLE_MS);
        if (hovered != NONE) {
            throw new IllegalStateException("al salir de la esfera deberia quedar sin impacto");
        }
    }
}
