# Roteiro de Testes em Bancada — ESP32 Pick-by-Light (Spec 14.3)

## Ambiente de Referência Validado

- **Hardware:** módulo `ESP-WROOM-32` (4 MB flash, sem PSRAM), porta serial `COM5` (conversor USB-UART `CH340`).
- **LEDs de teste:** `GPIO13` (`CH02`), `GPIO26` (`CH11`) e `GPIO27` (`CH12`), ligação `active_high = true` (`GPIO -> resistor -> LED -> GND`).
- **Broker MQTT:** Mosquitto `2.1.2` em Docker (`192.168.1.6:1883`), acesso anônimo.
- **Firmware / Toolchain:** `esp32/esp32.ino` (commit `5a9d5ae`), core `esp32:esp32 3.3.0`, `ArduinoJson 7.4.2`, MQTT `3.1.1` via ESP-MQTT (`# CONFIG_MQTT_PROTOCOL_5 is not set` no `sdkconfig`).
- **Pré-requisito de CLI:** substituir `<BROKER>` (ex.: `192.168.1.6`) e `<ESP_ID>` (ex.: `esp-a1b2c3`).

---

## Casos de Bancada (1–23)

### 1. Boot
- **Passos:** alimentar/resetar o ESP32 com o broker no ar e observar `wms/pickbylight/v1/esp/<ESP_ID>/status` via `mosquitto_sub -h <BROKER> -t "wms/pickbylight/v1/esp/#" -v`. Resetar novamente e comparar `boot_id`.
- **Esperado:** publica `status: "ONLINE"` (retained) com 14 canais (`CH01`–`CH14`), `mqtt_protocol: "3.1.1"` e `config_revision: 0`; `boot_id` muda a cada reset.
- **Resultado:** **Validado em bancada** (ESP real apareceu `ONLINE` com 14 canais e `boot_id` novo após reboot).

### 2. Corte de energia (LWT)
- **Passos:** com o ESP `ONLINE`, desligar a alimentação da placa e monitorar `wms/pickbylight/v1/esp/<ESP_ID>/status`. Repetir para queda curta e queda longa para observar eventual "pisca" (`OFFLINE` seguido de `ONLINE`).
- **Esperado:** após expiração do keep-alive (~1,5 × 15 s ≈ 22 s), broker publica LWT `status: "OFFLINE"` com `reason: "LWT"` (retained).
- **Resultado:** **Parcialmente validado** — LWT `OFFLINE` validado com ESP real; medição comparativa de queda curta/longa para registrar eventual "pisca" **a executar**.

### 3. Discovery
- **Passos:** publicar `{"schema_version":1,"request_id":"11111111-1111-4111-8111-111111111111"}` em `wms/pickbylight/v1/discovery/request`.
- **Esperado:** ESP republica `status` (`ONLINE`) em `wms/pickbylight/v1/esp/<ESP_ID>/status` sem incluir `request_id`.
- **Resultado:** **A executar**.

### 4. `config/get`
- **Passos:** com configuração já aplicada (`RESTORE_PENDING = false`), publicar `{"schema_version":1,"esp_id":"<ESP_ID>","request_id":"22222222-2222-4222-8222-222222222222"}` em `wms/pickbylight/v1/esp/<ESP_ID>/config/get`.
- **Esperado:** ESP republica `config/state` retained em `wms/pickbylight/v1/esp/<ESP_ID>/config/state` contendo `last_request_id: "22222222-2222-4222-8222-222222222222"`.
- **Resultado:** **A executar**.

### 5. `config/set` válido
- **Passos:** pelo app (ou via `mosquitto_pub` com `expected_revision` atual), gravar mappings nos canais `CH02`, `CH11`, `CH12` e observar `config/result` e `config/state`.
- **Esperado:** `config/result` retorna `status: "OK"` e `config_revision + 1`; `config/state` retained atualiza os mappings; após reboot, a configuração volta via restauração do app.
- **Resultado:** **Validado em bancada** (gravação aplicada e restaurada após reboot com o app conectado).

### 6. Revisão errada
- **Passos:** enviar `config/set` com `expected_revision` divergente da revisão ativa no ESP.
- **Esperado:** `config/result` retorna `status: "ERRO"`, `error_code: "REVISION_MISMATCH"`; `config_revision` e mappings ativos permanecem inalterados.
- **Resultado:** **A executar**.

### 7. Duplicata (`request_id` repetido)
- **Passos:** publicar duas vezes o mesmo `config/set` válido com idêntico `request_id` (QoS 1).
- **Esperado:** apenas uma aplicação; a segunda publicação recebe o mesmo `config/result` salvo no cache de deduplicação (8 últimos `request_id`), sem incrementar `config_revision` novamente.
- **Resultado:** **A executar**.

### 8. Payloads inválidos
- **Passos:** enviar em `config/set`: JSON malformado, `esp_id` divergente, canal inexistente (`CH99`), `channel_id` duplicado, `led_id` duplicado e payload > 2048 bytes.
- **Esperado:** erros enumerados correspondentes (`ESP_ID_MISMATCH`, `UNKNOWN_CHANNEL`, `CHANNEL_CONFLICT`, `LED_ID_CONFLICT`) ou descarte seguro com log WARN (JSON malformado / > 2048 bytes), sem reboot.
- **Resultado:** **A executar**.

### 9. Expiração v5 / disciplina 3.1.1
- **Passos:** publicar `config/set` com o ESP offline (`Message Expiry 2 s` em `-V mqttv5`, ou `Clean Session = true` em `-V mqttv311`); religar o ESP após > 2 s.
- **Esperado:** comando não é reaplicado após a reconexão (em 3.1.1 a sessão limpa impede replay no broker).
- **Resultado:** **A executar** *(nota: firmware opera em MQTT 3.1.1; validar ausência de replay por sessão limpa)*.

### 10. Comandos próprios (`ACENDER`, `APAGAR`, `TESTE_LED`)
- **Passos:** com `CH02`/`CH11`/`CH12` mapeados no app, executar `Testar LED`, iniciar separação (`ACENDER`) e concluir/cancelar (`APAGAR`).
- **Esperado:** LED físico correspondente acende e apaga; ESP dono da posição publica `ACK` `OK` em `wms/pickbylight/ack` (< 500 ms); `TESTE_LED` apaga sozinho ao fim de `duracao_ms`.
- **Resultado:** **Validado em bancada** (`Testar LED` e `ACENDER`/`APAGAR` validados nos LEDs físicos em `GPIO13`/`GPIO26`/`GPIO27`).

### 11. ID de outro ESP
- **Passos:** publicar `ACENDER`/`APAGAR`/`TESTE_LED` com IDs lógicos não mapeados no ESP em teste.
- **Esperado:** nenhum LED acende e nenhum `ACK` é publicado pelo ESP.
- **Resultado:** **A executar**.

### 12. PING
- **Passos:** publicar `{"acao":"PING"}` em `wms/pickbylight/comando` (ou acionar PING no app).
- **Esperado:** publica `{"acao":"PONG","status":"OK","esp_id":"<ESP_ID>","boot_id":"..."}` em `wms/pickbylight/ack`.
- **Resultado:** **A executar**.

### 13. Reconexão (broker e Wi-Fi)
- **Passos:** reiniciar o container do Mosquitto e depois derrubar/restaurar o ponto de acesso Wi-Fi.
- **Esperado:** ESP aplica reconexão automática Wi-Fi e backoff exponencial com jitter no MQTT sem crash/reboot, republicando `status` e `config/state` ao voltar.
- **Resultado:** **A executar**.

### 14. Estresse e estabilidade
- **Passos:** enviar 100 comandos seguidos (`ACENDER`/`APAGAR`/`TESTE_LED`/`PING`) e manter o ESP ligado por 24 h acompanhando `free_heap` e `uptime_ms` no `status` periódico (60 s).
- **Esperado:** nenhum reset inesperado (`boot_id` estável) e `free_heap` sem queda contínua.
- **Resultado:** **A executar**.

### 15. Compatibilidade MQTT 3.1.1 / MQTT 5 no app
- **Passos:** repetir os casos 1–12 com o app configurado em `Automático`, `5.0` e `3.1.1` no Mosquitto 2.1.2 (enquanto o firmware permanece em 3.1.1).
- **Esperado:** todos os fluxos funcionam em `Automático`, `5.0` (app v5 ↔ broker ↔ ESP 3.1.1) e `3.1.1`.
- **Resultado:** **A executar**.

### 16. Cada canal (`CH01`–`CH14`)
- **Passos:** mapear e acionar individualmente os canais `CH01` a `CH14` (GPIOs `4, 13, 16, 17, 18, 19, 21, 22, 23, 25, 26, 27, 32, 33`) e conferir cada LED físico na placa alvo.
- **Esperado:** todos os canais disponíveis na DevKit acendem e apagam sem conflito de pino.
- **Resultado:** **A executar** *(validados até aqui: `CH02`/GPIO13, `CH11`/GPIO26 e `CH12`/GPIO27)*.

### 17. Boot seguro
- **Passos:** com algum LED aceso ou após gravar/resetar a placa, observar todos os canais durante a inicialização antes da conexão Wi-Fi/MQTT.
- **Esperado:** `setup()` coloca todos os canais configurados em nível inativo (`LOW` em `active_high`) antes de iniciar Serial, Wi-Fi e MQTT.
- **Resultado:** **A executar**.

### 18. Sem piscar no boot
- **Passos:** observar visualmente (ou com analisador lógico) os 14 canais padrão desde o instante do reset físico.
- **Esperado:** nenhum canal pisca durante o boot da ROM/bootloader antes da entrada em `setup()`.
- **Resultado:** **A executar**.

### 19. Tabela de canais inválida
- **Passos:** compilar build de teste com `-DALLOW_INVALID_CHANNEL_TABLE_TEST` e um GPIO proibido ou duplicado na `CHANNEL_TABLE`, gravar e observar `status` e `config/set`.
- **Esperado:** nenhum GPIO é acionado, publica `status: "CONFIG_INVALID"` com `error_code: "INVALID_CHANNEL_TABLE"` e `channels: []`; comandos de LED são ignorados e `config/set` retorna `INTERNAL_ERROR`.
- **Resultado:** **A executar**.

### 20. Offline 65 s
- **Passos:** acender um LED via `ACENDER`, parar o broker Mosquitto por mais de 65 s e depois religar o broker.
- **Esperado:** após > 65 s desconectado, o ESP apaga todos os LEDs localmente; ao reconectar ao broker, os LEDs permanecem apagados até novo comando.
- **Resultado:** **Validado em bancada** (ESP real apagou o LED aceso após 65 s sem broker e permaneceu apagado ao reconectar).

### 21. Cor ignorada
- **Passos:** publicar `ACENDER` e `TESTE_LED` com `cor` desconhecida (ex.: `"ROXO"`, `"BRANCO"`).
- **Esperado:** firmware aceita e ignora `cor`, acende o LED normalmente e responde `ACK` `OK`.
- **Resultado:** **A executar**.

### 22. IDs em 2 ESPs
- **Passos:** com 2 ESPs ativos (ou 1 ESP real + `tools/esp_sim.py`), mapear o ID de estante no ESP A e o ID da posição no ESP B, e disparar `ACENDER` para essa posição.
- **Esperado:** ambos os ESPs acendem seus respectivos LEDs e apenas o ESP B (dono de `posicao_led_id`) publica `ACK`.
- **Resultado:** **A executar**.

### 23. Restauração
- **Passos:** (a) religar o ESP sem mappings salvos no app; (b) salvar mappings no app e religar o ESP; (c) desligar o app antes de religar o ESP e abrir o app após > 20 s; (d) usar `[Esquecer ESP]` no app e religar o ESP.
- **Esperado:** (a) restauração vazia (`config_revision = 1`, `mappings = []`); (b) mesma configuração restaurada em poucos segundos; (c) ESP repete `config/restore` com backoff até o app voltar e restaurar; (d) ESP esquecido volta zerado.
- **Resultado:** **Parcialmente validado** — validados em bancada com ESP real: restauração vazia, restauração com mappings após reboot e ESP esquecido voltando zerado; ciclo completo de retry com o app fechado por > 20 s antes de reabrir **a executar**.

