# Arquitectura del NUI smoke test

Proyecto Maven (JDK 25, JavaFX 25.0.2, classpath sin `module-info.java`) que comprueba, en un equipo de aula, que
están disponibles las tecnologías de las prácticas de **interfaces naturales de usuario (NUI)** con JavaFX: voz,
códigos y marcadores, manos y cuerpo, realidad aumentada y modelos de aprendizaje automático.

Son **5 apps JavaFX independientes** más un **launcher** que las abre una a una o las comprueba todas de una vez.

## 1. Vista general

```
                           Main  (punto de entrada; NO hereda de Application)
                            |
        sin argumentos ---->+----> LauncherApp : lista de apps, "Abrir" y "Comprobar todo"
        <app>          ---->+----> <App>       : ventana interactiva de esa app
        <app> --check  ---->+----> <App>       : autotest sin ventana; escribe "RESULT ..." y termina

   Apps (AppId)                    Clase            Modos / pasos de --check
   ---------------------------     --------------   ---------------------------------------------
   Entorno                         EntornoApp       Red | Camara | 3D
   Voz                             VozApp           Comandos | Modelo | Micro | Voces | Sintesis | IA
   Marcadores y RA                 MarcadoresApp    QR | ArUco | Rostro | Cubo 3D
   Manos y cuerpo                  GestosApp        Manos | Dwell | Swipe | Pose | Picking 3D
   Clasificacion (DJL)             ClasificacionApp ResNet18
```

## 2. Un proceso por app

JavaFX solo admite un `Application.launch` por JVM. Por eso el launcher **no** abre las apps dentro de su proceso:
cada una se lanza en una JVM propia con `ProcessBuilder`, usando el mismo classpath.

```
 LauncherApp --AppRunner.open(clave)--------------->  java ... Main <clave>            ventana interactiva
 LauncherApp --AppRunner.check(clave, plazo)------->  java ... Main <clave> --check    sin ventana
                      ^                                        | stdout + stderr
                      |                                        v
                      +------ Report <-------------  logs/<clave>-check.log   (se busca la línea "RESULT ...")
```

Ventajas: aísla las librerías nativas (OpenCV, ONNX Runtime, libtorch), libera cámara y micrófono al cerrar la app,
y un fallo o un cuelgue no tumba a las demás (el launcher mata el proceso al agotarse el plazo).

**Condición:** todo debe estar en el *classpath*. En Eclipse ya lo está; con Maven lo garantiza
`<runtimePathOption>CLASSPATH</runtimePathOption>` en `javafx-maven-plugin` (sin esa opción el plugin pone los jars de
JavaFX en el *module path* y la app hija no los encontraría). `--enable-native-access=ALL-UNNAMED` lo añade el plugin al
proceso padre y `AppRunner` a cada hijo.

## 3. El protocolo `--check` (`Report`)

La app escribe **una línea** con este formato; el launcher la busca entre el ruido de las librerías:

```
 RESULT OK - Red: HTTP 200 (577 caracteres) | Camara: 640x480 | 3D: soportado
 RESULT FALLO - Red: FALLO (java.net.ConnectException) | Camara: 640x480 | 3D: soportado
```

* Cada paso aporta `nombre: resumen`; los pasos se unen con ` | `.
* **Todos los pasos se ejecutan** aunque uno falle (el informe indica cuál); el resultado global es OK solo si todos lo son.
* Código de salida del proceso: `0` si OK, `1` si FALLO. `Report` no depende de JavaFX.
* El log completo (con avisos de las librerías) queda en `logs/<app>-check.log` (excluido en `.gitignore`).

### "Comprobar todo"

```
 LauncherApp (hilo "check-all", en serie: cámara y micrófono son exclusivos)
   para cada app de AppId:
        estado = "comprobando..."
        Report = AppRunner.check(clave, plazo)      -> proceso hijo --check, con timeout
        estado = "OK - ..." | "FALLO - ..."
   al final: "N de 5 apps OK"
 Aviso previo: se abren ventanas y el ratón se mueve solo (Picking 3D). No hay que tocarlo.
```

| App | Plazo de `--check` | Motivo |
|---|---|---|
| Entorno | 60 s | Rápida |
| Voz | 180 s | Primera vez: descarga del modelo Vosk (~40 MB) |
| Marcadores y RA | 120 s | Descarga del XML de Haar |
| Manos y cuerpo | 180 s | Descarga de dos modelos ONNX (~4 y ~5,5 MB) |
| Clasificación | 900 s | Primera vez: descarga de PyTorch nativo y pesos (varios minutos) |

## 4. Jerarquía de clases

```
 Application (JavaFX)
    |
    +-- LauncherApp
    |
    +-- NuiApp (abstract)  steps() + buildUi();  start() decide: --check o ventana
          |
          +-- EntornoApp
          +-- VozApp
          +-- ClasificacionApp
          +-- LiveApp (abstract)  ventana con selector de modo + bucle de cámara
                |
                +-- MarcadoresApp   modos: QrMode, ArucoMode, FaceMode, CubeMode
                +-- GestosApp       modos: HandMode, DwellMode, SwipeMode, PoseMode, PickingMode
```

| Clase | Papel |
|---|---|
| `Main` | Entrada única. Evita el error "JavaFX runtime components are missing" al no heredar de `Application`. |
| `AppId` | Catálogo de apps: clave, título, requisitos, clase y plazo de `--check`. |
| `NuiApp` | Base: `start` decide entre `--check` (ejecuta `steps()` en un hilo, imprime el informe y sale) y `buildUi`. |
| `LiveApp` | Base de las apps con cámara: ventana con `ChoiceBox` de modos, Iniciar/Detener, Comprobar y contadores. |
| `LiveMode` | Un modo: `selfTest()`, `process(frame)`, `hint()`, `prepareLive()`, `endLive()` y `close()`. |
| `Camera` | Bucle de captura (`OpenCVFrameGrabber`), `copy` y `toMat` compartidos. |
| `Downloads` | Descarga con caché en `./models`. |
| `AppRunner` / `Report` | Lanzamiento por procesos y protocolo del informe (sin JavaFX). |
| `OnnxModel` / `HandModel` | Modelo ONNX genérico y el de manos (compartido por 4 modos). |
| `CommandEngine`, `DwellEngine`, `SwipeEngine` | Máquinas de estado **puras**, con autotest sin cámara ni micrófono. |

> **Nota.** Si en tu copia del proyecto sigue existiendo `ArMode.java`, es un resto del renombrado a `LiveMode`: ninguna
> clase lo usa y se puede borrar.

## 5. Modelo de hilos

```
  Hilo de JavaFX (UI)                         Hilo de trabajo
  -------------------                         ----------------------------------------
  Button.onAction -----------------------> CompletableFuture.supplyAsync(...)   (check, dictado, IA, ...)
                                              |
  Platform.runLater(() -> label/imagen) <-----+   (las pruebas nunca tocan nodos directamente)

  Directo (LiveApp):   hilo de trabajo --> Camera.stream --> mode.process(frame) --> toFXImage --> runLater(setImage)
  Si el hilo de trabajo necesita algo del hilo de JavaFX (render 3D, Robot):  Platform.runLater + CompletableFuture
```

Reglas: nada de bloquear el hilo de JavaFX; nada de tocar nodos desde otro hilo; los valores de la ventana se **leen en el
hilo de JavaFX** y se pasan ya copiados a la tarea.

## 6. Memoria nativa (JavaCV / OpenCV)

`Mat` y `MatVector` viven fuera del heap de Java. A 30 fps se acumularían si se esperase al recolector, así que se agrupan
en un `PointerScope` que los libera al cerrar el bloque. **Regla:** los objetos de larga vida (detector ArUco, clasificador
Haar, modelos ONNX, `Model` de Vosk) se crean **fuera** de cualquier scope; si se crearan dentro, el scope los liberaría al
cerrar el primer fotograma.

## 7. Dependencias (`pom.xml`)

| Dependencia | Versión | Para qué |
|---|---|---|
| `javafx-controls`, `javafx-swing` | 25.0.2 | UI, 3D, `Robot`; `SwingFXUtils` (`BufferedImage` <-> `Image`) |
| `javacv` | 1.5.11 | Envoltorio Java de OpenCV: captura y conversores |
| `opencv`, `openblas` (+ clasificador `windows-x86_64`) | 4.10.0-1.5.11 / 0.3.28-1.5.11 | Binarios nativos |
| `zxing core`, `javase` | 3.5.3 | QR: generar y leer |
| `vosk` | 0.3.45 | Reconocimiento de voz offline (Kaldi) |
| `ai.djl:api`, `pytorch-engine`, `pytorch-model-zoo` | 0.34.0 | Clasificación de imágenes |
| `slf4j-simple` | 2.0.16 | Logs de DJL |
| `onnxruntime` | 1.22.0 | Modelos de manos y pose |
| `jackson-databind` | 2.17.2 | JSON de la API de Groq |

`native.platform=windows-x86_64` fija los binarios de JavaCV a Windows. La síntesis de voz (SAPI vía PowerShell) también es
solo Windows.

## 8. Ficheros que genera el proyecto

| Ruta | Qué es |
|---|---|
| `models/` | Modelos descargados (Vosk, Haar, ONNX). Excluido en `.gitignore`. |
| `logs/<app>-check.log` | Salida completa de cada `--check`. Excluido en `.gitignore`. |
| `aruco-23.png` | Marcador ArUco para imprimir o ver en el móvil (se crea al iniciar ArUco o Cubo 3D si no existe). |
| `~/.djl.ai` | Caché de PyTorch nativo y pesos de DJL (fuera del proyecto). |

## 9. Avisos normales en los logs

| Aviso | Significado |
|---|---|
| `Unsupported JavaFX configuration: classes were loaded from 'unnamed module'` | Esperado: JavaFX se carga desde el classpath, no desde el module path. |
| `A terminally deprecated method in sun.misc.Unsafe has been called ... UnsafeRaw` | Procede de JavaCPP (indexadores de ArUco) bajo JDK 25. Es un aviso, no un error. |
| `[check] INFO ai.djl.pytorch.engine.PtEngine ...` | Información de DJL/PyTorch al arrancar. |

## 10. Añadir una app o un modo

* **Modo en una app con cámara:** crear una clase que implemente `LiveMode` y registrarla en el constructor de
  `MarcadoresApp` o `GestosApp`; aparece sola en el selector y en `--check`.
* **App nueva:** una clase que extienda `NuiApp` (o `LiveApp`) con una línea en `AppId`.

## 11. Limitaciones conocidas

* `Downloads.ensureFile` y la descarga del modelo de Vosk no verifican integridad: una descarga cortada puede dejar un
  fichero parcial que la siguiente ejecución daría por bueno (borrar `models/` lo resuelve).
* `LauncherApp` pasa el classpath por línea de comandos: en Windows el límite ronda los 32.000 caracteres; con muchas
  dependencias podría llegar a rozarse (la salida sería un `@argfile`).
* Todas las demos "en directo" son informativas: no mostrar un QR o una mano **no** es un fallo. Solo `--check` valida el equipo.
* `Voz` ya no es portable: la síntesis usa Windows SAPI.
