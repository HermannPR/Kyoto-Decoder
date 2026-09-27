# Kyoto Decoder

`fotocodec.py` guarda cualquier archivo como una o varias imágenes PNG y lo recupera **idéntico**,
verificado con SHA-256. Un solo archivo de Python, sin dependencias: corre en
Windows, macOS, Linux o Termux (Android) con Python 3.8+.

```sh
python fotocodec.py codificar documento.zip -o imagenes/      # archivo -> PNG(s)
python fotocodec.py decodificar imagenes/ -o recuperado/     # PNG(s) -> archivo
```

Los archivos grandes se parten en varias imágenes (`--max-mb`, 20 MB por imagen por
defecto). Para decodificar, pon todas las partes en una carpeta; el orden de los
nombres no importa.

## Cómo funciona

Cada imagen es un PNG RGB de 8 bits, cuadrado, cuyos píxeles son los bytes del
archivo precedidos de una cabecera:

| Campo | Bytes | Uso |
|---|---|---|
| Magia `FCOD1` | 5 | Reconocer imágenes de foto-codec |
| Id del archivo | 8 | Agrupar las partes de un mismo archivo |
| Parte / total | 4 + 4 | Reensamblar en orden y detectar faltantes |
| Tamaño del trozo / total | 8 + 8 | Quitar el relleno del último píxel |
| SHA-256 | 32 | Verificar que el archivo regresó intacto |
| Nombre | 2 + n | Nombre original (solo el nombre base al restaurar) |

## Límites

- **Solo sin pérdida.** Si una imagen se convierte a JPEG, se redimensiona o se le
  aplica cualquier edición, los datos se pierden. El decodificador lo detecta por el
  hash y no escribe un archivo corrupto.
- Un PNG re-guardado por otra herramienta **sin pérdida** (otros filtros o compresión)
  sí se decodifica.
- No cifra: cualquiera con la herramienta puede recuperar el archivo. Si el contenido
  es privado, cífralo antes (por ejemplo con un `.zip` o `.7z` con contraseña).

## Bóveda local (`vault.py`)

`vault.py` es una capa encima de `fotocodec.py`: gestiona una carpeta-bóveda que
**por fuera parece un gestor de archivos normal** (agregar, listar, buscar, extraer,
borrar) pero **por dentro guarda cada archivo como imágenes PNG**. Es de **uso local**:
no sube nada a ningún servicio en la nube.

```sh
python vault.py agregar  mi-boveda documento.pdf            # lo guarda como PNG(s), organizado por fecha
python vault.py agregar  mi-boveda foto.jpg --por tipo      # o organizado por tipo/extensión
python vault.py agregar  mi-boveda privado.txt --clave      # pide clave y lo cifra
python vault.py listar   mi-boveda                          # nombre, tipo, tamaño, fecha
python vault.py buscar   mi-boveda pdf                      # por nombre o tipo (busca en toda la bóveda)
python vault.py extraer  mi-boveda documento.pdf -o salida/ # decodifica y verifica SHA-256
python vault.py borrar   mi-boveda documento.pdf
python vault.py exportar mi-boveda listo-para-subir/        # copia los PNG organizados a una carpeta aparte
```

### Organización por carpetas

Los PNG de cada archivo se guardan en su propia subcarpeta dentro de `datos/`,
para que la bóveda sea cómoda de revisar u ordenar a mano. `--por` elige el
esquema al agregar (por defecto `fecha`):

```
mi-boveda/
  index.json                                índice legible: nombre, tipo, tamaño, fecha, sha256, cifrado, ruta_datos
  datos/2024/06/20240614-093000_documento_ab12.../*.png   organización por fecha (AAAA/MM)
  datos/pdf/20240615-101500_informe_cd34.../*.png         organización por tipo (--por tipo)
```

El nombre de cada subcarpeta empieza con la fecha y hora (`AAAAMMDD-HHMMSS`) para
que quede ordenable cronológicamente aunque la organización elegida sea por tipo,
y termina en el id de la entrada para que sea único.

`listar`, `buscar`, `extraer` y `borrar` funcionan igual sin importar el esquema
elegido, y recorren las subcarpetas de forma recursiva si hace falta. Las bóvedas
creadas por una versión anterior de `vault.py` (estructura plana `datos/<id>/`)
se siguen leyendo sin necesidad de migrar nada: `vault.py` reconoce ambas formas.

Se conservan el nombre y la extensión originales. Al extraer se verifica el SHA-256
(y, si el archivo estaba cifrado, la clave y la integridad antes de escribir nada).

### Exportar una carpeta lista para subir a mano

`vault.py exportar mi-boveda CARPETA_DESTINO [--por fecha|tipo] [--filtro TEXTO]`
copia (no mueve) los PNG de la bóveda a `CARPETA_DESTINO`, organizados igual que
arriba, sin el `index.json` ni nada interno de la bóveda: una carpeta limpia de
solo imágenes, lista para copiar a donde el dueño quiera. `--filtro` exporta solo
las entradas cuyo nombre o tipo coincidan (igual que `buscar`).

## Flujo manual: codificar, guardar donde quieras, y recuperar

`vault.py` (y `fotocodec.py`) son herramientas **locales**: no incluyen ninguna
integración de nube ni subida automática a ningún servicio. El flujo pensado es
manual, en tres pasos:

1. **Codificar y organizar.** Agrega tus archivos a la bóveda (`vault.py agregar`,
   con `--clave` si quieres cifrarlos) o, si solo quieres las imágenes sueltas sin
   índice, usa `fotocodec.py codificar` directamente. Con `vault.py exportar`
   preparas además una carpeta ya organizada por fecha o tipo, lista para copiar.
2. **Subir las imágenes a donde tú decidas.** Son archivos PNG normales: cópialos
   a un disco externo, a tu propio servidor, a un servicio de almacenamiento de
   archivos genérico, o a donde prefieras. Esa decisión es tuya y el proceso es
   manual; la herramienta no se conecta a ningún servicio por ti. Ten en cuenta
   que subir archivos disfrazados de fotos a servicios pensados **solo** para
   fotos puede violar sus términos de servicio y poner en riesgo tu cuenta, así
   que revisa las condiciones del servicio que elijas antes de usarlo así.
3. **Recuperar y verificar.** Cuando quieras el archivo de vuelta, junta las
   imágenes de esa entrada en una carpeta y usa `vault.py extraer` (o
   `fotocodec.py decodificar` si no usaste una bóveda). En ambos casos se
   recalcula el SHA-256 del contenido recuperado y se compara contra el guardado
   al codificar: si no coincide (imagen recomprimida, editada o incompleta), no
   se escribe ningún archivo corrupto y se avisa del error.

### Cifrado opcional

Con `--clave` (o `--pedir-clave`, que la solicita sin mostrarla) el archivo se cifra
antes de convertirse en imágenes, usando **solo la librería estándar**:

- La clave de 64 bytes se deriva con `PBKDF2-HMAC-SHA256` (200 000 iteraciones, salt
  aleatorio por archivo).
- El contenido se cifra con un flujo tipo CTR: cada bloque es
  `HMAC-SHA256(clave_cifrado, contador)` y se aplica XOR sobre los datos. Como el salt
  es único por archivo, el flujo nunca se reutiliza.
- Se usa **encrypt-then-MAC**: un `HMAC-SHA256` sobre el texto cifrado detecta una clave
  incorrecta o cualquier alteración antes de descifrar.

> Nota: es una construcción de librería estándar razonable, no un formato auditado como
> AES-GCM. Para secretos de alto valor, cifra además con una herramienta dedicada.

## Pruebas

```sh
python -m unittest -v test_fotocodec test_vault
```

Las de `fotocodec` cubren ida y vuelta con varios tamaños, nombres con acentos, archivos
partidos, imágenes alteradas, partes faltantes, PNG re-guardados con los cinco filtros y
nombres maliciosos que intentan escribir fuera de la carpeta de salida.

Las de `vault` cubren ida y vuelta, el índice y sus metadatos, extraer por nombre o por id,
búsqueda, borrado, archivos partidos en varias imágenes, cifrar/descifrar (incluida la
detección de clave incorrecta), la organización por carpetas (fecha y tipo), la
compatibilidad con bóvedas de estructura plana, la búsqueda/extracción recursiva cuando
una carpeta se movió a mano, y el comando `exportar`.
