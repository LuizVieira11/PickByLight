# Contexto do Projeto — WMS Pick-by-Light

Sistema **WMS (Warehouse Management System)** multiplataforma em **Kotlin Multiplatform (KMP)** e **Compose Multiplatform**, com pacote base `com.uniso.wms.pickbylight`. Integra dispositivos **Android** (NFC *read-only* + Eclipse Paho MQTT QoS 1 + DataStore) e interface **Web/Wasm** a estantes físicas controladas por **ESP32 + WS2812B** via MQTT.

## Fonte da Verdade
- **`especificacao/ESPECIFICACAO.md`**: Especificação técnica vinculante (arquitetura, entidades, fluxos de tela, protocolo MQTT, tratamento de falhas e os 10 cenários de homologação). Consulte as seções relevantes antes de planejar ou alterar regras de negócio.

## Estrutura do Repositório
- `app/shared/src/commonMain/`: Domínio (`domain/model/Entities.kt`, `domain/repository/WmsRepository.kt`), Dados (`data/repository/InMemoryWmsRepository.kt`, `data/seed/WmsSeeder.kt`, `data/mqtt/`, `data/settings/`), NFC (`nfc/`), ViewModels UDF e Telas Compose M3 (`ui/screens/`), Tema Industrial (`ui/theme/`), Responsividade (`ui/responsive/WindowSizeClass.kt`).
- `app/shared/src/androidMain/`: Implementações nativas Android (Paho MQTT, DataStore, NFC ReaderMode, feedback háptico/sonoro).
- `app/shared/src/wasmJsMain/`: Implementações Web/Wasm.
- `app/shared/src/commonTest/`: Testes unitários e os 10 cenários de homologação (`HomologacaoCenariosTest.kt`).
- `app/androidApp/` e `app/webApp/`: Entry-points Android e Web/Wasm.
- `core/`: Utilitários multiplataforma compartilhados.

## Regras de Arquitetura
- Padrão **Clean Architecture + MVVM + UDF** (`UiState`, `ViewModel`, `StateFlow`).
- **Pureza KMP**: Nunca importe classes específicas de Android/JVM (`android.*`, `java.*`) em `commonMain`. Use `expect`/`actual` ou interfaces injetadas via providers (`MqttServiceProvider`, `WmsRepositoryProvider`, `NfcController`, `FeedbackController`).
- **Persistência atual**: Os dados operacionais rodam em memória (`InMemoryWmsRepository` + `WmsSeeder`); não há banco PostgreSQL/Room ativo nesta fase. Apenas configurações (ex.: URL do broker MQTT) usam `DataStore` (`BrokerUrlDataStore`).
