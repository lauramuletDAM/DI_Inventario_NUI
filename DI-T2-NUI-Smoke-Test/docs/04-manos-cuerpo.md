# App Manos y cuerpo (`GestosApp`)

Gestos con la cámara. Un único **modelo de manos** (una inferencia por fotograma) alimenta cuatro modos que solo se diferencian en
cómo **interpretan** los 21 puntos. La pose corporal es otro modelo, pero comparte la cámara y la ventana (`LiveApp`).

**Fuentes:** `GestosApp`, `HandModel`, `OnnxModel`, `HandMode`, `DwellMode`, `DwellEngine`, `SwipeMode`, `SwipeEngine`, `PoseMode`,
`PickingMode` · **Librería:** ONNX Runtime 1.22.0 (+ JavaFX 3D para el picking).

```
                       +--> HandMode      esqueleto + pellizco con un umbral
 Camera --> HandModel  +--> DwellMode     pellizco con fijación de 800 ms          (DwellEngine)
 (1 inferencia)        +--> SwipeMode     deslizamiento de la muñeca               (SwipeEngine)
                       '--> PickingMode   yema del índice -> cursor -> picking 3D  (ventana auxiliar)

 Camera --> PoseMode   (otro modelo: pose corporal, ángulos del codo, brazo levantado)
```

## Pasos de `--check`

| Modo | Qué valida | Ejemplo real |
|---|---|---|
| `Manos` | Descarga (1.ª vez, ~4 MB), carga el modelo y hace una inferencia con imagen negra | `modelo OK (entrada [1, 224, 224, 3])` |
| `Dwell` | Autotest de su máquina de estados | `3 pellizcos, 2 confirmados con 800 ms de fijacion` |
| `Swipe` | Autotest de su máquina de estados | `eventos [DERECHA, IZQUIERDA]` |
| `Pose` | Autotest de los ángulos + carga del modelo de pose (~5,5 MB) | `angulos 90/180 OK; modelo OK (entrada [1, 256, 256, 3])` |
| `Picking 3D` | Abre su ventana y mueve el cursor con `Robot` por las 3 esferas y el vacío | `el picking nativo detecta las 3 esferas y el vacio` |

> **`Picking 3D` mueve el ratón.** Durante `--check` (y mientras el modo está activo) no hay que tocarlo.

## Modelo de manos (`HandModel` + `OnnxModel`)

Contrato del modelo MediaPipe Handpose (OpenCV Zoo), **verificado inspeccionando el `.onnx`**:

```
 entrada   input_1    [1, 224, 224, 3]   float32   NHWC, valores 0..1 (RGB / 255)
 salidas   Identity   [1, 63]            21 puntos x (x, y, z)   <- puntos (el código divide x e y entre 224: están en píxeles)
           Identity_1 [1, 1]             confianza de mano        <- se usa
           Identity_2 [1, 1]             probable lateralidad (por su forma; no se usa)
           Identity_3 [1, 63]            probables puntos 3D "de mundo" (por su forma; no se usa)
```

`OnnxModel` identifica las salidas por su **número total de elementos** (63 = puntos, 1 = confianza) y detecta NHWC o NCHW leyendo la
forma de entrada. No hay detector de palma previo: la mano debe estar **dentro del cuadrado central** y razonablemente grande.

```
 Fotograma w x h                    recorte cuadrado central           OnnxModel
 +---------------------+            +--------+                          salidas: 63 valores + confianza
 |     +---------+     |  side=     | 224x224| ----------------->               |
 |     | cuadrado|     |  min(w,h)  +--------+                                  v
 |     +---------+     |                                         Hand: puntos / 224  (0..1 dentro del cuadrado)
 +---------------------+  cx=(w-side)/2, cy=(h-side)/2
```

### Los 21 puntos (convención MediaPipe) y el pellizco

```
  Pulgar   Índice   Medio   Anular   Meñique
    4         8       12      16       20     <- puntas
    |         |        |       |        |
    3         7       11      15       19
    |         |        |       |        |
    2         6       10      14       18
    |         |        |       |        |
    1         5--------9-------13-------17    <- nudillos
     \        |                          |
      +-------+-----------0--------------+     0 = muñeca (conecta con 1, 5 y 17)

  ratio = dist(4, 8) / dist(0, 9)      punta pulgar - punta índice, entre muñeca y nudillo del dedo medio (tamaño de la mano)
  mano abierta ~ 0.8      pellizco: ratio menor que 0.25 en HandMode
```

Dividir entre el tamaño de la mano hace la medida **independiente de la distancia a la cámara**. Solo se usan `x` e `y`.

## Modo Manos (`HandMode`)

Esqueleto (huesos cian y puntos rojos) y ratio de pellizco con un único umbral (0.25). Sirve para comprobar que el modelo funciona y
que la mano se ve. Si no se detecta mano, solo se dibuja el cuadrado.

## Modo Dwell (`DwellMode` + `DwellEngine`): anti "toque de Midas"

El "clic" solo se dispara si el pellizco se **mantiene** 800 ms. Cuenta pellizcos **instantáneos** (lo que haría un sistema sin fijación)
y **confirmados**.

```
                 pellizco (ema menor que 0.25)            progreso llega a 1.0 (800 ms)
    +------+ ---------------------------------> +----------+ -----------------> +-----------+
    | IDLE |                                    | CHARGING |                    | CONFIRMED |
    +------+ <--------------------------------- +----------+                    +-----------+
       ^            suelta antes de 800 ms                                              |
       +-------------------------------------------------------------------------------+
                    suelta durante 300 ms seguidos (rearme)

  Histéresis sobre el ratio suavizado (ema = 0.5·ema + 0.5·ratio):
     ratio:  0.0 ----------- 0.25 ========= 0.35 ----------- 1.0
                 pellizco     ^ENTRA   zona muerta    EXIT^   mano abierta
```

Traza del autotest (ejecutando el motor, paso de 33 ms):

```
 1066 ms   empieza un pellizco accidental            instant=1
 1133 ms   se suelta a los ~70 ms: no confirma
 1566 ms   empieza un pellizco sostenido             instant=2
 2391 ms   ~800 ms después: CONFIRMA                 confirmed=1
 2930 ms   300 ms con la mano abierta: rearma (IDLE)
 3166 ms   empieza el tercer pellizco                instant=3
 3991 ms   CONFIRMA                                  confirmed=2           -> instant=3, confirmed=2
```

En directo se dibuja un **anillo** en el punto medio entre pulgar e índice (naranja mientras carga, verde al confirmar; el arco crece en
sentido horario desde arriba) y el texto `estado / instantaneos / confirmados / >>> SELECCIONAR_ELEMENTO`.

## Modo Swipe (`SwipeMode` + `SwipeEngine`): gesto dinámico

A diferencia del pellizco (una postura), importa la **trayectoria en el tiempo**. Usa la muñeca (punto 0).

```
 ventana deslizante de 500 ms: cada fotograma añade (t, x, y); se descartan los puntos más viejos
    dx = x_actual - x_del_punto_más_antiguo_de_la_ventana
    dispara si  |dx| >= 0.30  y  |dy| < 0.5 * |dx|      (rápido, largo y casi horizontal)
    después: enfriamiento de 800 ms y se vacía la ventana (evita dobles disparos)

 Dirección: la imagen NO está reflejada. Mover la mano a TU derecha reduce x (dx < 0)  ->  "DERECHA"
```

Traza del autotest: `t=1231 ms DERECHA`, `t=2731 ms IZQUIERDA`; la diagonal final (dx=-0.4, dy=0.8) **no** dispara, porque 0.8 no es menor
que 0.5 · 0.4. En directo: estela naranja de las últimas 15 posiciones y texto `SWIPE ...` durante 1 s.

## Modo Pose (`PoseMode`)

MediaPipe Pose (OpenCV Zoo), **verificado inspeccionando el `.onnx`**:

```
 entrada   input_1    [1, 256, 256, 3]    float32 NHWC, RGB / 255
 salidas   Identity   [1, 195]            39 puntos x 5 valores (x, y, z, visibilidad, presencia)   <- se usa (33 del cuerpo + 6 auxiliares)
           Identity_1 [1, 1]              confianza de persona                                       <- se usa
           (máscara de segmentación, mapas de calor y puntos 3D: se ignoran)

  Puntos usados (izquierda/derecha del SUJETO)          Brazo levantado (y crece hacia abajo)
             0 nariz                                      y(muñeca) < y(hombro) - 0.05
      12 ----- 11   hombros
      |         |
     14        13   codos
      |         |
     16        15   muñecas
      24 ----- 23   caderas

  Ángulo en el vértice b (producto escalar):    u = a - b ; v = c - b ; cos(theta) = (u . v) / (|u| |v|)
       a                                         90° = codo recto ; 180° = brazo estirado
        \
         b----c
```

* El coseno se limita a [-1, 1] porque el redondeo en coma flotante puede dar 1.0000001 y `acos` devolvería `NaN`.
* La normalización usa una **heurística**: si algún valor supera 2.0 se asume que están en píxeles (sobre 256); si no, normalizados.
  No se ha comprobado qué escala devuelve el modelo sin ejecutarlo con una persona delante.
* No hay detector de personas: hay que alejarse para que la parte superior del cuerpo quepa en el cuadrado central.

## Modo Picking 3D (`PickingMode`): raycasting nativo de JavaFX

En vez de calcular rayos a mano, la yema del índice mueve el **cursor real del sistema** con `javafx.scene.robot.Robot`; JavaFX genera
eventos de ratón y resuelve el impacto con `PickResult`.

```
 Cámara -> HandModel -> punta del índice (punto 8) -> coordenadas de imagen -> ventana (x * W/w, y * H/h)
        -> view.localToScreen(x, y) -> robot.mouseMove(...)  -> JavaFX: evento de ratón -> picking
        -> Sphere.setOnMouseEntered(e -> e.getPickResult().getIntersectedPoint())   (Point3D local)

 Ventana auxiliar (StackPane = capas apiladas; la última queda encima)
 +-----------------------------------+
 |  SubScene 3D (fondo transparente) |  <- arriba: recibe el ratón, 3 esferas (ROJA, VERDE, AZUL)
 |  ImageView (fotograma de cámara)  |  <- abajo
 +-----------------------------------+      esferas de radio 40, x = -120, 0, 120; cámara a 500, FOV 30
```

* El `ImageView` estira la imagen exactamente a 640x480, por eso la conversión `fx * W / w` es exacta.
* Autotest: cursor a una esquina (ninguna esfera), al centro de cada esfera (debe informar del impacto) y de nuevo a la esquina.
* La ventana es `alwaysOnTop` (otra ventana encima impediría el picking). Se abre en `prepareLive()` y se cierra siempre en `endLive()`.
* `Robot` solo puede usarse en el hilo de JavaFX: de ahí `onFx(...)` = `Platform.runLater` + `CompletableFuture`.

## Observaciones

* Una inferencia por fotograma en CPU en el mismo hilo que captura: la tasa de fotogramas queda limitada por el modelo.
* Los umbrales (confianza 0.5, pellizco 0.25, margen de brazo levantado 0.05) son valores empíricos fijados en el código.
* La teoría usa una distancia 3D de 0.05 para el pellizco; aquí se usa un **ratio normalizado** de 0.25. Son medidas distintas: el ratio
  no depende de la distancia a la cámara.
* No se usan puntos 3D ni la lateralidad que devuelve el modelo.
