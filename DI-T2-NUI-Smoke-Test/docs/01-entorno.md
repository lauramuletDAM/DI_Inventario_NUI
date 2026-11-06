# App Entorno (`EntornoApp`)

**Qué valida:** que el equipo está listo para el resto: tiene Internet, una cámara que entrega fotogramas y soporte
para gráficos 3D de JavaFX. Es la primera app que conviene comprobar.

**Fuente:** `EntornoApp.java` · **Librerías:** `java.net.http` (JDK), JavaCV/OpenCV, JavaFX.

## Pasos de `--check`

```
 Red     HttpClient GET https://example.com --------------> "HTTP 200 (N caracteres)"
 Camara  OpenCVFrameGrabber(0) -> 20 fotogramas -> el último válido -> BufferedImage --> "640x480"
 3D      Platform.isSupported(ConditionalFeature.SCENE3D) -------------------------> "soportado"
```

| Paso | Qué hace | Cuándo falla |
|---|---|---|
| `Red` | GET a `https://example.com` con 10 s de plazo de conexión; devuelve código y tamaño del cuerpo | Sin red, proxy que bloquea, TLS |
| `Camara` | Abre la cámara 0, descarta 20 fotogramas de calentamiento y se queda con el último con imagen | Cámara ocupada, sin permiso, índice 0 no es la deseada |
| `3D` | Pregunta a JavaFX si `SCENE3D` está soportado | Drivers gráficos antiguos o entorno remoto sin aceleración |

Ejemplo real: `RESULT OK - Red: HTTP 200 (577 caracteres) | Camara: 640x480 | 3D: soportado`

## Ventana

Un botón **Comprobar** que ejecuta los tres pasos en segundo plano, un área de texto con un paso por línea y la **vista previa**
del último fotograma capturado.

## APIs

| Clase | Papel |
|---|---|
| `HttpClient` / `HttpRequest` / `HttpResponse` | Cliente HTTP del JDK. `send` es síncrono (bloquea el hilo de trabajo). |
| `OpenCVFrameGrabber(int)` | Captura por índice de dispositivo (0 = primera cámara). `AutoCloseable`. |
| `Frame` | Contenedor de JavaCV; `image` es `null` si no hay imagen. |
| `Java2DFrameConverter` | `Frame` -> `BufferedImage`. |
| `Platform.isSupported(ConditionalFeature.SCENE3D)` | Soporte real de 3D en el equipo. |
| `SwingFXUtils.toFXImage` | `BufferedImage` -> `Image` para la vista previa. |

## Observaciones

* El plazo de 10 s es solo de **conexión**; no hay plazo de respuesta, así que un servidor que acepta y no contesta bloquearía el paso
  (el launcher lo cortaría a los 60 s).
* La copia a `TYPE_INT_RGB` evita depender de la memoria que el convertidor comparte con el `Frame`.
* El render 3D completo se prueba en **Marcadores y RA** (`Cubo 3D`); aquí solo se pregunta si el equipo lo soporta.
* La imagen de la cámara no está reflejada (sin efecto espejo): relevante para `Swipe` y `Pose`.
