package es.dam.nui.smoketest;

/**
 * App "Marcadores y RA": todo lo que ANCLA contenido aumentado a algo que ve la camara. Une las
 * antiguas pruebas 3 (QR), 6 (RA con QR), 7 (ArUco), 8 (rostro) y 9 (cubo 3D). La ventana y el bucle
 * de camara los aporta {@link LiveApp}; aqui solo se registran los modos.
 *
 * <pre>{@code
 *   QR       ida y vuelta + puntos de esquina       ZXing
 *   ArUco    genera el marcador 23 y lo detecta     OpenCV objdetect
 *   Rostro   descarga (1a vez) y carga Haar         OpenCV
 *   Cubo 3D  SCENE3D soportado y render no vacio    JavaFX 3D   (se ancla al ArUco)
 * }</pre>
 *
 * <p>El cubo reutiliza el detector de {@link ArucoMode} para anclarse a su marcador.
 */
public class MarcadoresApp extends LiveApp {

    public MarcadoresApp() {
        ArucoMode aruco = new ArucoMode();
        modes.put("QR", new QrMode());
        modes.put("ArUco", aruco);
        modes.put("Rostro", new FaceMode());
        modes.put("Cubo 3D", new CubeMode(aruco));
    }

    @Override
    protected String title() {
        return "Marcadores y RA";
    }
}
