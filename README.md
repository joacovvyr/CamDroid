# CamDroid

Base V1 de una cámara Android con procesamiento local.

Base inicial con preview CameraX, captura de fotos y grabación de video FHD. Las capturas se guardan en el almacenamiento externo privado de la app y se eliminan al desinstalarla. El blur todavía no está implementado y su botón está deshabilitado. EffectPipeline es únicamente una estructura de configuración; no procesa imágenes todavía.

## Abrir

Requiere JDK 17, Gradle 8.9 y Android SDK 35. Compilar con `gradle assembleDebug lintDebug`. GitHub Actions ejecuta estos pasos y publica el APK de prueba como artefacto CamDroid-debug cuando la compilación termina correctamente. El permiso de micrófono es opcional. Todavía requiere pruebas en un teléfono real.

## Próximos incrementos

- Segmentación local de persona con máscara temporal.
- Render GPU del blur usando `CameraEffect`/OpenGL.
- Selectores reales de resolución y FPS según capacidades del dispositivo.
- Modelos LiteRT locales para denoise, enhance y upscale.
