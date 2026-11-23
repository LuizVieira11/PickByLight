/*
 * Etapa 6.0 — teste de bancada SEM rede (Wi-Fi/MQTT desligados).
 * Percorre a tabela de canais da spec (MQTT5_E_ESP32.md 3.2) e mostra no Monitor Serial (115200)
 * qual canal está ativo, para conferir fiação, polaridade (active_high) e corrente.
 *
 * Para cada canal: 1,5 s em nível ALTO e 1,5 s em nível BAIXO.
 *   - LED acende no ALTO  -> ligação GPIO -> resistor -> LED -> GND (active_high = true, padrão).
 *   - LED acende no BAIXO -> ligação 3V3 -> LED -> resistor -> GPIO (active_high = false).
 * No fim de cada volta, todos em ALTO por 3 s (teste de corrente: se a placa reiniciar aqui,
 * a causa do reset aparece no próximo boot como BROWNOUT).
 *
 * GPIO 0 e 12 são proibidos (strapping: podem impedir o boot) e 2/5/14/15 ficam fora por cautela;
 * por isso não estão na tabela. Mantenha LEDs só nos GPIOs abaixo.
 */
#include <esp_system.h>

struct Canal { const char* id; uint8_t gpio; };

// Tabela 3.2 da spec, na ordem (a mesma que o firmware publica em status.capabilities.channels).
const Canal CANAIS[] = {
  {"CH01", 4},  {"CH02", 13}, {"CH03", 16}, {"CH04", 17}, {"CH05", 18}, {"CH06", 19}, {"CH07", 21},
  {"CH08", 22}, {"CH09", 23}, {"CH10", 25}, {"CH11", 26}, {"CH12", 27}, {"CH13", 32}, {"CH14", 33},
};
const size_t N = sizeof(CANAIS) / sizeof(CANAIS[0]);

const char* motivoReset(esp_reset_reason_t r) {
  switch (r) {
    case ESP_RST_POWERON:  return "liga/desliga (normal)";
    case ESP_RST_BROWNOUT: return "BROWNOUT: queda de tensão! Corrente alta demais ou alimentação fraca";
    case ESP_RST_PANIC:    return "PANIC (erro de software)";
    case ESP_RST_TASK_WDT:
    case ESP_RST_INT_WDT:
    case ESP_RST_WDT:      return "WATCHDOG";
    case ESP_RST_SW:       return "reinício por software";
    case ESP_RST_EXT:      return "botão EN/reset";
    default:               return "outro";
  }
}

void todos(uint8_t nivel) {
  for (size_t i = 0; i < N; i++) digitalWrite(CANAIS[i].gpio, nivel);
}

void setup() {
  // Primeiro os pinos (antes de qualquer outra coisa), todos em BAIXO.
  for (size_t i = 0; i < N; i++) {
    pinMode(CANAIS[i].gpio, OUTPUT);
    digitalWrite(CANAIS[i].gpio, LOW);
  }
  Serial.begin(115200);
  delay(500);
  Serial.println();
  Serial.println("=== Teste de bancada 6.0 (sem rede) ===");
  Serial.printf("Motivo do último reset: %s\n", motivoReset(esp_reset_reason()));
  Serial.printf("PSRAM detectada: %s (WROOM-32 deve dizer NAO; se SIM, GPIO16/17 nao servem)\n",
                psramFound() ? "SIM" : "NAO");
  Serial.println("Se algum LED acendeu AGORA (tudo em BAIXO), ele esta ligado como active_low.");
}

void loop() {
  for (size_t i = 0; i < N; i++) {
    Serial.printf("%s  GPIO%-2u  ALTO  -> acendeu? (active_high)\n", CANAIS[i].id, CANAIS[i].gpio);
    digitalWrite(CANAIS[i].gpio, HIGH);
    delay(1500);
    Serial.printf("%s  GPIO%-2u  BAIXO -> acendeu? (active_low)\n", CANAIS[i].id, CANAIS[i].gpio);
    digitalWrite(CANAIS[i].gpio, LOW);
    delay(1500);
  }
  Serial.println(">>> TODOS em ALTO por 3 s (teste de corrente)");
  todos(HIGH);
  delay(3000);
  todos(LOW);
  Serial.println(">>> volta completa; recomecando\n");
  delay(1000);
}
