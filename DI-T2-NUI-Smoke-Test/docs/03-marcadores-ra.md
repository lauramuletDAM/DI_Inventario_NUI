# App Marcadores y RA (`MarcadoresApp`)

Todo lo que **ancla** contenido aumentado a algo que ve la cámara. Cuatro modos que comparten el bucle de cámara
(`Camera`), el dibujo sobre el fotograma y la conversión a OpenCV. La ventana la aporta `LiveApp`.

**Fuentes:** `MarcadoresApp`, `QrMode`, `ArucoMode`, `FaceMode`, `CubeMode` · **Librerías:** ZXing 3.5.3, JavaCV/OpenCV 4.10,
JavaFX 3D.

## Ventana y bucle

```
 [QR v] [Iniciar] [Comprobar]      línea de estado:  "<pista>   [N de M fotogramas con detección]"
 área de texto (errores / informe)
 vista previa en directo

 Iniciar:  mode.prepareLive() -> Camera.stream(running, frame -> { mode.process(frame); mostrar(frame) }) -> mode.endLive()
 Detener:  running = false  (el bucle termina en el siguiente fotograma y libera la cámara)
```

A diferencia de las pruebas antiguas, el directo **no tiene duración fija**: corre hasta pulsar `Detener`.

## Pasos de `--check` (sin cámara ni persona)

| Modo | Qué valida | Ejemplo real |
|---|---|---|
| `QR` | Ida y vuelta de generar y leer, y que ZXing devuelva puntos de esquina | `ida y vuelta OK, 3 puntos de esquina` |
| `ArUco` | Genera el marcador 23 y lo detecta | `marcador 23 generado y detectado` |
| `Rostro` | Descarga (1.ª vez) y carga el clasificador Haar | `clasificador Haar cargado` |
| `Cubo 3D` | SCENE3D soportado y render no vacío | `SCENE3D soportado, cubo renderizado (19979 muestras visibles)` |

La cámara en sí se valida en la app **Entorno**.

## Modo QR (`QrMode`)

```
 GENERAR                                      LEER
 texto --MultiFormatWriter.encode--> BitMatrix    BufferedImage
                                        |            |  BufferedImageLuminanceSource  (a grises)
                     MatrixToImageWriter|            v
                                        v         HybridBinarizer  (blanco/negro con umbral local)
                                  BufferedImage      |
                                        '---------> BinaryBitmap --MultiFormatReader.decode--> Result

 Result.getResultPoints():   TL ------ TR     centros de los 3 patrones de búsqueda
                             |                y, si el QR lo tiene (versión 2 o mayor), el de alineación
                             BL                (el orden lo fija ZXing)
```

* En directo: si hay QR, une los puntos con una línea, marca los vértices y escribe `RA: <contenido>` (el QR es el **ancla**).
* Pistas (`DecodeHintType`): `POSSIBLE_FORMATS = QR_CODE` (solo QR) y `TRY_HARDER` (más tolerancia a ruido y perspectiva, a costa de CPU).
* ZXing añade una **zona de silencio** alrededor del QR; es necesaria para leerlo.
* `NotFoundException` (no hay código) es el caso normal de un fotograma sin QR.
* Para leer **códigos de barras 1D** (EAN, Code 128...) habría que ampliar `POSSIBLE_FORMATS`; con una webcam de 640x480 es probable
  que sean poco fiables (suposición, no medida).

## Modo ArUco (`ArucoMode`)

Marcadores fiduciales: el ancla estándar de RA y robótica, porque dan 4 esquinas precisas y un **id** robusto.

```
 Marcador DICT_4X4_50, id 23              Detección
 +--+--+--+--+--+--+                       imagen --> umbral adaptativo --> contornos de 4 lados
 |##|##|##|##|##|##|  borde negro            --> quita la perspectiva --> lee la rejilla de 4x4 bits
 |##|  |  |##|  |##|  4x4 bits = id          --> busca el id en el diccionario (50 marcadores)
 |##|##|  |  |##|##|  (ilustrativo)         corners: 1x4 puntos (x, y) por marcador ; ids: Nx1
 +--+--+--+--+--+--+                       esquinas:  0 --- 1
                                                       |     |
                                                       3 --- 2     (siguen el giro del marcador)
```

* El marcador 23 (200 px) se centra en un lienzo blanco de 400x400 con zona de silencio; es lo que usa el autotest y lo que se guarda
  como `aruco-23.png` al iniciar el modo en directo (solo si no existe).
* El autotest detecta pasando por `Camera.toMat`, el mismo camino que usa la cámara.
* `IntIndexer`/`FloatIndexer` son vistas Java para leer los `Mat` por `(fila, columna, canal)`: `fi.get(0, j, 0)` = x de la esquina `j`.
* `RefineParameters` solo interviene en `refineDetectedMarkers`, que aquí no se llama: es inocuo pero no influye en `detectMarkers`.
* **Memoria nativa:** el detector y el diccionario se crean fuera del `PointerScope` (deben sobrevivir a los fotogramas); `Mat`/`MatVector`
  de cada detección se liberan al cerrar el scope.

## Modo Rostro (`FaceMode`)

```
 Frame --toMat--> BGR --cvtColor--> gris --equalizeHist--> gris con contraste uniforme
                                                              |  detectMultiScale (ventana deslizante, varias escalas)
                                                              v
                                                     RectVector (x, y, ancho, alto)

 Sombrero:        /\      punta = (x + w/2, y - h/2)
                 /  \
           +----------+   base  = borde superior del rostro
           |  rostro  |   (alto del sombrero = la mitad del alto del rostro)
           +----------+
```

Viola-Jones: características Haar (diferencias de brillo entre rectángulos, calculadas con una imagen integral) y una cascada AdaBoost
que descarta rápido lo que no es un rostro. Rápido y sin GPU, pero solo rostros **frontales** y sensible a la luz. El modelo
`haarcascade_frontalface_default.xml` (~0,9 MB) se descarga a `models/`. `detectMultiScale` se usa con los parámetros por defecto de OpenCV.

## Modo Cubo 3D (`CubeMode`)

Comprueba que JavaFX puede renderizar 3D **fuera de pantalla** y lo compone sobre la cámara. Si hay un marcador ArUco a la vista, el
cubo se dibuja **anclado a su centro**; si no, queda en el centro de la imagen.

```
 Hilo de la cámara                         Hilo de JavaFX
 render(w, h)  --Platform.runLater------>  Box 120^3 + PhongMaterial, giros X=30 Y=40
 future.get(30 s) <---- complete -------   PointLight + AmbientLight, SubScene(depthBuffer)
                                           PerspectiveCamera(fixedEye) en z = -500
                                           fondo MAGENTA --> snapshot --> BufferedImage
                                           pixel magenta -> alfa 0  (chroma key)
 process(frame): drawImage(cubo, centro del marcador - centro de la imagen)
```

* **Chroma key:** `Scene.snapshot` devuelve una imagen opaca, así que el fondo se pinta de un color clave (magenta) y luego se hace
  transparente. Se desactiva el antialiasing para que el borde del cubo no se mezcle con el magenta y deje un halo rosado.
* JavaFX: X a la derecha, Y hacia abajo, Z hacia dentro de la pantalla. Los nodos 3D deben crearse y renderizarse en el hilo de JavaFX.
* El cubo se renderiza **una vez por tamaño de imagen** y se reutiliza.
* Anclar el cubo a un marcador es RA **de marcadores**; no hay SLAM, detección de planos ni oclusión como en ARCore/ARKit.

## Observaciones

* No mostrar un QR, un marcador o un rostro en directo **no** es un fallo: solo `--check` valida.
* La imagen de la cámara no está reflejada.
* `ArucoMode` y `FaceMode` guardan recursos nativos que liberan `close()` al cerrar la app.
