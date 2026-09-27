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
python vault.py agregar  mi-boveda documento.pdf            # lo guarda como PNG(s)
python vault.py agregar  mi-boveda privado.txt --clave      # pide clave y lo cifra
python vault.py listar   mi-boveda                          # nombre, tipo, tamaño, fecha
python vault.py buscar   mi-boveda pdf                      # por nombre o tipo
python vault.py extraer  mi-boveda documento.pdf -o salida/ # decodifica y verifica SHA-256
python vault.py borrar   mi-boveda documento.pdf
```

Estructura de una bóveda:

```
mi-boveda/
  index.json          índice legible: nombre original, tipo, tamaño, fecha, sha256, cifrado
  datos/<id>/*.png    las imágenes PNG de cada archivo
```

Se conservan el nombre y la extensión originales. Al extraer se verifica el SHA-256
(y, si el archivo estaba cifrado, la clave y la integridad antes de escribir nada).

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
búsqueda, borrado, archivos partidos en varias imágenes y cifrar/descifrar (incluida la
detección de clave incorrecta).
