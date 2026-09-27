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

## Pruebas

```sh
python -m unittest -v test_fotocodec
```

Cubren ida y vuelta con varios tamaños, nombres con acentos, archivos partidos,
imágenes alteradas, partes faltantes, PNG re-guardados con los cinco filtros y nombres
maliciosos que intentan escribir fuera de la carpeta de salida.
