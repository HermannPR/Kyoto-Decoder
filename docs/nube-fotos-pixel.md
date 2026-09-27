# Centro de fotos en el Pixel (fotos reales)

> Parte del proyecto Kyoto, **separada** de la bóveda (`vault.py`). Aquí NO se codifica
> ni se disfraza nada: son **fotos reales** que respaldas en Google Fotos del Pixel,
> que es justo para lo que Google da ese espacio. Para guardar *archivos* como imágenes,
> usa la bóveda; eso se queda en tu disco y **no** va a Google.

## Qué logra

Desde cualquier dispositivo (tu teléfono, la laptop, el de un familiar) seleccionas
muchas fotos y las mandas al **Pixel**; el Pixel las respalda solo en su Google Fotos.
Así juntas las fotos de varios equipos en un mismo lugar respaldado.

## Cómo se arma (sin apps raras, todo gratis y open source)

### 1. Que se alcancen entre sí: Tailscale
Instala **Tailscale** en el Pixel y en cada dispositivo, con la misma cuenta. Así se
ven entre ellos aunque no estén en la misma casa (red privada cifrada). Si solo los
vas a usar en la misma wifi, puedes saltarte esto.

### 2. Enviar fotos elegidas: LocalSend
**LocalSend** (open source, tipo AirDrop) en cada dispositivo y en el Pixel:
1. Abres LocalSend, eliges muchas fotos y tocas **Enviar**.
2. Aparece el Pixel por su nombre; lo eliges.
3. Llegan a una carpeta del Pixel (por defecto `Descargas/LocalSend` o `Pictures/LocalSend`).
No pasa por ninguna nube intermedia: va directo de un aparato al otro.

### 3. (Opcional) Sincronizar una carpeta sola: Syncthing
Si quieres que una carpeta de fotos se copie al Pixel **sin elegir nada**, instala
**Syncthing** en el dispositivo y en el Pixel, y comparte esa carpeta hacia una del Pixel.

### 4. Que el Pixel las respalde: Google Fotos
En el **Pixel**: Google Fotos → foto de perfil → **Copia de seguridad** → activarla, y en
**Copia de seguridad de carpetas del dispositivo** enciende la carpeta donde caen las
fotos (la de LocalSend y/o Syncthing). Desde ahí Google Fotos las sube solo.

## Comprobar que funciona
Manda una foto de prueba desde otro dispositivo con LocalSend → ábrela en el Pixel →
confirma en unos minutos que aparece en Google Fotos (con la wifi encendida).

## Límites honestos
- El respaldo del Pixel es para **fotos y videos reales**. No metas aquí archivos
  disfrazados: para eso está la bóveda, que se queda local.
- El Pixel tiene que estar encendido y con internet para respaldar.
- LocalSend y Syncthing no cuestan ni usan servidores de terceros; Tailscale es gratis
  para uso personal.
