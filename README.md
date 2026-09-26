# CamDroid

Base V1 de una cámara Android con procesamiento local.

Incluye preview CameraX, captura de fotos, grabación de video, selector inicial de calidad FHD y un control de intensidad para el efecto de blur. `EffectPipeline` es la frontera para añadir segmentación de persona, shaders GPU y modelos LiteRT sin acoplarlos a la UI.

## Abrir

Abrir la carpeta en Android Studio Hedgehog o posterior y ejecutar en un dispositivo Android 8.0+ con permisos de cámara y micrófono.

## Próximos incrementos

- Segmentación local de persona con máscara temporal.
- Render GPU del blur usando `CameraEffect`/OpenGL.
- Selectores reales de resolución y FPS según capacidades del dispositivo.
- Modelos LiteRT locales para denoise, enhance y upscale.
