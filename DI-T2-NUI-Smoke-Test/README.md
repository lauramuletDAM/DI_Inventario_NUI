# NUI smoke test (JavaFX)

Comprueba, en un equipo de aula, que están disponibles las tecnologías de las prácticas de **interfaces naturales de usuario (NUI)** con
JavaFX. Son 5 apps independientes y un launcher que las abre una a una o las valida todas de una vez.

| App | Qué comprueba | Necesita |
|---|---|---|
| Entorno | Red, cámara y soporte 3D | Internet, cámara |
| Voz | Dictado y comandos de voz (Vosk), síntesis de voz (SAPI) e IA (Groq) | Micrófono, altavoz (Windows), Internet la 1.ª vez |
| Marcadores y RA | QR, ArUco, rostro y cubo 3D sobre la cámara | Cámara, Internet la 1.ª vez, 3D |
| Manos y cuerpo | Manos, pellizco con fijación, deslizamiento, pose y picking 3D | Cámara, Internet la 1.ª vez. **Mueve el ratón** |
| Clasificación | Clasificar una imagen con DJL (PyTorch + ResNet18) | Internet; la 1.ª vez descarga varios minutos |

## Requisitos

* JDK 25 y Maven. Las dependencias nativas del `pom.xml` son para **Windows x64** (`native.platform`).
* La síntesis de voz usa Windows SAPI a través de PowerShell (solo Windows).

## Ejecutar

* **Eclipse:** ejecutar `es.dam.nui.smoketest.Main` como aplicación Java (abre el launcher).
* **Maven:** `mvn clean javafx:run`.
* Una app suelta: `Main <clave>` (`entorno`, `voz`, `marcadores`, `gestos`, `clasificacion`). Con `--check` ejecuta solo su autotest,
  sin ventana, y escribe una línea `RESULT OK - ...` o `RESULT FALLO - ...`.

## Validar el equipo

1. Abrir el launcher y pulsar **Comprobar todo**. Se abren ventanas y **el ratón se mueve solo** (Picking 3D): no hay que tocarlo.
2. Cada app termina con `OK - ...` o `FALLO - ...` y el launcher muestra `N de 5 apps OK`.
3. Si algo falla, el detalle está en `logs/<app>-check.log`.

La primera ejecución descarga modelos a `models/` (Vosk, Haar, ONNX) y PyTorch a `~/.djl.ai`; puede tardar varios minutos.

## Documentación

Arquitectura y un documento por app en [`docs/`](docs/README.md).
