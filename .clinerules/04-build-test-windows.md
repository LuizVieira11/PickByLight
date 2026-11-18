# Build, Testes e Ambiente Windows

- **OS**: Windows (use `gradlew.bat`, nunca `./gradlew`).
- **Java Daemon**: Configurado em `gradle/gradle-daemon-jvm.properties` (Java 21 Azul Zulu). Se houver travamento de daemon, use `gradlew.bat --stop`.
- **Progresso visível**: Nunca use `-q` ou `--quiet` nos comandos Gradle.

## Comandos Principais
- **Testes unitários e de homologação (obrigatório após mudanças de lógica):**
  ```cmd
  gradlew.bat :app:shared:testAndroidHostTest --rerun-tasks
  ```
  *(Sempre use `--rerun-tasks` ao validar alterações para evitar resultados `UP-TO-DATE` em cache.)*

- **Build APK Debug Android:**
  ```cmd
  gradlew.bat :app:androidApp:assembleDebug
  ```

- **Executar Web/Wasm em desenvolvimento:**
  ```cmd
  gradlew.bat :app:webApp:wasmJsBrowserDevelopmentRun
  ```
