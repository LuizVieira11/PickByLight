# Contratos Críticos — MQTT, NFC e Poka-Yoke

Não altere os contratos abaixo sem atualizar explicitamente `especificacao/ESPECIFICACAO.md` e os testes em `HomologacaoCenariosTest.kt`.

## Protocolo MQTT (QoS 1)
Definido em `app/shared/src/commonMain/kotlin/com/uniso/wms/pickbylight/data/mqtt/model/MqttPayloads.kt` e `MqttService.kt`:
- **`wms/pickbylight/comando`** (`MqttTopics.COMANDO`, App → ESP32):
  - `MqttComandoAcender`: `acao = "ACENDER"`, `estante_led_id`, `prateleira_led_id`, `posicao_led_id`, `cor` (padrão `"VERDE"`).
  - `MqttComandoApagar`: `acao = "APAGAR"`, `estante_led_id`, `prateleira_led_id`, `posicao_led_id`.
  - `MqttComandoPing`: `acao = "PING"`.
  - `MqttComandoTesteLed`: `acao = "TESTE_LED"`, `estante_led_id`, `prateleira_led_id?`, `posicao_led_id?`, `cor` (padrão `"AZUL"`), `duracao_ms` (padrão `3000`).
- **`wms/pickbylight/ack`** (`MqttTopics.ACK`, ESP32 → App):
  - `MqttAck`: `acao = "ACK"`, `posicao_led_id?`, `status` (`"OK"` ou `"ERRO"`).
- **`wms/pickbylight/status/app`** (`MqttTopics.STATUS_APP`, App → Broker, `retained = true`):
  - `MqttStatusApp`: `client = "wms_mobile"`, `status` (`"ONLINE"` ou `"OFFLINE"` via LWT).

## Timeouts, Resiliência e Poka-Yoke
- **Timeout de ACK (5s)**: Se o ESP32 não responder `ACK` em até 5 segundos, permitir transição para **Modo Manual** (`modoManual = true` em `Movimentacao(SAIDA)`), mantendo validação NFC ativa.
- **Timeout de Operação (60s)**: Ao expirar sem leitura NFC válida, disparar `APAGAR` automaticamente, liberar lock otimista e cancelar a operação.
- **Offline**: Broker desconectado exibe banner persistente (`MqttDisconnectedBanner`) e enfileira comandos pendentes (`pendingCommandsCount`) com retry exponencial.
- **NFC Read-Only**: Leitura apenas (`enableReaderMode` no Android), validação de UID hexadecimal de 7 bytes, debounce de 2s. Tag desconhecida ou posição sem saldo suficiente aciona alerta visual vermelho, vibração longa (`600ms`) e bloqueia a baixa (Poka-Yoke).
- **Endereços físicos**: Dimensões L×A×P devem respeitar `^\d{1,4}x\d{1,4}x\d{1,4}(cm|mm)$`.
