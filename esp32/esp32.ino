/*
 * PickByLight — Firmware ESP32 (Contrato v1 + Comandos Legados)
 * Fonte da verdade: especificacao/MQTT5_E_ESP32.md
 *
 * Alvo e ambiente validados:
 * - Modulo: ESP-WROOM-32 (ESP32-D0WDQ6, 4 MB flash, sem PSRAM)
 * - FQBN: esp32:esp32:esp32 (4 MB flash, particoes padrao)
 * - Core Arduino/ESP-IDF: esp32:esp32 3.3.0 (ESP-IDF idf-release_v5.5-b66b5448-v1)
 * - Biblioteca JSON: ArduinoJson 7.4.2
 * - Caminho MQTT: MQTT 3.1.1 via ESP-MQTT (mqtt_client.h)
 * - Evidencia sdkconfig: sdkconfig:2325 -> "# CONFIG_MQTT_PROTOCOL_5 is not set"
 */

#if !defined(ARDUINO_ARCH_ESP32)
#error "Firmware exclusivo para ESP32 (FQBN esp32:esp32:esp32)."
#endif

#include <Arduino.h>
#include <ArduinoJson.h>
#include <WiFi.h>
#include <atomic>
#include <ctype.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

extern "C" {
#include "esp_mac.h"
#include "esp_random.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "mqtt_client.h"
}

#if defined(__has_include) && __has_include("secrets.h")
#include "secrets.h"
#define HAS_LOCAL_SECRETS 1
#else
#include "secrets.example.h"
#define HAS_LOCAL_SECRETS 0
#endif

// ============================================================================
// (1) Configuracao e constantes
// ============================================================================

static constexpr char FIRMWARE_VERSION[] = "1.0.0";
static constexpr char MQTT_PROTOCOL_STR[] = "3.1.1";
static constexpr int SCHEMA_VERSION = 1;

static constexpr uint8_t MAX_CHANNELS = 14;
static constexpr uint8_t ACTIVE_CHANNEL_COUNT = 14;  // Reduzivel <= 14 conforme maquete

static constexpr size_t MAX_CONFIG_BYTES = 2048;
static constexpr size_t MAX_STATUS_BYTES = 2048;
static constexpr size_t MAX_OTHER_BYTES = 512;
static constexpr size_t MQTT_CLIENT_BUF_BYTES = 4096;

static constexpr uint32_t HEARTBEAT_INTERVAL_MS = 60000UL;
static constexpr uint32_t OFFLINE_LED_SAFETY_MS = 65000UL;
static constexpr uint32_t RESTORE_BACKOFF_MIN_MS = 10000UL;
static constexpr uint32_t RESTORE_BACKOFF_MAX_MS = 60000UL;
static constexpr uint32_t MQTT_BACKOFF_MIN_MS = 1000UL;
static constexpr uint32_t MQTT_BACKOFF_MAX_MS = 30000UL;
static constexpr uint32_t TEST_LED_DEFAULT_MS = 3000UL;
static constexpr uint32_t TEST_LED_MAX_MS = 65000UL;

static constexpr size_t DEDUP_CACHE_SIZE = 8;
static constexpr size_t RX_QUEUE_DEPTH = 8;
static constexpr size_t CTRL_QUEUE_DEPTH = 8;
static constexpr size_t MAX_LED_ID_LEN = 64;
static constexpr size_t UUID_LEN = 36;

// Desligamento controlado (reason="SHUTDOWN") omitido por ausencia de pino/botao na placa alvo.

// Tipos internos declarados antes da primeira funcao para compatibilidade com auto-prototyping Arduino.
enum TopicKind : uint8_t {
  TK_UNKNOWN = 0,
  TK_COMANDO,
  TK_DISCOVERY_REQ,
  TK_CONFIG_GET,
  TK_CONFIG_SET
};

enum CtrlEventKind : uint8_t {
  CTRL_MQTT_CONNECTED = 1,
  CTRL_MQTT_DISCONNECTED,
  CTRL_MQTT_ERROR
};

struct MqttRxMessage {
  TopicKind topicKind;
  uint16_t payloadLen;
  bool payloadTooLarge;
  char payload[MAX_CONFIG_BYTES + 1];
};

enum MappingKind : uint8_t {
  KIND_ESTANTE = 0,
  KIND_PRATELEIRA,
  KIND_POSICAO
};

struct ActiveMapping {
  bool inUse;
  char ledId[MAX_LED_ID_LEN + 1];
  MappingKind kind;
  uint8_t channelIdx;
};

struct DedupEntry {
  bool valid;
  char requestId[UUID_LEN + 1];
  uint16_t resultLen;
  char resultPayload[MAX_OTHER_BYTES + 1];
};

enum FsmState : uint8_t {
  FSM_HALT_PLACEHOLDERS = 0,
  FSM_WAIT_WIFI,
  FSM_MQTT_CONNECTING,
  FSM_ONLINE
};

// ============================================================================
// (2) Identidade
// ============================================================================

static char g_espId[16] = {0};   // "esp-a1b2c3"
static char g_bootId[16] = {0};  // 8 hex minusculos

static char g_topicStatus[64] = {0};
static char g_topicConfigGet[64] = {0};
static char g_topicConfigState[64] = {0};
static char g_topicConfigSet[64] = {0};
static char g_topicConfigResult[64] = {0};
static char g_topicConfigRestore[64] = {0};

static constexpr char TOPIC_COMANDO[] = "wms/pickbylight/comando";
static constexpr char TOPIC_ACK[] = "wms/pickbylight/ack";
static constexpr char TOPIC_DISCOVERY_REQ[] = "wms/pickbylight/v1/discovery/request";

static char g_lwtPayload[160] = {0};

static void logMsg(const char* level, const char* fmt, ...) {
  char buf[224];
  va_list args;
  va_start(args, fmt);
  vsnprintf(buf, sizeof(buf), fmt, args);
  va_end(args);
  Serial.printf("[%lu][%s][%s] %s\n",
                (unsigned long)(esp_timer_get_time() / 1000ULL),
                g_espId[0] ? g_espId : "esp-boot",
                level,
                buf);
}

static void initIdentityAndTopics() {
  uint8_t mac[6] = {0};
  esp_efuse_mac_get_default(mac);
  snprintf(g_espId, sizeof(g_espId), "esp-%02x%02x%02x", mac[3], mac[4], mac[5]);

  uint32_t r = esp_random();
  if (r == 0) {
    r = 0x9e3779b9UL;
  }
  snprintf(g_bootId, sizeof(g_bootId), "%08lx", (unsigned long)r);

  snprintf(g_topicStatus, sizeof(g_topicStatus), "wms/pickbylight/v1/esp/%s/status", g_espId);
  snprintf(g_topicConfigGet, sizeof(g_topicConfigGet), "wms/pickbylight/v1/esp/%s/config/get", g_espId);
  snprintf(g_topicConfigState, sizeof(g_topicConfigState), "wms/pickbylight/v1/esp/%s/config/state", g_espId);
  snprintf(g_topicConfigSet, sizeof(g_topicConfigSet), "wms/pickbylight/v1/esp/%s/config/set", g_espId);
  snprintf(g_topicConfigResult, sizeof(g_topicConfigResult), "wms/pickbylight/v1/esp/%s/config/result", g_espId);
  snprintf(g_topicConfigRestore, sizeof(g_topicConfigRestore), "wms/pickbylight/v1/esp/%s/config/restore", g_espId);

  snprintf(g_lwtPayload,
           sizeof(g_lwtPayload),
           "{\"schema_version\":1,\"esp_id\":\"%s\",\"boot_id\":\"%s\",\"status\":\"OFFLINE\",\"reason\":\"LWT\"}",
           g_espId,
           g_bootId);
}

// ============================================================================
// (3) GPIO e canais
// ============================================================================

struct ChannelDef {
  const char* id;
  uint8_t gpio;
  bool activeHigh;
};

// Tabela recomendada da secao 3.2 (GPIO 12 e 14 fora; GPIO 2 reservado).
static constexpr ChannelDef CHANNEL_TABLE[ACTIVE_CHANNEL_COUNT] = {
    {"CH01", 4, true},
    {"CH02", 13, true},
    {"CH03", 16, true},
    {"CH04", 17, true},
    {"CH05", 18, true},
    {"CH06", 19, true},
    {"CH07", 21, true},
    {"CH08", 22, true},
    {"CH09", 23, true},
    {"CH10", 25, true},
    {"CH11", 26, true},
    {"CH12", 27, true},
    {"CH13", 32, true},
    {"CH14", 33, true},
};

static constexpr bool isRecommendedChannelGpio(uint8_t gpio) {
  return gpio == 4 || gpio == 13 || gpio == 16 || gpio == 17 || gpio == 18 ||
         gpio == 19 || gpio == 21 || gpio == 22 || gpio == 23 || gpio == 25 ||
         gpio == 26 || gpio == 27 || gpio == 32 || gpio == 33;
}

static constexpr bool isChannelIdFormatConst(const char* id) {
  return id != nullptr && id[0] == 'C' && id[1] == 'H' &&
         id[2] >= '0' && id[2] <= '9' && id[3] >= '0' && id[3] <= '9' &&
         id[4] == '\0';
}

static constexpr bool strEqConst(const char* a, const char* b) {
  return (a[0] == b[0]) && (a[0] == '\0' ? true : strEqConst(a + 1, b + 1));
}

static constexpr bool validateChannelTableConst() {
  if (ACTIVE_CHANNEL_COUNT < 1 || ACTIVE_CHANNEL_COUNT > MAX_CHANNELS) {
    return false;
  }
  for (size_t i = 0; i < ACTIVE_CHANNEL_COUNT; ++i) {
    if (!isChannelIdFormatConst(CHANNEL_TABLE[i].id)) {
      return false;
    }
    if (!isRecommendedChannelGpio(CHANNEL_TABLE[i].gpio)) {
      return false;
    }
    for (size_t j = i + 1; j < ACTIVE_CHANNEL_COUNT; ++j) {
      if (CHANNEL_TABLE[i].gpio == CHANNEL_TABLE[j].gpio) {
        return false;
      }
      if (strEqConst(CHANNEL_TABLE[i].id, CHANNEL_TABLE[j].id)) {
        return false;
      }
    }
  }
  return true;
}

#ifndef ALLOW_INVALID_CHANNEL_TABLE_TEST
static_assert(validateChannelTableConst(), "Tabela de canais invalida (secao 3.2)");
#endif

static bool g_channelTableValid = false;
static bool g_ledOn[MAX_CHANNELS] = {false};
static bool g_testActive[MAX_CHANNELS] = {false};
static uint32_t g_testUntilMs[MAX_CHANNELS] = {0};

static bool validateChannelTableRuntime() {
  return validateChannelTableConst();
}

static void writeChannelHardware(size_t idx, bool on) {
  if (!g_channelTableValid || idx >= ACTIVE_CHANNEL_COUNT) {
    return;
  }
  g_ledOn[idx] = on;
  const bool high = CHANNEL_TABLE[idx].activeHigh ? on : !on;
  digitalWrite(CHANNEL_TABLE[idx].gpio, high ? HIGH : LOW);
}

static void initChannelsOffFirst() {
  g_channelTableValid = validateChannelTableRuntime();
  for (size_t i = 0; i < MAX_CHANNELS; ++i) {
    g_ledOn[i] = false;
    g_testActive[i] = false;
    g_testUntilMs[i] = 0;
  }
  if (!g_channelTableValid) {
    return;
  }
  for (size_t i = 0; i < ACTIVE_CHANNEL_COUNT; ++i) {
    const uint8_t gpio = CHANNEL_TABLE[i].gpio;
    pinMode(gpio, OUTPUT);
    digitalWrite(gpio, CHANNEL_TABLE[i].activeHigh ? LOW : HIGH);
  }
}

static void turnOffAllLeds() {
  for (size_t i = 0; i < ACTIVE_CHANNEL_COUNT; ++i) {
    g_testActive[i] = false;
    g_testUntilMs[i] = 0;
    writeChannelHardware(i, false);
  }
}

static int findChannelIndexById(const char* channelId) {
  if (channelId == nullptr) {
    return -1;
  }
  for (size_t i = 0; i < ACTIVE_CHANNEL_COUNT; ++i) {
    if (strcmp(CHANNEL_TABLE[i].id, channelId) == 0) {
      return (int)i;
    }
  }
  return -1;
}

// ============================================================================
// (4) Wi-Fi
// ============================================================================

static bool credentialsArePlaceholders() {
  return WIFI_SSID[0] == '\0' || WIFI_SSID[0] == '<' ||
         MQTT_HOST[0] == '\0' || MQTT_HOST[0] == '<';
}

static void initWifi() {
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
}

static bool isWifiConnected() {
  return WiFi.status() == WL_CONNECTED;
}

// ============================================================================
// (5) MQTT (ESP-MQTT 3.1.1, filas FreeRTOS e remontagem)
// ============================================================================

static esp_mqtt_client_handle_t g_mqttClient = nullptr;
static QueueHandle_t g_rxQueue = nullptr;
static QueueHandle_t g_ctrlQueue = nullptr;
static std::atomic<bool> g_mqttConnected{false};
static std::atomic<uint32_t> g_rxDroppedCount{0};

// Estado de remontagem exclusivo da task interna do ESP-MQTT
static struct {
  bool active;
  TopicKind kind;
  size_t expectedTotal;
  size_t maxBytes;
  size_t copied;
  bool tooLarge;
  char buf[MAX_CONFIG_BYTES + 1];
} s_reasm = {false, TK_UNKNOWN, 0, 0, 0, false, {0}};

static TopicKind classifyTopic(const char* topic, int topicLen) {
  if (topic == nullptr || topicLen <= 0) {
    return TK_UNKNOWN;
  }
  auto eq = [&](const char* lit) -> bool {
    const size_t n = strlen(lit);
    return (size_t)topicLen == n && memcmp(topic, lit, n) == 0;
  };
  if (eq(TOPIC_COMANDO)) return TK_COMANDO;
  if (eq(TOPIC_DISCOVERY_REQ)) return TK_DISCOVERY_REQ;
  if (eq(g_topicConfigGet)) return TK_CONFIG_GET;
  if (eq(g_topicConfigSet)) return TK_CONFIG_SET;
  return TK_UNKNOWN;
}

static size_t maxBytesForTopic(TopicKind kind) {
  return (kind == TK_CONFIG_SET) ? MAX_CONFIG_BYTES : MAX_OTHER_BYTES;
}

static void resetReassembly() {
  s_reasm.active = false;
  s_reasm.kind = TK_UNKNOWN;
  s_reasm.expectedTotal = 0;
  s_reasm.maxBytes = 0;
  s_reasm.copied = 0;
  s_reasm.tooLarge = false;
}

static void onMqttDataEvent(const esp_mqtt_event_handle_t event) {
  if (event->current_data_offset == 0) {
    // Offset 0 sempre inicia nova mensagem e descarta remontagem anterior.
    resetReassembly();
    const TopicKind kind = classifyTopic(event->topic, event->topic_len);
    if (kind == TK_UNKNOWN) {
      return;
    }
    const size_t total = (event->total_data_len > 0) ? (size_t)event->total_data_len
                                                     : (size_t)(event->data_len > 0 ? event->data_len : 0);
    s_reasm.active = true;
    s_reasm.kind = kind;
    s_reasm.expectedTotal = total;
    s_reasm.maxBytes = maxBytesForTopic(kind);
    s_reasm.copied = 0;
    s_reasm.tooLarge = (total > s_reasm.maxBytes);
  }

  if (!s_reasm.active) {
    return;
  }

  const size_t chunkLen = (event->data_len > 0 && event->data != nullptr) ? (size_t)event->data_len : 0;
  const size_t offset = (event->current_data_offset >= 0) ? (size_t)event->current_data_offset : 0;

  if (!s_reasm.tooLarge && chunkLen > 0) {
    if (offset + chunkLen > s_reasm.maxBytes || offset != s_reasm.copied) {
      s_reasm.tooLarge = true;
    } else {
      memcpy(s_reasm.buf + offset, event->data, chunkLen);
      s_reasm.copied += chunkLen;
    }
  }

  if (offset + chunkLen >= s_reasm.expectedTotal) {
    MqttRxMessage msg;
    msg.topicKind = s_reasm.kind;
    msg.payloadTooLarge = s_reasm.tooLarge;
    msg.payloadLen = s_reasm.tooLarge ? 0 : (uint16_t)s_reasm.copied;
    if (!s_reasm.tooLarge && s_reasm.copied <= MAX_CONFIG_BYTES) {
      memcpy(msg.payload, s_reasm.buf, s_reasm.copied);
      msg.payload[s_reasm.copied] = '\0';
    } else {
      msg.payload[0] = '\0';
    }
    if (xQueueSend(g_rxQueue, &msg, 0) != pdTRUE) {
      g_rxDroppedCount.fetch_add(1, std::memory_order_relaxed);
    }
    resetReassembly();
  }
}

static void mqttEventHandler(void* /*handler_args*/,
                             esp_event_base_t /*base*/,
                             int32_t event_id,
                             void* event_data) {
  esp_mqtt_event_handle_t event = static_cast<esp_mqtt_event_handle_t>(event_data);
  CtrlEventKind ctrl = CTRL_MQTT_ERROR;
  switch ((esp_mqtt_event_id_t)event_id) {
    case MQTT_EVENT_CONNECTED:
      g_mqttConnected.store(true, std::memory_order_release);
      ctrl = CTRL_MQTT_CONNECTED;
      xQueueSend(g_ctrlQueue, &ctrl, 0);
      break;
    case MQTT_EVENT_DISCONNECTED:
      g_mqttConnected.store(false, std::memory_order_release);
      resetReassembly();
      ctrl = CTRL_MQTT_DISCONNECTED;
      xQueueSend(g_ctrlQueue, &ctrl, 0);
      break;
    case MQTT_EVENT_ERROR:
      ctrl = CTRL_MQTT_ERROR;
      xQueueSend(g_ctrlQueue, &ctrl, 0);
      break;
    case MQTT_EVENT_DATA:
      onMqttDataEvent(event);
      break;
    default:
      break;
  }
}

static bool initMqttClient() {
  g_rxQueue = xQueueCreate(RX_QUEUE_DEPTH, sizeof(MqttRxMessage));
  g_ctrlQueue = xQueueCreate(CTRL_QUEUE_DEPTH, sizeof(CtrlEventKind));
  if (g_rxQueue == nullptr || g_ctrlQueue == nullptr) {
    return false;
  }

  esp_mqtt_client_config_t cfg = {};
  cfg.broker.address.hostname = MQTT_HOST;
  cfg.broker.address.port = MQTT_PORT;
  cfg.broker.address.transport = MQTT_TRANSPORT_OVER_TCP;

  cfg.credentials.client_id = g_espId;
  if (MQTT_USER[0] != '\0') {
    cfg.credentials.username = MQTT_USER;
  }
  if (MQTT_PASSWORD[0] != '\0') {
    cfg.credentials.authentication.password = MQTT_PASSWORD;
  }

  cfg.session.protocol_ver = MQTT_PROTOCOL_V_3_1_1;
  cfg.session.disable_clean_session = false;
  cfg.session.keepalive = 15;
  cfg.session.last_will.topic = g_topicStatus;
  cfg.session.last_will.msg = g_lwtPayload;
  cfg.session.last_will.msg_len = (int)strlen(g_lwtPayload);
  cfg.session.last_will.qos = 1;
  cfg.session.last_will.retain = 1;

  cfg.buffer.size = MQTT_CLIENT_BUF_BYTES;
  cfg.buffer.out_size = MQTT_CLIENT_BUF_BYTES;
  cfg.network.timeout_ms = 5000;
  cfg.network.disable_auto_reconnect = true;

  g_mqttClient = esp_mqtt_client_init(&cfg);
  if (g_mqttClient == nullptr) {
    return false;
  }
  if (esp_mqtt_client_register_event(g_mqttClient, MQTT_EVENT_ANY, mqttEventHandler, nullptr) != ESP_OK) {
    return false;
  }
  return true;
}

static void enqueuePublish(const char* topic, const char* payload, size_t len, int qos, int retain) {
  if (g_mqttClient == nullptr || !g_mqttConnected.load(std::memory_order_acquire)) {
    return;
  }
  const int msgId = esp_mqtt_client_enqueue(g_mqttClient, topic, payload, (int)len, qos, retain, true);
  if (msgId < 0) {
    logMsg("WARN", "enqueue falhou (%d) em %s", msgId, topic);
  }
}

static void subscribeAllTopics() {
  if (g_mqttClient == nullptr) {
    return;
  }
  esp_mqtt_client_subscribe_single(g_mqttClient, TOPIC_COMANDO, 1);
  esp_mqtt_client_subscribe_single(g_mqttClient, TOPIC_DISCOVERY_REQ, 1);
  esp_mqtt_client_subscribe_single(g_mqttClient, g_topicConfigGet, 1);
  esp_mqtt_client_subscribe_single(g_mqttClient, g_topicConfigSet, 1);
}

// ============================================================================
// (6) Protocolo e JSON
// ============================================================================

static char g_txBuf[MAX_CONFIG_BYTES + 1] = {0};

static uint64_t getUptimeMs() {
  return (uint64_t)(esp_timer_get_time() / 1000ULL);
}

static bool isUuid(const char* s) {
  if (s == nullptr || strlen(s) != UUID_LEN) {
    return false;
  }
  for (size_t i = 0; i < UUID_LEN; ++i) {
    if (i == 8 || i == 13 || i == 18 || i == 23) {
      if (s[i] != '-') return false;
    } else {
      if (!isxdigit((unsigned char)s[i])) return false;
    }
  }
  return true;
}

static void generateUuidV4(char* out, size_t outSize) {
  uint8_t b[16];
  esp_fill_random(b, sizeof(b));
  b[6] = (uint8_t)((b[6] & 0x0F) | 0x40);
  b[8] = (uint8_t)((b[8] & 0x3F) | 0x80);
  snprintf(out,
           outSize,
           "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
           b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7],
           b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]);
}

static bool hasOnlyAllowedKeys(JsonObjectConst obj, const char* const* allowed, size_t count) {
  for (JsonPairConst kv : obj) {
    const char* key = kv.key().c_str();
    bool ok = false;
    for (size_t i = 0; i < count; ++i) {
      if (strcmp(key, allowed[i]) == 0) {
        ok = true;
        break;
      }
    }
    if (!ok) {
      return false;
    }
  }
  return true;
}

static bool serializeAndPublish(const JsonDocument& doc,
                                const char* topic,
                                size_t maxBytes,
                                int qos,
                                int retain) {
  if (doc.overflowed()) {
    logMsg("ERROR", "JsonDocument overflow ao serializar %s", topic);
    return false;
  }
  const size_t n = serializeJson(doc, g_txBuf, sizeof(g_txBuf));
  if (n == 0 || n >= sizeof(g_txBuf) || n > maxBytes) {
    logMsg("ERROR", "Payload excedeu %u bytes (%u) em %s", (unsigned)maxBytes, (unsigned)n, topic);
    return false;
  }
  enqueuePublish(topic, g_txBuf, n, qos, retain);
  return true;
}

// ============================================================================
// (7) Estado da configuracao em RAM e restauracao (NVS removida - ADR-5)
// ============================================================================

static ActiveMapping g_mappings[MAX_CHANNELS];
static uint32_t g_configRevision = 0;
static bool g_restorePending = true;
static char g_lastRequestId[UUID_LEN + 1] = {0};

static DedupEntry g_dedupCache[DEDUP_CACHE_SIZE];
static size_t g_dedupNextIdx = 0;

static uint32_t g_restoreBackoffMs = RESTORE_BACKOFF_MIN_MS;
static uint32_t g_nextRestoreAtMs = 0;

static const char* kindToStr(MappingKind k) {
  switch (k) {
    case KIND_ESTANTE: return "ESTANTE";
    case KIND_PRATELEIRA: return "PRATELEIRA";
    case KIND_POSICAO: return "POSICAO";
  }
  return "POSICAO";
}

static bool parseKind(const char* s, MappingKind* out) {
  if (s == nullptr) return false;
  if (strcmp(s, "ESTANTE") == 0) {
    *out = KIND_ESTANTE;
    return true;
  }
  if (strcmp(s, "PRATELEIRA") == 0) {
    *out = KIND_PRATELEIRA;
    return true;
  }
  if (strcmp(s, "POSICAO") == 0) {
    *out = KIND_POSICAO;
    return true;
  }
  return false;
}

static void initConfigRam() {
  for (size_t i = 0; i < MAX_CHANNELS; ++i) {
    g_mappings[i].inUse = false;
    g_mappings[i].ledId[0] = '\0';
    g_mappings[i].kind = KIND_POSICAO;
    g_mappings[i].channelIdx = 0;
  }
  for (size_t i = 0; i < DEDUP_CACHE_SIZE; ++i) {
    g_dedupCache[i].valid = false;
    g_dedupCache[i].requestId[0] = '\0';
    g_dedupCache[i].resultLen = 0;
    g_dedupCache[i].resultPayload[0] = '\0';
  }
  g_dedupNextIdx = 0;
  g_configRevision = 0;
  g_restorePending = true;
  g_lastRequestId[0] = '\0';
  g_restoreBackoffMs = RESTORE_BACKOFF_MIN_MS;
  g_nextRestoreAtMs = 0;
}

static const DedupEntry* findDedup(const char* requestId) {
  if (requestId == nullptr) return nullptr;
  for (size_t i = 0; i < DEDUP_CACHE_SIZE; ++i) {
    if (g_dedupCache[i].valid && strcmp(g_dedupCache[i].requestId, requestId) == 0) {
      return &g_dedupCache[i];
    }
  }
  return nullptr;
}

static void storeDedup(const char* requestId, const char* payload, size_t len) {
  if (requestId == nullptr || payload == nullptr || len > MAX_OTHER_BYTES) {
    return;
  }
  DedupEntry& slot = g_dedupCache[g_dedupNextIdx];
  slot.valid = true;
  snprintf(slot.requestId, sizeof(slot.requestId), "%s", requestId);
  memcpy(slot.resultPayload, payload, len);
  slot.resultPayload[len] = '\0';
  slot.resultLen = (uint16_t)len;
  g_dedupNextIdx = (g_dedupNextIdx + 1) % DEDUP_CACHE_SIZE;
}

static void publishConfigRestore() {
  if (!g_channelTableValid || !g_restorePending) {
    return;
  }
  char reqId[UUID_LEN + 1];
  generateUuidV4(reqId, sizeof(reqId));

  JsonDocument doc;
  doc["schema_version"] = SCHEMA_VERSION;
  doc["esp_id"] = g_espId;
  doc["boot_id"] = g_bootId;
  doc["request_id"] = reqId;
  serializeAndPublish(doc, g_topicConfigRestore, MAX_OTHER_BYTES, 1, 0);
  logMsg("INFO", "config/restore publicado (request_id=%s)", reqId);
}

// ============================================================================
// (8) Comandos legados (wms/pickbylight/comando -> wms/pickbylight/ack)
// ============================================================================

static bool setChannelStateByLedId(const char* ledId, bool on, bool isTest, uint32_t testUntilMs) {
  if (ledId == nullptr || ledId[0] == '\0') {
    return false;
  }
  bool matched = false;
  for (size_t i = 0; i < ACTIVE_CHANNEL_COUNT; ++i) {
    if (g_mappings[i].inUse && strcmp(g_mappings[i].ledId, ledId) == 0) {
      const uint8_t ch = g_mappings[i].channelIdx;
      if (isTest) {
        g_testActive[ch] = true;
        g_testUntilMs[ch] = testUntilMs;
      } else {
        g_testActive[ch] = false;
        g_testUntilMs[ch] = 0;
      }
      writeChannelHardware(ch, on);
      matched = true;
    }
  }
  return matched;
}

static bool ownsLedId(const char* ledId) {
  if (ledId == nullptr || ledId[0] == '\0') {
    return false;
  }
  for (size_t i = 0; i < ACTIVE_CHANNEL_COUNT; ++i) {
    if (g_mappings[i].inUse && strcmp(g_mappings[i].ledId, ledId) == 0) {
      return true;
    }
  }
  return false;
}

static void publishLegacyAck(const char* posicaoLedId, const char* requestId) {
  JsonDocument doc;
  doc["acao"] = "ACK";
  if (posicaoLedId != nullptr && posicaoLedId[0] != '\0') {
    doc["posicao_led_id"] = posicaoLedId;
  }
  doc["status"] = "OK";
  doc["esp_id"] = g_espId;
  doc["boot_id"] = g_bootId;
  if (requestId != nullptr && requestId[0] != '\0') {
    doc["request_id"] = requestId;
  }
  serializeAndPublish(doc, TOPIC_ACK, MAX_OTHER_BYTES, 1, 0);
}

static void publishLegacyPong(const char* requestId) {
  JsonDocument doc;
  doc["acao"] = "PONG";
  doc["status"] = "OK";
  doc["esp_id"] = g_espId;
  doc["boot_id"] = g_bootId;
  if (requestId != nullptr && requestId[0] != '\0') {
    doc["request_id"] = requestId;
  }
  serializeAndPublish(doc, TOPIC_ACK, MAX_OTHER_BYTES, 1, 0);
}

static void handleLegacyCommand(const MqttRxMessage& msg) {
  if (msg.payloadTooLarge) {
    logMsg("WARN", "Comando legado excedeu %u bytes", (unsigned)MAX_OTHER_BYTES);
    return;
  }

  JsonDocument doc;
  const DeserializationError err = deserializeJson(doc, msg.payload, msg.payloadLen);
  if (err || !doc.is<JsonObjectConst>()) {
    logMsg("WARN", "JSON invalido em comando legado: %s", err ? err.c_str() : "not object");
    return;
  }

  JsonObjectConst root = doc.as<JsonObjectConst>();
  const char* acao = root["acao"].is<const char*>() ? root["acao"].as<const char*>() : nullptr;
  if (acao == nullptr) {
    logMsg("WARN", "Comando legado sem acao");
    return;
  }

  const char* reqId = root["request_id"].is<const char*>() ? root["request_id"].as<const char*>() : nullptr;

  if (strcmp(acao, "PING") == 0) {
    publishLegacyPong(reqId);
    return;
  }

  if (!g_channelTableValid) {
    logMsg("WARN", "Comando %s ignorado: tabela de canais invalida", acao);
    return;
  }

  const char* estId = root["estante_led_id"].is<const char*>() ? root["estante_led_id"].as<const char*>() : nullptr;
  const char* pratId = root["prateleira_led_id"].is<const char*>() ? root["prateleira_led_id"].as<const char*>() : nullptr;
  const char* posId = root["posicao_led_id"].is<const char*>() ? root["posicao_led_id"].as<const char*>() : nullptr;

  if (strcmp(acao, "ACENDER") == 0 || strcmp(acao, "APAGAR") == 0) {
    const bool on = (strcmp(acao, "ACENDER") == 0);
    setChannelStateByLedId(estId, on, false, 0);
    setChannelStateByLedId(pratId, on, false, 0);
    setChannelStateByLedId(posId, on, false, 0);
    if (ownsLedId(posId)) {
      publishLegacyAck(posId, reqId);
    }
    return;
  }

  if (strcmp(acao, "TESTE_LED") == 0) {
    uint32_t duracaoMs = TEST_LED_DEFAULT_MS;
    if (!root["duracao_ms"].isNull() && root["duracao_ms"].is<long long>()) {
      const long long raw = root["duracao_ms"].as<long long>();
      if (raw <= 0) {
        duracaoMs = 1;
      } else if (raw > (long long)TEST_LED_MAX_MS) {
        duracaoMs = TEST_LED_MAX_MS;
      } else {
        duracaoMs = (uint32_t)raw;
      }
    }
    const uint32_t untilMs = millis() + duracaoMs;
    setChannelStateByLedId(estId, true, true, untilMs);
    setChannelStateByLedId(pratId, true, true, untilMs);
    setChannelStateByLedId(posId, true, true, untilMs);

    if (posId != nullptr && posId[0] != '\0') {
      if (ownsLedId(posId)) {
        publishLegacyAck(posId, reqId);
      }
    } else if (pratId != nullptr && pratId[0] != '\0') {
      if (ownsLedId(pratId)) {
        publishLegacyAck(nullptr, reqId);
      }
    } else if (estId != nullptr && estId[0] != '\0') {
      if (ownsLedId(estId)) {
        publishLegacyAck(nullptr, reqId);
      }
    }
    return;
  }

  logMsg("WARN", "Acao legada desconhecida: %s", acao);
}

static void tickTestLedTimers(uint32_t nowMs) {
  for (size_t i = 0; i < ACTIVE_CHANNEL_COUNT; ++i) {
    if (g_testActive[i] && (int32_t)(nowMs - g_testUntilMs[i]) >= 0) {
      g_testActive[i] = false;
      g_testUntilMs[i] = 0;
      writeChannelHardware(i, false);
    }
  }
}

// ============================================================================
// (9) Gerenciamento v1 (status, discovery, config/get, config/set)
// ============================================================================

static void publishStatusOnline() {
  JsonDocument doc;
  doc["schema_version"] = SCHEMA_VERSION;
  doc["esp_id"] = g_espId;
  doc["boot_id"] = g_bootId;
  doc["status"] = g_channelTableValid ? "ONLINE" : "CONFIG_INVALID";
  doc["config_revision"] = g_configRevision;
  if (!g_channelTableValid) {
    doc["error_code"] = "INVALID_CHANNEL_TABLE";
  }

  JsonObject caps = doc["capabilities"].to<JsonObject>();
  caps["firmware_version"] = FIRMWARE_VERSION;
  caps["mqtt_protocol"] = MQTT_PROTOCOL_STR;
  JsonArray chArr = caps["channels"].to<JsonArray>();
  if (g_channelTableValid) {
    for (size_t i = 0; i < ACTIVE_CHANNEL_COUNT; ++i) {
      JsonObject c = chArr.add<JsonObject>();
      c["channel_id"] = CHANNEL_TABLE[i].id;
      c["gpio"] = CHANNEL_TABLE[i].gpio;
      c["active_high"] = CHANNEL_TABLE[i].activeHigh;
    }
  }

  doc["uptime_ms"] = getUptimeMs();
  if (isWifiConnected()) {
    const int rssi = WiFi.RSSI();
    if (rssi >= -127 && rssi <= 0) {
      doc["rssi"] = rssi;
    }
  }
  doc["free_heap"] = esp_get_free_heap_size();

  serializeAndPublish(doc, g_topicStatus, MAX_STATUS_BYTES, 1, 1);
}

static void publishConfigState(const char* lastRequestId) {
  if (!g_channelTableValid || g_restorePending) {
    return;
  }
  JsonDocument doc;
  doc["schema_version"] = SCHEMA_VERSION;
  doc["esp_id"] = g_espId;
  doc["boot_id"] = g_bootId;
  doc["config_revision"] = g_configRevision;

  JsonArray arr = doc["mappings"].to<JsonArray>();
  for (size_t i = 0; i < ACTIVE_CHANNEL_COUNT; ++i) {
    if (g_mappings[i].inUse) {
      JsonObject m = arr.add<JsonObject>();
      m["led_id"] = g_mappings[i].ledId;
      m["kind"] = kindToStr(g_mappings[i].kind);
      m["channel_id"] = CHANNEL_TABLE[g_mappings[i].channelIdx].id;
    }
  }

  doc["uptime_ms"] = getUptimeMs();
  if (lastRequestId != nullptr && isUuid(lastRequestId)) {
    doc["last_request_id"] = lastRequestId;
  } else if (g_lastRequestId[0] != '\0') {
    doc["last_request_id"] = g_lastRequestId;
  }

  serializeAndPublish(doc, g_topicConfigState, MAX_CONFIG_BYTES, 1, 1);
}

static void publishConfigResultAndCache(const char* requestId,
                                        bool ok,
                                        const char* errorCode,
                                        const char* message) {
  JsonDocument doc;
  doc["schema_version"] = SCHEMA_VERSION;
  doc["esp_id"] = g_espId;
  doc["boot_id"] = g_bootId;
  doc["request_id"] = requestId;
  doc["status"] = ok ? "OK" : "ERRO";
  if (!ok && errorCode != nullptr) {
    doc["error_code"] = errorCode;
  }
  doc["config_revision"] = g_configRevision;
  if (message != nullptr && message[0] != '\0') {
    char truncMsg[161];
    snprintf(truncMsg, sizeof(truncMsg), "%s", message);
    doc["message"] = truncMsg;
  }

  if (doc.overflowed()) {
    logMsg("ERROR", "JsonDocument overflow ao serializar config/result");
    return;
  }
  const size_t n = serializeJson(doc, g_txBuf, sizeof(g_txBuf));
  if (n == 0 || n >= sizeof(g_txBuf) || n > MAX_OTHER_BYTES) {
    logMsg("ERROR", "config/result excedeu %u bytes (%u)", (unsigned)MAX_OTHER_BYTES, (unsigned)n);
    return;
  }
  storeDedup(requestId, g_txBuf, n);
  enqueuePublish(g_topicConfigResult, g_txBuf, n, 1, 0);
}

static void handleDiscoveryRequest(const MqttRxMessage& msg) {
  if (msg.payloadTooLarge) {
    logMsg("WARN", "discovery/request excedeu %u bytes", (unsigned)MAX_OTHER_BYTES);
    return;
  }
  JsonDocument doc;
  const DeserializationError err = deserializeJson(doc, msg.payload, msg.payloadLen);
  if (err || !doc.is<JsonObjectConst>()) {
    logMsg("WARN", "discovery/request JSON invalido");
    return;
  }
  JsonObjectConst root = doc.as<JsonObjectConst>();
  static const char* const ALLOWED[] = {"schema_version", "request_id"};
  if (!hasOnlyAllowedKeys(root, ALLOWED, 2)) {
    logMsg("WARN", "discovery/request com chave desconhecida");
    return;
  }
  if (!root["schema_version"].is<long long>() || root["schema_version"].as<long long>() != SCHEMA_VERSION) {
    logMsg("WARN", "discovery/request schema_version invalido");
    return;
  }
  const char* reqId = root["request_id"].is<const char*>() ? root["request_id"].as<const char*>() : nullptr;
  if (!isUuid(reqId)) {
    logMsg("WARN", "discovery/request request_id invalido");
    return;
  }
  publishStatusOnline();
}

static void handleConfigGet(const MqttRxMessage& msg) {
  if (msg.payloadTooLarge) {
    logMsg("WARN", "config/get excedeu %u bytes", (unsigned)MAX_OTHER_BYTES);
    return;
  }
  JsonDocument doc;
  const DeserializationError err = deserializeJson(doc, msg.payload, msg.payloadLen);
  if (err || !doc.is<JsonObjectConst>()) {
    logMsg("WARN", "config/get JSON invalido");
    return;
  }
  JsonObjectConst root = doc.as<JsonObjectConst>();
  static const char* const ALLOWED[] = {"schema_version", "esp_id", "request_id"};
  if (!hasOnlyAllowedKeys(root, ALLOWED, 3)) {
    logMsg("WARN", "config/get com chave desconhecida");
    return;
  }
  if (!root["schema_version"].is<long long>() || root["schema_version"].as<long long>() != SCHEMA_VERSION) {
    logMsg("WARN", "config/get schema_version invalido");
    return;
  }
  const char* espId = root["esp_id"].is<const char*>() ? root["esp_id"].as<const char*>() : nullptr;
  if (espId == nullptr || strcmp(espId, g_espId) != 0) {
    logMsg("WARN", "config/get esp_id invalido");
    return;
  }
  const char* reqId = root["request_id"].is<const char*>() ? root["request_id"].as<const char*>() : nullptr;
  if (!isUuid(reqId)) {
    logMsg("WARN", "config/get request_id invalido");
    return;
  }
  if (!g_channelTableValid || g_restorePending) {
    logMsg("INFO", "config/get ignorado em RESTORE_PENDING/CONFIG_INVALID");
    return;
  }
  publishConfigState(reqId);
}

static bool isEspIdFormat(const char* s) {
  if (s == nullptr || strlen(s) != 10) return false;
  if (strncmp(s, "esp-", 4) != 0) return false;
  for (size_t i = 4; i < 10; ++i) {
    const char c = s[i];
    if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
  }
  return true;
}

static bool validateConfigSetMappings(JsonArrayConst arr,
                                      const char* reqId,
                                      ActiveMapping outNext[MAX_CHANNELS],
                                      size_t* outCount) {
  static const char* const ALLOWED_MAP[] = {"led_id", "kind", "channel_id"};
  size_t count = 0;
  for (JsonVariantConst item : arr) {
    if (!item.is<JsonObjectConst>()) {
      publishConfigResultAndCache(reqId, false, "INVALID_SCHEMA", "mapping nao e objeto");
      return false;
    }
    JsonObjectConst m = item.as<JsonObjectConst>();
    if (!hasOnlyAllowedKeys(m, ALLOWED_MAP, 3)) {
      publishConfigResultAndCache(reqId, false, "INVALID_SCHEMA", "chave desconhecida em mapping");
      return false;
    }
    const char* ledId = m["led_id"].is<const char*>() ? m["led_id"].as<const char*>() : nullptr;
    const char* kindStr = m["kind"].is<const char*>() ? m["kind"].as<const char*>() : nullptr;
    const char* chId = m["channel_id"].is<const char*>() ? m["channel_id"].as<const char*>() : nullptr;

    const size_t ledLen = ledId ? strlen(ledId) : 0;
    MappingKind kind = KIND_POSICAO;
    if (ledLen < 1 || ledLen > MAX_LED_ID_LEN || !parseKind(kindStr, &kind) || !isChannelIdFormatConst(chId)) {
      publishConfigResultAndCache(reqId, false, "INVALID_SCHEMA", "campo de mapping invalido");
      return false;
    }

    const int chIdx = findChannelIndexById(chId);
    if (chIdx < 0) {
      publishConfigResultAndCache(reqId, false, "UNKNOWN_CHANNEL", chId);
      return false;
    }
    for (size_t prev = 0; prev < count; ++prev) {
      if (strcmp(outNext[prev].ledId, ledId) == 0) {
        publishConfigResultAndCache(reqId, false, "LED_ID_CONFLICT", ledId);
        return false;
      }
      if (outNext[prev].channelIdx == (uint8_t)chIdx) {
        publishConfigResultAndCache(reqId, false, "CHANNEL_CONFLICT", chId);
        return false;
      }
    }

    outNext[count].inUse = true;
    snprintf(outNext[count].ledId, sizeof(outNext[count].ledId), "%s", ledId);
    outNext[count].kind = kind;
    outNext[count].channelIdx = (uint8_t)chIdx;
    count++;
  }
  *outCount = count;
  return true;
}

static void handleConfigSet(const MqttRxMessage& msg) {
  if (msg.payloadTooLarge) {
    logMsg("WARN", "config/set PAYLOAD_TOO_LARGE (> %u bytes)", (unsigned)MAX_CONFIG_BYTES);
    return;
  }

  JsonDocument doc;
  const DeserializationError err = deserializeJson(doc, msg.payload, msg.payloadLen);
  if (err || !doc.is<JsonObjectConst>()) {
    logMsg("WARN", "config/set JSON invalido: %s", err ? err.c_str() : "not object");
    return;
  }

  JsonObjectConst root = doc.as<JsonObjectConst>();
  const char* reqId = root["request_id"].is<const char*>() ? root["request_id"].as<const char*>() : nullptr;
  if (!isUuid(reqId)) {
    logMsg("WARN", "config/set request_id invalido ou ausente");
    return;
  }

  const DedupEntry* dup = findDedup(reqId);
  if (dup != nullptr) {
    enqueuePublish(g_topicConfigResult, dup->resultPayload, dup->resultLen, 1, 0);
    return;
  }

  static const char* const ALLOWED_ROOT[] = {
      "schema_version", "esp_id", "request_id", "expected_revision",
      "issued_at_ms", "ttl_ms", "mappings"};
  if (!hasOnlyAllowedKeys(root, ALLOWED_ROOT, 7)) {
    publishConfigResultAndCache(reqId, false, "INVALID_SCHEMA", "chave desconhecida na raiz");
    return;
  }

  if (!root["schema_version"].is<long long>() || root["schema_version"].as<long long>() != SCHEMA_VERSION) {
    publishConfigResultAndCache(reqId, false, "INVALID_SCHEMA", "schema_version invalido");
    return;
  }

  const char* espId = root["esp_id"].is<const char*>() ? root["esp_id"].as<const char*>() : nullptr;
  if (!isEspIdFormat(espId)) {
    publishConfigResultAndCache(reqId, false, "INVALID_SCHEMA", "esp_id invalido");
    return;
  }
  if (strcmp(espId, g_espId) != 0) {
    publishConfigResultAndCache(reqId, false, "ESP_ID_MISMATCH", "esp_id nao corresponde");
    return;
  }

  if (!g_channelTableValid) {
    publishConfigResultAndCache(reqId, false, "INTERNAL_ERROR", "tabela de canais invalida");
    return;
  }

  if (!root["expected_revision"].is<long long>() || root["expected_revision"].as<long long>() < 0) {
    publishConfigResultAndCache(reqId, false, "INVALID_SCHEMA", "expected_revision invalido");
    return;
  }
  const long long expectedRev = root["expected_revision"].as<long long>();

  if (!root["issued_at_ms"].isNull()) {
    if (!root["issued_at_ms"].is<long long>() || root["issued_at_ms"].as<long long>() < 0) {
      publishConfigResultAndCache(reqId, false, "INVALID_SCHEMA", "issued_at_ms invalido");
      return;
    }
  }
  if (!root["ttl_ms"].isNull()) {
    if (!root["ttl_ms"].is<long long>()) {
      publishConfigResultAndCache(reqId, false, "INVALID_SCHEMA", "ttl_ms invalido");
      return;
    }
    const long long ttl = root["ttl_ms"].as<long long>();
    if (ttl < 1 || ttl > 65000) {
      publishConfigResultAndCache(reqId, false, "INVALID_SCHEMA", "ttl_ms fora de 1..65000");
      return;
    }
  }

  if (!root["mappings"].is<JsonArrayConst>()) {
    publishConfigResultAndCache(reqId, false, "INVALID_SCHEMA", "mappings ausente ou invalido");
    return;
  }
  JsonArrayConst arr = root["mappings"].as<JsonArrayConst>();
  if (arr.size() > MAX_CHANNELS) {
    publishConfigResultAndCache(reqId, false, "INVALID_SCHEMA", "mappings > 14");
    return;
  }

  ActiveMapping next[MAX_CHANNELS];
  for (size_t i = 0; i < MAX_CHANNELS; ++i) {
    next[i].inUse = false;
    next[i].ledId[0] = '\0';
    next[i].kind = KIND_POSICAO;
    next[i].channelIdx = 0;
  }
  size_t count = 0;
  if (!validateConfigSetMappings(arr, reqId, next, &count)) {
    return;
  }

  if ((uint64_t)expectedRev != (uint64_t)g_configRevision) {
    publishConfigResultAndCache(reqId, false, "REVISION_MISMATCH", "revisao divergente");
    return;
  }

  for (size_t ch = 0; ch < ACTIVE_CHANNEL_COUNT; ++ch) {
    const char* oldLed = nullptr;
    for (size_t i = 0; i < MAX_CHANNELS; ++i) {
      if (g_mappings[i].inUse && g_mappings[i].channelIdx == ch) {
        oldLed = g_mappings[i].ledId;
        break;
      }
    }
    if (oldLed != nullptr) {
      bool keptSame = false;
      for (size_t i = 0; i < count; ++i) {
        if (next[i].channelIdx == ch && strcmp(next[i].ledId, oldLed) == 0) {
          keptSame = true;
          break;
        }
      }
      if (!keptSame) {
        g_testActive[ch] = false;
        g_testUntilMs[ch] = 0;
        writeChannelHardware(ch, false);
      }
    }
  }

  for (size_t i = 0; i < MAX_CHANNELS; ++i) {
    g_mappings[i] = next[i];
  }
  g_configRevision++;
  g_restorePending = false;
  snprintf(g_lastRequestId, sizeof(g_lastRequestId), "%s", reqId);

  publishConfigResultAndCache(reqId, true, nullptr, nullptr);
  publishConfigState(reqId);
  logMsg("INFO", "config/set aplicado rev=%lu mappings=%u", (unsigned long)g_configRevision, (unsigned)count);
}

// ============================================================================
// (10) Diagnostico, setup() e loop()
// ============================================================================

static FsmState g_fsmState = FSM_WAIT_WIFI;
static bool g_mqttStarted = false;
static bool g_mqttRetryArmed = false;
static uint32_t g_mqttBackoffMs = MQTT_BACKOFF_MIN_MS;
static uint32_t g_mqttNextRetryAtMs = 0;

static uint32_t g_disconnectedSinceMs = 0;
static bool g_offlineLedsCleared = false;
static uint32_t g_nextHeartbeatAtMs = 0;

// Scratch estatico para evitar alocar ~2 KB na stack da loopTask (8 KB).
static MqttRxMessage g_rxScratch;

static uint32_t computeJitteredBackoff(uint32_t baseMs) {
  const uint32_t halfSpan = baseMs / 4U;  // +/- 25%
  const uint32_t span = halfSpan * 2U + 1U;
  const uint32_t offset = esp_random() % span;
  return (baseMs - halfSpan) + offset;
}

static void scheduleMqttReconnect(uint32_t nowMs) {
  const uint32_t delayMs = computeJitteredBackoff(g_mqttBackoffMs);
  g_mqttNextRetryAtMs = nowMs + delayMs;
  g_mqttRetryArmed = true;
  if (g_mqttBackoffMs < MQTT_BACKOFF_MAX_MS) {
    const uint32_t doubled = g_mqttBackoffMs * 2U;
    g_mqttBackoffMs = (doubled > MQTT_BACKOFF_MAX_MS) ? MQTT_BACKOFF_MAX_MS : doubled;
  }
}

static void onMqttConnectedInLoop(uint32_t nowMs) {
  g_fsmState = FSM_ONLINE;
  g_mqttRetryArmed = false;
  g_mqttBackoffMs = MQTT_BACKOFF_MIN_MS;
  g_offlineLedsCleared = false;

  logMsg("INFO", "MQTT conectado (3.1.1) rssi=%d heap=%lu",
         isWifiConnected() ? WiFi.RSSI() : 0,
         (unsigned long)esp_get_free_heap_size());

  subscribeAllTopics();
  publishStatusOnline();

  if (g_channelTableValid && g_restorePending) {
    publishConfigRestore();
    g_restoreBackoffMs = RESTORE_BACKOFF_MIN_MS;
    g_nextRestoreAtMs = nowMs + g_restoreBackoffMs;
  } else if (g_channelTableValid) {
    publishConfigState(nullptr);
  }
  g_nextHeartbeatAtMs = nowMs + HEARTBEAT_INTERVAL_MS;
}

static void onMqttDisconnectedInLoop(uint32_t nowMs) {
  if (g_fsmState == FSM_ONLINE) {
    g_disconnectedSinceMs = nowMs;
    g_offlineLedsCleared = false;
    logMsg("WARN", "MQTT desconectado");
  }
  if (g_fsmState != FSM_HALT_PLACEHOLDERS) {
    g_fsmState = isWifiConnected() ? FSM_MQTT_CONNECTING : FSM_WAIT_WIFI;
    if (g_mqttStarted && isWifiConnected() && !g_mqttRetryArmed) {
      scheduleMqttReconnect(nowMs);
    }
  }
}

static void processRxMessage(const MqttRxMessage& msg) {
  switch (msg.topicKind) {
    case TK_COMANDO:
      handleLegacyCommand(msg);
      break;
    case TK_DISCOVERY_REQ:
      handleDiscoveryRequest(msg);
      break;
    case TK_CONFIG_GET:
      handleConfigGet(msg);
      break;
    case TK_CONFIG_SET:
      handleConfigSet(msg);
      break;
    default:
      break;
  }
}

void setup() {
  initChannelsOffFirst();
  enableLoopWDT();

  Serial.begin(115200);
  initIdentityAndTopics();
  initConfigRam();
  g_disconnectedSinceMs = millis();

  logMsg("INFO", "Boot fw=%s boot_id=%s channels_valid=%d has_secrets=%d",
         FIRMWARE_VERSION, g_bootId, g_channelTableValid ? 1 : 0, HAS_LOCAL_SECRETS);

  if (!g_channelTableValid) {
    logMsg("ERROR", "Tabela de canais invalida: GPIOs bloqueados (CONFIG_INVALID)");
  }

  if (credentialsArePlaceholders()) {
    logMsg("ERROR", "Credenciais placeholder detectadas (WIFI_SSID/MQTT_HOST com '<'). "
                    "Copie esp32/secrets.example.h para esp32/secrets.h e preencha valores reais.");
    g_fsmState = FSM_HALT_PLACEHOLDERS;
    return;
  }

  initWifi();
  if (!initMqttClient()) {
    logMsg("ERROR", "Falha ao inicializar cliente ESP-MQTT");
    g_fsmState = FSM_HALT_PLACEHOLDERS;
    return;
  }
  g_fsmState = FSM_WAIT_WIFI;
}

void loop() {
  const uint32_t nowMs = millis();

  tickTestLedTimers(nowMs);

  const uint32_t dropped = g_rxDroppedCount.exchange(0, std::memory_order_relaxed);
  if (dropped > 0) {
    logMsg("WARN", "rxQueue cheia: %lu mensagem(ns) descartada(s)", (unsigned long)dropped);
  }

  if (g_ctrlQueue != nullptr) {
    CtrlEventKind ctrl;
    while (xQueueReceive(g_ctrlQueue, &ctrl, 0) == pdTRUE) {
      if (ctrl == CTRL_MQTT_CONNECTED) {
        onMqttConnectedInLoop(nowMs);
      } else if (ctrl == CTRL_MQTT_DISCONNECTED) {
        onMqttDisconnectedInLoop(nowMs);
      } else if (ctrl == CTRL_MQTT_ERROR) {
        logMsg("WARN", "Evento de erro no cliente ESP-MQTT");
      }
    }
  }

  if (g_rxQueue != nullptr) {
    while (xQueueReceive(g_rxQueue, &g_rxScratch, 0) == pdTRUE) {
      processRxMessage(g_rxScratch);
    }
  }

  if (g_fsmState == FSM_HALT_PLACEHOLDERS) {
    delay(5);
    return;
  }

  if (!isWifiConnected()) {
    if (g_fsmState == FSM_ONLINE) {
      onMqttDisconnectedInLoop(nowMs);
    } else if (g_fsmState != FSM_WAIT_WIFI) {
      g_fsmState = FSM_WAIT_WIFI;
    }
  } else {
    if (g_fsmState == FSM_WAIT_WIFI) {
      g_fsmState = FSM_MQTT_CONNECTING;
      if (!g_mqttStarted) {
        if (esp_mqtt_client_start(g_mqttClient) == ESP_OK) {
          g_mqttStarted = true;
        } else {
          scheduleMqttReconnect(nowMs);
        }
      } else if (!g_mqttRetryArmed) {
        g_mqttNextRetryAtMs = nowMs;
        g_mqttRetryArmed = true;
      }
    } else if (g_fsmState == FSM_MQTT_CONNECTING && g_mqttStarted && g_mqttRetryArmed) {
      if ((int32_t)(nowMs - g_mqttNextRetryAtMs) >= 0) {
        g_mqttRetryArmed = false;
        const esp_err_t rc = esp_mqtt_client_reconnect(g_mqttClient);
        if (rc != ESP_OK) {
          logMsg("WARN", "esp_mqtt_client_reconnect falhou (%d)", (int)rc);
          scheduleMqttReconnect(nowMs);
        }
      }
    }
  }

  if (g_fsmState == FSM_ONLINE) {
    if (g_channelTableValid && g_restorePending && (int32_t)(nowMs - g_nextRestoreAtMs) >= 0) {
      publishConfigRestore();
      if (g_restoreBackoffMs < RESTORE_BACKOFF_MAX_MS) {
        const uint32_t doubled = g_restoreBackoffMs * 2U;
        g_restoreBackoffMs = (doubled > RESTORE_BACKOFF_MAX_MS) ? RESTORE_BACKOFF_MAX_MS : doubled;
      }
      g_nextRestoreAtMs = nowMs + g_restoreBackoffMs;
    }
    if ((int32_t)(nowMs - g_nextHeartbeatAtMs) >= 0) {
      publishStatusOnline();
      g_nextHeartbeatAtMs = nowMs + HEARTBEAT_INTERVAL_MS;
    }
  } else {
    if (!g_offlineLedsCleared && (int32_t)(nowMs - g_disconnectedSinceMs) >= (int32_t)OFFLINE_LED_SAFETY_MS) {
      turnOffAllLeds();
      g_offlineLedsCleared = true;
      logMsg("WARN", "Offline > 65s: todos os LEDs apagados");
    }
  }

  delay(2);
}
