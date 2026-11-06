package es.dam.nui.smoketest;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.Scene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.transform.Rotate;

/**
 * Objeto 3D de JavaFX compuesto sobre la camara. Comprueba que el equipo soporta SCENE3D y que se
 * puede renderizar fuera de pantalla. Si hay un marcador ArUco a la vista, el cubo se dibuja ANCLADO
 * a su centro (RA con contenido 3D); sin marcador queda en el centro de la imagen.
 *
 * <pre>{@code
 *   Hilo de la camara                         Hilo de JavaFX
 *   -----------------                         -----------------------------------------------
 *   render(w, h)  --Platform.runLater------>  Box 120^3 + PhongMaterial, giros X=30 Y=40
 *   future.get(30 s) <---- complete -------   PointLight + AmbientLight, SubScene(depthBuffer)
 *                                             PerspectiveCamera(fixedEye) en z = -500
 *                                             fondo MAGENTA --> snapshot --> BufferedImage
 *                                             pixel magenta -> alfa 0 (chroma key)
 *   process(frame): drawImage(cubo, centro del marcador - centro de la imagen)
 * }</pre>
 *
 * <p>Chroma key: {@code Scene.snapshot} devuelve una imagen OPACA, asi que el fondo se pinta de un
 * color clave (magenta) y luego se hace transparente. Se desactiva el antialiasing para que el
 * borde del cubo no se mezcle con el magenta y deje un halo rosado.
 *
 * <p>JavaFX: X a la derecha, Y hacia abajo, Z hacia dentro de la pantalla. Los nodos 3D deben
 * crearse y renderizarse en el hilo de JavaFX, de ahi {@code Platform.runLater} +
 * {@code CompletableFuture}. El cubo se renderiza una vez por tamano de imagen y se reutiliza.
 */
final class CubeMode implements LiveMode {

    private static final int DEFAULT_WIDTH = 640;
    private static final int DEFAULT_HEIGHT = 480;
    private static final double BOX_SIZE = 120;
    private static final double ROTATE_X_DEGREES = 30;
    private static final double ROTATE_Y_DEGREES = 40;
    private static final double LIGHT_X = -300;
    private static final double LIGHT_Y = -300;
    private static final double LIGHT_Z = -500;
    private static final double AMBIENT_LEVEL = 0.4;
    private static final double CAMERA_Z = -500;
    private static final double NEAR_CLIP = 0.1;
    private static final double FAR_CLIP = 2000;
    private static final int KEY_RGB = 0xFF00FF;                   // Color.MAGENTA
    private static final int RGB_MASK = 0xFFFFFF;
    private static final int TRANSPARENT = 0;
    private static final int ALPHA_SHIFT = 24;
    private static final int SAMPLE_STEP = 2;
    private static final int RENDER_TIMEOUT_SECONDS = 30;

    private final ArucoMode aruco;
    private BufferedImage cube;

    CubeMode(ArucoMode aruco) {
        this.aruco = aruco;
    }

    @Override
    public String selfTest() throws Exception {
        int visible = visibleSamples(cube(DEFAULT_WIDTH, DEFAULT_HEIGHT));
        if (visible == 0) {
            throw new IllegalStateException("el render 3D salio vacio");
        }
        return "SCENE3D soportado, cubo renderizado (" + visible + " muestras visibles)";
    }

    @Override
    public boolean process(BufferedImage frame) throws Exception {
        int w = frame.getWidth();
        int h = frame.getHeight();
        BufferedImage image = cube(w, h);
        List<ArucoMode.Marker> markers = aruco.detect(frame);
        int dx = 0;                                                // sin marcador: el cubo ya esta en el centro
        int dy = 0;
        if (!markers.isEmpty()) {
            dx = markers.get(0).centerX() - w / 2;                 // desplaza el cubo al centro del marcador
            dy = markers.get(0).centerY() - h / 2;
        }
        Graphics2D g = frame.createGraphics();
        g.drawImage(image, dx, dy, null);
        g.dispose();
        return !markers.isEmpty();
    }

    @Override
    public String hint() {
        return "Ensena el marcador ArUco para anclar el cubo; sin marcador queda centrado";
    }

    @Override
    public void prepareLive() throws Exception {
        aruco.prepareLive();                                       // el mismo marcador de la prueba ArUco
    }

    private synchronized BufferedImage cube(int w, int h) throws Exception {
        if (cube == null || cube.getWidth() != w || cube.getHeight() != h) {
            cube = render(w, h);
        }
        return cube;
    }

    /** Renderiza el cubo en una imagen w x h con fondo transparente. */
    private static BufferedImage render(int w, int h) throws Exception {
        if (!Platform.isSupported(ConditionalFeature.SCENE3D)) {
            throw new IllegalStateException("SCENE3D no soportado en este equipo");
        }
        CompletableFuture<BufferedImage> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                Box box = new Box(BOX_SIZE, BOX_SIZE, BOX_SIZE);
                box.setMaterial(new PhongMaterial(Color.CORNFLOWERBLUE));
                box.getTransforms().addAll(new Rotate(ROTATE_X_DEGREES, Rotate.X_AXIS), new Rotate(ROTATE_Y_DEGREES, Rotate.Y_AXIS));
                PointLight light = new PointLight(Color.WHITE);    // arriba a la izquierda, del lado de la camara
                light.setTranslateX(LIGHT_X);
                light.setTranslateY(LIGHT_Y);
                light.setTranslateZ(LIGHT_Z);
                Group root = new Group(box, light, new AmbientLight(Color.color(AMBIENT_LEVEL, AMBIENT_LEVEL, AMBIENT_LEVEL)));
                SubScene sub = new SubScene(root, w, h, true, SceneAntialiasing.DISABLED);   // depthBuffer: ordena caras
                PerspectiveCamera cam = new PerspectiveCamera(true);
                cam.setTranslateZ(CAMERA_Z);
                cam.setNearClip(NEAR_CLIP);
                cam.setFarClip(FAR_CLIP);
                sub.setCamera(cam);
                sub.setFill(Color.MAGENTA);
                Scene scene = new Scene(new Group(sub), w, h, true, SceneAntialiasing.DISABLED);
                scene.setFill(Color.MAGENTA);
                WritableImage snapshot = scene.snapshot(null);     // renderiza sin mostrar nada en pantalla
                BufferedImage bi = SwingFXUtils.fromFXImage(snapshot, null);
                for (int y = 0; y < bi.getHeight(); y++) {
                    for (int x = 0; x < bi.getWidth(); x++) {
                        if ((bi.getRGB(x, y) & RGB_MASK) == KEY_RGB) {
                            bi.setRGB(x, y, TRANSPARENT);          // pixel clave -> ARGB 0
                        }
                    }
                }
                future.complete(bi);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future.get(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** Cuenta (cada 2 px) los pixeles no transparentes: 0 = el render salio vacio. */
    private static int visibleSamples(BufferedImage image) {
        int visible = 0;
        for (int y = 0; y < image.getHeight(); y += SAMPLE_STEP) {
            for (int x = 0; x < image.getWidth(); x += SAMPLE_STEP) {
                if ((image.getRGB(x, y) >>> ALPHA_SHIFT) != 0) {
                    visible++;
                }
            }
        }
        return visible;
    }
}
