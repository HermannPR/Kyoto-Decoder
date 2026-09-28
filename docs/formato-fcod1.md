# Formato FCOD1 (contrato byte a byte)

Este documento describe **exactamente** lo que implementa `fotocodec.py` (y lo que
reimplementa la app Android `android/codec`). Es el contrato compartido: cualquier
implementación que lo cumpla puede leer las imágenes de las otras.

Convenciones: todos los enteros son **big-endian sin signo**. "Parte" se numera desde
**0** dentro de la cabecera y desde **1** en los nombres de archivo y en los mensajes
al usuario.

## 1. Vista general

```
archivo original ──► se parte en trozos de `trozo` bytes ──► por cada trozo:
    carga = CABECERA(71 bytes) + NOMBRE(n bytes, UTF-8) + TROZO(largo bytes)
    carga ──► píxeles RGB de un PNG cuadrado (relleno con 0x00) ──► parte.png
```

Una imagen = una parte. Un archivo = `total` imágenes que comparten el mismo
`id` (y el mismo SHA-256, tamaño total y nombre).

## 2. Cabecera de cada parte

`struct.Struct(">5s8sIIQQ32sH")` = **71 bytes**, seguida del nombre:

| Offset | Largo | Tipo | Campo | Valor |
|---:|---:|---|---|---|
| 0  | 5  | bytes | `magia` | ASCII `FCOD1` = `46 43 4F 44 31` |
| 5  | 8  | bytes | `id` | los **primeros 8 bytes** del SHA-256 del archivo completo |
| 13 | 4  | u32 | `parte` | índice de esta parte, **0 … total-1** |
| 17 | 4  | u32 | `total` | número de partes del archivo (≥ 1) |
| 21 | 8  | u64 | `largo` | bytes de datos del archivo que lleva esta parte |
| 29 | 8  | u64 | `tam` | tamaño total del archivo original |
| 37 | 32 | bytes | `sha` | SHA-256 del archivo original completo |
| 69 | 2  | u16 | `lnom` | largo en bytes del nombre |
| 71 | lnom | bytes | `nombre` | `os.path.basename(ruta)` codificado en **UTF-8** (sin terminador) |
| 71+lnom | largo | bytes | datos | `archivo[parte*trozo : parte*trozo + largo]` |

Notas:

- `id` es redundante con `sha[:8]`; existe para agrupar rápido.
- `nombre` se repite idéntico en todas las partes. Máximo 65 535 bytes (límite de `H`).
- El tamaño del trozo (`trozo`) **no** se guarda; solo `largo` de cada parte.

## 3. Partición

```
trozo = max(1, int(max_mb * 1024 * 1024))          # por defecto max_mb = 20 → 20 971 520
total = max(1, ceil(tam / trozo))                  # un archivo vacío produce 1 parte con largo 0
parte i (0-based): archivo[i*trozo : (i+1)*trozo]  # todas miden `trozo` salvo la última
```

`max_mb` puede ser decimal (`--max-mb 0.1` → `trozo = 104 857`). Todas las partes llevan
la misma cabecera salvo `parte` y `largo`.

Nombre de archivo que usa el codificador (solo informativo, **el decodificador no lo usa**):

```
f"{nombre}.{parte+1:04d}de{total:04d}.png"         # p. ej. foto.zip.0003de0012.png
```

## 4. Bytes → píxeles → PNG

1. `carga = cabecera + nombre + datos` (longitud `L = 71 + lnom + largo`).
2. `pixeles = ceil(L / 3)`; `lado = max(1, ceil(sqrt(pixeles)))`.
   La imagen es **cuadrada**: `lado × lado`. (La app calcula la raíz entera exacta;
   coincide con `math.ceil(math.sqrt(n))` para cualquier tamaño práctico.)
3. Se rellena `carga` con bytes `0x00` hasta `lado * lado * 3`.
4. Los bytes se colocan en orden: fila por fila de arriba abajo, píxel por píxel de
   izquierda a derecha, canal **R, G, B**. Es decir, el byte `k` de la carga es el canal
   `k % 3` del píxel `(x, y)` con `y = (k // 3) // lado`, `x = (k // 3) % lado`.
5. PNG escrito:
   - Firma `89 50 4E 47 0D 0A 1A 0A`.
   - `IHDR`: ancho = alto = `lado`, profundidad 8, tipo de color **2 (RGB)**,
     compresión 0, filtro 0, **sin entrelazado**.
   - `IDAT`: `zlib.compress(crudo, 9)` en **un solo** chunk, donde `crudo` es, por cada
     fila, un byte de filtro `0x00` (None) seguido de `lado*3` bytes de la fila.
   - `IEND` vacío. Cada chunk lleva su CRC-32 (`zlib.crc32(tipo + datos)`).
   - Sin chunks auxiliares (sin gAMA/sRGB/pHYs, sin paleta, sin alfa).

El PNG es **sin pérdida**; convertirlo a JPEG, redimensionarlo o editarlo destruye los datos.

## 5. Lectura del PNG (lo que un decodificador debe aceptar)

`leer_png` es tolerante a que otra herramienta re-guarde el PNG **sin pérdida**:

- Acepta profundidad **8** con tipo de color **2 (RGB)** o **6 (RGBA)**; sin entrelazar.
  Cualquier otra cosa (paleta, gris, 16 bits, Adam7) → error "PNG no soportado".
- Concatena **todos** los `IDAT` (pueden ser varios chunks) y descomprime con zlib.
- Acepta los **5 filtros** por fila (0 None, 1 Sub, 2 Up, 3 Average, 4 Paeth) con
  `bpp = canales` (3 o 4); la fila previa de la primera fila es todo ceros.
  Paeth: `p=a+b-c`; gana `a` si `pa<=pb y pa<=pc`, si no `b` si `pb<=pc`, si no `c`.
- Con RGBA se **descarta el canal alfa** (cada 4.º byte) y se usan solo R, G, B.
- Ignora los chunks auxiliares. `fotocodec.py` **no** verifica los CRC de los chunks;
  la app Android sí los verifica (una imagen con CRC erróneo se reporta como dañada).
- El resultado son `ancho * alto * 3` bytes; la cabecera se lee desde el byte 0.

## 6. Decodificación y agrupación

1. Entrada: una imagen o una carpeta. `fotocodec.py` toma de la carpeta todo archivo
   que termine en `.png` (sin distinguir mayúsculas; **no** recursivo). La app Android
   además recorre subcarpetas.
2. Por cada imagen: leer píxeles, desempacar la cabecera (`CAB.unpack_from`), exigir
   `magia == b"FCOD1"`; si falla, la imagen se **omite** con aviso (no aborta).
3. Agrupar por `id` (los 8 bytes). **Los nombres de archivo y el orden de las imágenes
   no importan**: las partes pueden estar renombradas, desordenadas o mezcladas con
   partes de otros archivos o con fotos normales.
4. En cada grupo, ordenar por el campo `parte`. Si falta algún índice en `0…total-1`
   → error `"<nombre>: faltan partes [3, 7]"` (números 1-based). `total` se toma de la
   primera parte encontrada.
5. `datos = concat(datos de la parte 0, 1, …, total-1)`; cada parte aporta exactamente
   sus primeros `largo` bytes tras el nombre (el resto es relleno y se descarta).
6. Verificar `len(datos) == tam` **y** `sha256(datos) == sha`. Si no → error "el hash no
   coincide" y **no se escribe** el archivo.
7. Escribir con **solo el nombre base** (`os.path.basename(nombre)`, o `"archivo"` si
   queda vacío) dentro de la carpeta de salida; un nombre como `../../x` no escapa.

Diferencias de la app Android (compatibles con el formato, más estrictas en el reporte):

- Agrupa por `(id, sha, total, tam, nombre)` en vez de solo `id`: dos archivos con
  contenido idéntico pero distinto nombre se recuperan por separado (en Python se funden).
- Si hay partes repetidas con el mismo índice se usa la primera válida.
- Un grupo incompleto o con hash erróneo no aborta a los demás: se reporta
  (`faltan partes 3,7 de foto.zip`, `el hash no coincide`) y se siguen recuperando
  los otros archivos.
- Lee y escribe por *streaming*: nunca carga un archivo entero en memoria (en Python,
  `codificar` y `decodificar` sí lo hacen).

## 7. Ejemplo mínimo

Archivo `hi.txt` con contenido `hola` (4 bytes), `max_mb = 20`:

- `sha256("hola") = b221d9dbb083a7f3 3428d7c2a3c3198ae925614d70210e28716ccaa7cd4ddb79`
- `trozo = 20 971 520`, `total = 1`, una sola parte `hi.txt.0001de0001.png`.
- Carga (81 bytes = 71 cabecera + 6 nombre + 4 datos):

```
46 43 4F 44 31                                   "FCOD1"
B2 21 D9 DB B0 83 A7 F3                          id = sha[:8]
00 00 00 00                                      parte = 0
00 00 00 01                                      total = 1
00 00 00 00 00 00 00 04                          largo = 4
00 00 00 00 00 00 00 04                          tam = 4
B2 21 D9 DB B0 83 A7 F3 34 28 D7 C2 A3 C3 19 8A
E9 25 61 4D 70 21 0E 28 71 6C CA A7 CD 4D DB 79  sha
00 06                                            lnom = 6
68 69 2E 74 78 74                                "hi.txt"
68 6F 6C 61                                      "hola"
```

- `pixeles = ceil(81/3) = 27`, `lado = ceil(sqrt(27)) = 6` → PNG 6×6 RGB, 108 bytes de
  píxeles: los 81 de la carga + 27 ceros de relleno.
- El primer píxel es `(0x46, 0x43, 0x4F)` = "FCO"; el píxel 26 (fila 4, columna 2)
  es `(0x6F, 0x6C, 0x61)` = "ola"; los píxeles 27…35 son `(0, 0, 0)` (relleno).
- Datos zlib: 6 filas × (1 byte de filtro `00` + 18 bytes).

Si el archivo midiera 50 000 bytes con `--max-mb 0.01` (`trozo = 10 485`), saldrían
`total = 5` partes con `largo` = 10 485, 10 485, 10 485, 10 485 y 8 060.
