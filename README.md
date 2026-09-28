# CamDroid

Prototipo de cámara Android con segmentación de personas 100% local.

Abrir **RETRATO IA** para usar el modelo de segmentación incluido en el APK. No requiere descargas, cuenta ni API key. El manifiesto elimina el permiso de Internet, incluso si una dependencia intenta agregarlo.

El modo retrato incluye cámara frontal/trasera, intensidad de blur de 0 a 100%, perfiles de objetivo virtual (natural/35 mm, retrato/50 mm, retrato pro/85 mm y tele 2×), máscara visible de diagnóstico y foto del resultado GPU. La máscara y la imagen pertenecen al mismo frame. STREAM_MODE estabiliza la segmentación y el shader conserva la persona al difuminar el fondo. La interfaz PersonSegmenter permite reemplazar el modelo.

En Android 10+ las fotos IA aparecen en Pictures/CamDroid. En Android 8/9 se guardan en el directorio privado externo de la app. Las fotos y videos del modo cámara original también se guardan en ese directorio privado y se eliminan al desinstalar.

### Límites actuales

- Preview IA solicitado a 640×480; CameraX negocia la resolución disponible. La interfaz muestra dimensiones, tiempo total y FPS de resultados.
- La foto IA captura el preview renderizado; no es una foto de sensor a resolución completa.
- El video del modo original no tiene efectos IA. El modo retrato no graba video.
- Hay copias CPU→GPU y nuevas imágenes por frame. Es una primera implementación comprobable, pendiente de optimización y mediciones térmicas.
- La segmentación de personas no es un mapa de profundidad y puede fallar en pelo, objetos o poca luz.
- Los perfiles de lente actuales son un recorte GPU conservador; no sustituyen todavía las cámaras físicas ultra-wide/tele ni la superresolución temporal.
- El SDK de segmentación es beta. No hay denoise, enhance ni upscale neuronal implementados.

Motor usado: [ML Kit Selfie Segmentation, modelo incluido](https://developers.google.com/ml-kit/vision/selfie-segmentation/android).

## Abrir

Requiere JDK 17, Gradle 8.9 y Android SDK 35. Compilar con `gradle assembleDebug lintDebug`. GitHub Actions ejecuta estos pasos y publica el APK de prueba como artefacto CamDroid-debug cuando la compilación termina correctamente. El permiso de micrófono es opcional. Todavía requiere pruebas en un teléfono real.

## Próximos incrementos

- Integración del efecto con video y captura de sensor.
- Mejorar bordes, reutilizar buffers y medir GPU/latencia/temperatura en teléfonos reales.
- Selectores reales de resolución y FPS según capacidades del dispositivo.
- Modelos LiteRT locales para denoise, enhance y upscale.

## Prueba en teléfono

1. Activar modo avión antes de abrir por primera vez y entrar a Retrato IA.
2. Mostrar la máscara: la persona debe aparecer blanca y el fondo oscuro.
3. Comparar intensidad 0%/100%, mover la cabeza/manos y cambiar de cámara.
4. Guardar una foto y comprobar orientación, espejo y efecto.
5. Enviar a segundo plano, volver, salir del modo IA y entrar otra vez.
6. Observar FPS y temperatura durante varios minutos. Registrar modelo del teléfono y versión de Android.
