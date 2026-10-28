# Morse BT

App de Android para enviar y recibir mensajes en código Morse entre dos teléfonos por Bluetooth, con sonido Morse real.

## Qué hace

- **Escribir y enviar**: escribes texto, la app lo convierte a Morse (vista previa `· − ·`), y al enviarlo el otro teléfono lo recibe, lo descifra y lo **reproduce en sonido Morse**.
- **Manipulador (telégrafo)**: mantén presionado el botón grande para hacer puntos y rayas.
  - Suena en tu teléfono **y en el del otro en tiempo real**.
  - Lo que tecleas se convierte en letras dentro del campo de texto.
  - El otro teléfono ve en la franja «EN VIVO» lo que vas tecleando.
- **Escuchar**: toca cualquier mensaje del chat para volver a oírlo; el botón ▶ reproduce lo que estás escribiendo sin enviarlo.
- **Luz de señal**: el punto ámbar de arriba se enciende al ritmo del tono.
- **Ajustes** (icono de controles): velocidad 5–40 PPM (palabras por minuto), tono 400–1000 Hz, reproducir al recibir y escuchar al enviar.
- Soporta letras, números, signos de puntuación, **Ñ, ¿ y ¡**. Las tildes se quitan (Morse no las usa).

## Cómo compilarla

### Opción A — Android Studio (recomendada)
1. Instala [Android Studio](https://developer.android.com/studio).
2. *File › Open* y elige la carpeta `MorseBT`.
3. Espera a que termine la sincronización de Gradle (la primera vez descarga dependencias).
   Si Android Studio ofrece actualizar el plugin de Android (AGP), puedes aceptar.
4. Conecta tu teléfono con la *Depuración USB* activada y pulsa ▶ *Run*.
   O genera el APK en *Build › Build Bundle(s)/APK(s) › Build APK(s)* → `app/build/outputs/apk/debug/app-debug.apk`.

### Opción B — Sin instalar nada (GitHub)
1. Sube la carpeta a un repositorio de GitHub.
2. En la pestaña **Actions** se ejecuta «Compilar APK» automáticamente.
3. Al terminar, descarga el artefacto **MorseBT-apk**, pásalo al teléfono e instálalo
   (hay que permitir «instalar apps de origen desconocido»).

## Cómo usarla

1. **Empareja** los dos teléfonos una vez en *Ajustes › Bluetooth*.
2. Abre la app en ambos.
3. En el teléfono A pulsa **Esperar conexión**.
4. En el teléfono B pulsa **Conectar** y elige el teléfono A de la lista.
5. ¡Listo! Escribe y envía, o usa el manipulador.

Consejos:
- Ambos teléfonos deben tener **la misma velocidad (PPM)** para que el descifrado en vivo funcione bien.
- Para teclear a mano empieza con 10–15 PPM: a 15 PPM un punto dura 80 ms y una raya 240 ms.
- Al pedir permisos, acepta «Dispositivos cercanos» (Android 12+).

## Detalles técnicos

- Kotlin, minSdk 26 (Android 8.0), targetSdk 34.
- Bluetooth clásico RFCOMM (perfil SPP, UUID `00001101-0000-1000-8000-00805F9B34FB`).
- Protocolo de texto, una línea por evento:
  - `M:.... --- .-.. .-` → mensaje completo en Morse
  - `K:1` / `K:0` → manipulador presionado / soltado
- Audio: tono senoidal generado con `AudioTrack` (rampas de 5 ms para que no haga «clic»).
  - Mensajes: `MorsePlayer` (cola, temporización estándar 1-3-7).
  - Manipulador: `LiveKeyer` (flujo continuo de baja latencia).

| Archivo | Para qué sirve |
|---|---|
| `MainActivity.kt` | Pantalla, permisos, envío y recepción |
| `BluetoothService.kt` | Servidor/cliente RFCOMM y lectura/escritura de líneas |
| `MorseCode.kt` | Tabla Morse, codificar/decodificar, temporización |
| `MorsePlayer.kt` | Reproduce mensajes completos |
| `LiveKeyer.kt` | Tono en vivo del manipulador |
| `KeyDecoder.kt` | Convierte pulsaciones en puntos, rayas y letras |
| `ChatAdapter.kt` | Burbujas del chat |
