Resumen (respuesta corta)  
Sí — es factible implementar un estado de subida "en tiempo real" (o casi real) en StarExec. La opción práctica y de menor fricción es Server-Sent Events (SSE) para push unidireccional; si se necesita mayor interactividad o control bidireccional, usar WebSockets y un broker (Redis pub/sub) para escalar.
Dónde está hoy (puntos clave del código)
- La página actual refresca la vista cada 10s si la subida no está completa: starexec-app/src/main/webapp/secure/details/uploadStatus.jsp:20 (ver cabecera Refresh).
- El tracking de estado se guarda/actualiza en la base de datos vía utilidades en Uploads, por ejemplo createBenchmarkUploadStatus y funciones de incremento como incrementCompletedBenchmarks, benchmarkFileUploadComplete: starexec-app/src/main/java/org/starexec/data/database/Uploads.java:66.
- El servlet que crea el statusId y lanza el procesamiento asíncrono es UploadBenchmark: starexec-app/src/main/java/org/starexec/servlets/UploadBenchmark.java:88.
Opciones técnicas (tradeoffs rápidos)
- Polling (actual → meta-refresh o AJAX poll):
  - Pros: implementación mínima.
  - Contras: latencia (seconds), carga innecesaria en DB/servidores con muchos clientes.
- SSE (Server-Sent Events):
  - Pros: simple de implementar en Java Servlet; sobre HTTP, funciona bien para notificaciones del servidor hacia el cliente; manejo sencillo en el navegador; reconexión automática.
  - Contras: unidireccional; muchas conexiones concurrentes requieren infraestructura (load balancer con keepalive).
- WebSocket:
  - Pros: full-duplex, baja latencia, ampliamente soportado; mejor para muchas notificaciones y control.
  - Contras: más complejidad; necesita broker (Redis pub/sub o similar) para varias instancias de aplicación.
- Long polling + HTTP/2 (menos recomendado salvo restricciones infra).
Recomendación práctica: comenzar con SSE (rápida, suficiente para mostrar progreso) y, en caso de escala, pasar a WebSockets con Redis pub/sub.
Arquitectura recomendada (implementable y minimalista)
1. Añadir un endpoint SSE en la app:
   - Ruta sugerida: /services/benchmarks/stream/{statusId} (valida permisos con BenchmarkSecurity.canUserSeeBenchmarkStatus).
   - El servlet mantiene la conexión text/event-stream y envía JSON con el BenchmarkUploadStatus cada vez que hay un cambio.
2. Publicar notificaciones cuando cambie el estado:
   - Modificar los puntos que actualizan el status (métodos en Uploads.java como benchmarkFileUploadComplete, incrementCompletedBenchmarks, processingBegun, benchmarkEverythingComplete, setBenchmarkErrorMessage) para publicar un evento a un NotificationService.
   - Inicialmente NotificationService puede ser un singleton en memoria que notifique a conexiones SSE locales.
   - Para múltiples instancias/replicación: hacer que NotificationService publique a Redis pub/sub (canal por statusId) y que los servidores de aplicación se suscriban a Redis y reemitan a sus clientes SSE.
3. Frontend:
   - Reemplazar/añadir a uploadStatus.jsp un pequeño script JS que abre EventSource('/services/benchmarks/stream/{statusId}') y actualiza el DOM (tabla y barra de progreso) en tiempo real. Mantener un fallback de polling cada 10s para clientes sin SSE.
4. Seguridad:
   - Validar sesión/permiso en el endpoint SSE antes de aceptar la conexión.
   - No enviar datos de otros usuarios.
   - Sanitizar/limitar tamaño de mensajes.
5. Operacional:
   - Configurar timeouts de reverse proxy (nginx, haproxy) y límites de conexiones keepalive.
   - Métricas: contadores de conexiones SSE, latencia, errores.
Lista concreta de cambios (alto nivel)
- Backend:
  - Crear UploadStreamServlet (SSE) o endpoint JAX-RS que verifique permisos y entregue text/event-stream.
  - Añadir/extraer NotificationService; instrumentar llamadas en Uploads.java (puntos donde actualmente hacen ps.execute() y después de actualizar DB emitir evento).
  - (Opcional) Integración con Redis pub/sub para multi-instances.
- Frontend:
  - Cambiar starexec-app/src/main/webapp/secure/details/uploadStatus.jsp:20 para eliminar el Refresh y añadir script SSE/AJAX.
  - Actualizar CSS/UX: barra de progreso, spinner, mensajes de error.
- Infra:
  - Si hay múltiples app servers, desplegar Redis y configurar app para usarlo.
  - Ajustar proxy para mantener conexiones SSE.
Esfuerzo estimado (aprox., muy dependiente del equipo)
- PoC (un servidor, SSE local, actualizar 3-5 puntos en Uploads + JSP): 1–2 días.
- Producción (autenticación, tests, Redis pub/sub, proxy tuning, QA): 3–7 días.
- Escalado masivo / WebSocket reemplazo: + varios días.
Ventajas frente al estado actual
- Experiencia de usuario con actualización inmediata.
- Menos carga en cliente y servidor que polling agresivo.
- Capacidad de emitir mensajes de error más útiles (p. ej. fallo en extracción).
Limitaciones / riesgos
- SSE usa 1 conexión por cliente → si hay muchos usuarios concurrentes, necesita tuning de infra.
- Hay que instrumentar varios puntos que hoy sólo escriben DB; si olvidamos alguno, el cliente no recibirá ese evento.
- Cambios en métodos DB deben hacerse con cuidado para no romper transacciones o introducir latencia.
Referencias de código que serán tocadas
- Página actual que refresca: starexec-app/src/main/webapp/secure/details/uploadStatus.jsp:20
- Creación del status y redirección: starexec-app/src/main/java/org/starexec/servlets/UploadBenchmark.java:88
- Lógica de estado y métodos de incremento: starexec-app/src/main/java/org/starexec/data/database/Uploads.java:66
Preguntas para afinar el alcance
- ¿Prefieres una solución incremental (SSE PoC en una sola instancia) o quieres desde el inicio una solución multi‑instance con Redis?  
- ¿Cuántas conexiones SSE simultáneas esperas en producción (orden de magnitud)? Esto ayuda a dimensionar infra.  
- ¿Deseas que el cliente muestre una barra de progreso basada en porcentaje (requiere total/estimado) o sólo indicadores (etapas: uploaded/extracted/processing/complete)?
