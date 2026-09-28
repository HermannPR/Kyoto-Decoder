# Kyoto para Android

App (Kotlin + Jetpack Compose, Material 3) que convierte archivos en imágenes PNG sin
pérdida y las recupera, **compatible con `fotocodec.py`** (formato FCOD1, ver
[`docs/formato-fcod1.md`](../docs/formato-fcod1.md)). Todo es local: la app **no tiene
permiso de Internet** y no sube nada a ninguna nube.

- **Codificar**: elige uno o varios archivos (o compártelos desde otra app con
  «Codificar con Kyoto»). Cada archivo se convierte en una o varias imágenes
  (`foto.zip.0001de0003.png`, …) en la carpeta que elijas o en la carpeta propia de la
  app. En esa carpeta se crea un `.nomedia` antes de escribir la primera imagen, para
  que la galería y Google Fotos no las muestren. Tamaño máximo por imagen: 5–100 MB
  (20 MB por defecto, igual que Python).
- **Recuperar**: elige una carpeta (se revisan subcarpetas) o imágenes sueltas. Las
  partes se agrupan por su cabecera, sin importar nombres ni orden; se verifica el
  SHA-256 y se escriben los archivos con su nombre original. Se informa con precisión:
  `faltan partes 3,7 de foto.zip`, `foto.zip: el hash no coincide…`,
  `foto.zip: la parte 2 está dañada (…)`, imágenes dañadas e imágenes ignoradas.

Los trabajos corren fuera del hilo principal, por streaming (una fila de imagen en
memoria, no el archivo), con progreso, cancelación y un servicio en primer plano con
notificación para que archivos de varios GB no se interrumpan al salir de la app.

## Estructura

```
android/
  codec/   Kotlin puro (sin Android): formato FCOD1, PNG por streaming, pruebas JVM
  app/     app Android (Compose, SAF, servicio en primer plano)
  tools/gen_fixtures.py   genera los fixtures de Python para las pruebas
```

## Compilar

Requiere JDK 17+ y el Android SDK (`local.properties` con `sdk.dir=...`, no se versiona).

```sh
cd android
./gradlew :app:assembleDebug        # APK en app/build/outputs/apk/debug/app-debug.apk
```

## Pruebas de compatibilidad

```sh
cd android
./gradlew :codec:test
```

- `PythonFixturesTest` (Python → Kotlin): decodifica imágenes creadas con
  `fotocodec.py codificar` (en `codec/src/test/resources/fixtures/`) y compara byte a
  byte con los originales. Casos: básico, multiparte renombrada y desordenada, archivo
  vacío, nombre unicode, PNG re-guardado con los 5 filtros (RGB y RGBA, varios IDAT),
  mezcla con fotos normales, partes faltantes, parte alterada (hash) y parte dañada (CRC).
  Para regenerar los fixtures: `py -3 android/tools/gen_fixtures.py` (desde la raíz).
- `KotlinToPythonTest` (Kotlin → Python): codifica con Kotlin, renombra y desordena las
  partes, y ejecuta `py -3 fotocodec.py decodificar` comprobando que el archivo sale
  idéntico; también que Python detecta partes faltantes y alteradas. Usa `py -3` en
  Windows y `python3` en otros sistemas; se puede cambiar con la variable
  `KYOTO_PYTHON` (p. ej. `KYOTO_PYTHON="python3.12"`). Si Python no está, se omiten.
- `Fcod1Test`: ejemplo del documento de formato, cálculos, ida y vuelta, duplicados,
  cancelación y un archivo de 64 MB por streaming.

## Limitaciones

- Solo sin pérdida: si una imagen se recomprime (JPEG/HEIC), se redimensiona o se edita,
  ese archivo no se puede recuperar (se detecta, no se escribe un archivo corrupto).
- El formato no guarda un hash por parte: si una imagen se alteró pero sigue siendo un
  PNG válido, se sabe qué archivo falló pero no qué parte. Las partes con el PNG roto
  (CRC/zlib) sí se señalan una por una.
- La carpeta predeterminada de la app (`Android/data/com.hermannpr.kyoto/files/…`) no
  es accesible para otras apps en Android 11+; elige una carpeta propia para usar las
  imágenes o los archivos recuperados.
- Los archivos compartidos desde otra app se leen con el permiso temporal que da esa
  app; conviene codificarlos en esa misma sesión.
- No cifra: si el contenido es privado, cífralo antes (p. ej. `vault.py --clave` o un
  `.7z` con contraseña).
