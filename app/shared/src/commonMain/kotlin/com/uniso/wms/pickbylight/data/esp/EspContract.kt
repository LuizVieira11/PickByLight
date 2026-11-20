package com.uniso.wms.pickbylight.data.esp

import com.uniso.wms.pickbylight.data.mqtt.model.ConfigGet
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigResult
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigResultStatus
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigSet
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigState
import com.uniso.wms.pickbylight.data.mqtt.model.DiscoveryRequest
import com.uniso.wms.pickbylight.data.mqtt.model.EspChannel
import com.uniso.wms.pickbylight.data.mqtt.model.EspErrorCode
import com.uniso.wms.pickbylight.data.mqtt.model.EspMapping
import com.uniso.wms.pickbylight.data.mqtt.model.EspOnlineStatus
import com.uniso.wms.pickbylight.data.mqtt.model.EspStatusOffline
import com.uniso.wms.pickbylight.data.mqtt.model.EspStatusOnline
import com.uniso.wms.pickbylight.data.mqtt.model.MappingKind
import com.uniso.wms.pickbylight.domain.model.Estante
import com.uniso.wms.pickbylight.domain.model.Posicao
import com.uniso.wms.pickbylight.domain.model.Prateleira
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Resultado de validação de payload v1. [Invalid.code] segue a seção 7 de MQTT5_E_ESP32.md. */
sealed interface ContractResult<out T> {
    data class Valid<T>(val value: T) : ContractResult<T>
    data class Invalid(val code: EspErrorCode, val detail: String) : ContractResult<Nothing>
}

enum class MappingViolationType {
    TOO_MANY_MAPPINGS,
    UNKNOWN_CHANNEL,
    LED_ID_DUPLICATED,
    CHANNEL_DUPLICATED,
    UNKNOWN_LED_ID,
    KIND_MISMATCH,
    LED_ID_OWNED_BY_OTHER_ESP
}

data class MappingViolation(
    val type: MappingViolationType,
    val ledId: String? = null,
    val channelId: String? = null,
    val otherEspId: String? = null
)

/** IDs lógicos existentes no domínio (Estante.ledId, Prateleira.ledIdPonta, Posicao.ledId). */
class DomainLedIds(
    val estantes: Set<String>,
    val prateleiras: Set<String>,
    val posicoes: Set<String>
) {
    companion object {
        fun from(estantes: List<Estante>, prateleiras: List<Prateleira>, posicoes: List<Posicao>) = DomainLedIds(
            estantes = estantes.map { it.ledId }.toSet(),
            prateleiras = prateleiras.map { it.ledIdPonta }.toSet(),
            posicoes = posicoes.map { it.ledId }.toSet()
        )
    }

    fun idsFor(kind: MappingKind): Set<String> = when (kind) {
        MappingKind.ESTANTE -> estantes
        MappingKind.PRATELEIRA -> prateleiras
        MappingKind.POSICAO -> posicoes
    }
}

/**
 * Validadores puros do contrato v1 (sem dependência de MQTT). Fonte: especificacao/MQTT5_E_ESP32.md seções 3.2, 3.4, 6 e 7.
 * Entrada que espelha o ESP (config/set, config/get, discovery): estrita, chave desconhecida vira INVALID_SCHEMA.
 * Recebimento ESP -> app (status, state, result): tolerante a chaves novas, exceto as proibidas pelo schema
 * (status sem request_id; OFFLINE sem capabilities). Campo obrigatório ausente ou enum inválido continua INVALID_SCHEMA.
 * O ACK legado NÃO usa este parser: segue tolerante (ignoreUnknownKeys) no serviço MQTT.
 */
@OptIn(ExperimentalSerializationApi::class)
object EspContract {
    const val SCHEMA_VERSION = 1
    const val MAX_CONFIG_BYTES = 2048
    const val MAX_OTHER_BYTES = 512
    const val MAX_MAPPINGS = 14
    const val MAX_CHANNELS = 14

    val ESP_ID_REGEX = Regex("^esp-[0-9a-f]{6}$")
    val BOOT_ID_REGEX = Regex("^[0-9a-f]{8,32}$")
    val CHANNEL_ID_REGEX = Regex("^CH[0-9]{2}$")
    val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /**
     * Canais permitidos (seção 3.2): recomendados + cautela (5, 14, 15). GPIO2 só como STATUS_LED_GPIO, nunca canal.
     * 0, 1, 3, 6-12 e 34-39 são proibidos. O firmware só ativa os recomendados por padrão.
     */
    val ALLOWED_CHANNEL_GPIOS: Set<Int> = setOf(4, 13, 16, 17, 18, 19, 21, 22, 23, 25, 26, 27, 32, 33, 5, 14, 15)

    private val CONFIG_INVALID_CODES = setOf(EspErrorCode.INVALID_CHANNEL_TABLE, EspErrorCode.STORAGE_FAILURE)

    /** Validadores que espelham o ESP (set/get/discovery): schema com additionalProperties=false. */
    private val strictJson = Json { ignoreUnknownKeys = false }

    /** Recebimento ESP -> app (status/state/result): tolera campos novos de firmware mais recente (seção 16, item 14). */
    private val lenientJson = Json { ignoreUnknownKeys = true }

    /** Saída v1 sem `null` explícito: o schema não aceita `"ttl_ms":null`. Não afeta os payloads legados. */
    private val outJson = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    fun utf8Size(payload: String): Int = payload.encodeToByteArray().size

    // ---------- Saída (app -> ESP) ----------

    // O app nunca gera um config/set que o ESP rejeitaria (maxItems 14). A checagem de bytes em encodeLimited é
    // defensiva: com as regras do schema (14 mappings, led_id de até 64 caracteres) um set válido não chega a 2048 bytes.
    fun encodeConfigSet(set: ConfigSet): ContractResult<String> =
        if (set.mappings.size > MAX_MAPPINGS) schema("mappings > $MAX_MAPPINGS") else
        encodeLimited(outJson.encodeToString(ConfigSet.serializer(), set), MAX_CONFIG_BYTES)

    fun encodeConfigGet(get: ConfigGet): ContractResult<String> =
        encodeLimited(outJson.encodeToString(ConfigGet.serializer(), get), MAX_OTHER_BYTES)

    fun encodeDiscoveryRequest(request: DiscoveryRequest): ContractResult<String> =
        encodeLimited(outJson.encodeToString(DiscoveryRequest.serializer(), request), MAX_OTHER_BYTES)

    fun encodeStatusOffline(status: EspStatusOffline): String =
        outJson.encodeToString(EspStatusOffline.serializer(), status)

    private fun encodeLimited(payload: String, maxBytes: Int): ContractResult<String> =
        if (utf8Size(payload) > maxBytes) {
            ContractResult.Invalid(EspErrorCode.PAYLOAD_TOO_LARGE, "${utf8Size(payload)} bytes > $maxBytes")
        } else {
            ContractResult.Valid(payload)
        }

    // ---------- Entrada / validação ----------

    fun parseDiscoveryRequest(payload: String): ContractResult<DiscoveryRequest> =
        decode<DiscoveryRequest>(payload, MAX_OTHER_BYTES) {
            header(it.schemaVersion, null, null, requestId = it.requestId)
        }

    fun parseConfigGet(payload: String, topicEspId: String): ContractResult<ConfigGet> =
        decode<ConfigGet>(payload, MAX_OTHER_BYTES) {
            header(it.schemaVersion, it.espId, topicEspId, requestId = it.requestId)
        }

    // Recebimento ESP -> app: tolerante a campos novos; só as chaves que o schema proíbe de propósito são rejeitadas.
    fun parseStatusOffline(payload: String, topicEspId: String): ContractResult<EspStatusOffline> =
        decode<EspStatusOffline>(
            payload, MAX_OTHER_BYTES, tolerant = true, forbiddenKeys = setOf("capabilities", "request_id")
        ) {
            header(it.schemaVersion, it.espId, topicEspId, bootId = it.bootId)
        }

    fun parseStatusOnline(payload: String, topicEspId: String): ContractResult<EspStatusOnline> =
        decode<EspStatusOnline>(
            payload, MAX_OTHER_BYTES, tolerant = true, forbiddenKeys = setOf("request_id")
        ) { s -> statusOnlineError(s, topicEspId) }

    fun parseConfigState(payload: String, topicEspId: String): ContractResult<ConfigState> =
        decode<ConfigState>(payload, MAX_CONFIG_BYTES, tolerant = true) { s ->
            header(s.schemaVersion, s.espId, topicEspId, bootId = s.bootId)
                ?: if (s.configRevision < 0) schema("config_revision < 0")
                else if (s.uptimeMs < 0) schema("uptime_ms < 0")
                else if (s.lastRequestId != null && !UUID_REGEX.matches(s.lastRequestId)) schema("last_request_id inválido")
                else mappingsError(s.mappings, channels = null)
        }

    fun parseConfigResult(payload: String, topicEspId: String): ContractResult<ConfigResult> =
        decode<ConfigResult>(payload, MAX_OTHER_BYTES, tolerant = true) { r ->
            header(r.schemaVersion, r.espId, topicEspId, bootId = r.bootId, requestId = r.requestId)
                ?: if (r.configRevision < 0) schema("config_revision < 0")
                else if ((r.message?.length ?: 0) > 160) schema("message > 160")
                else if (r.errorCode == EspErrorCode.INVALID_CHANNEL_TABLE) schema("INVALID_CHANNEL_TABLE só vale em status")
                else if (r.status == ConfigResultStatus.OK && r.errorCode != null) schema("OK com error_code")
                else if (r.status == ConfigResultStatus.ERRO && r.errorCode == null) schema("ERRO sem error_code")
                else null
        }

    /**
     * Valida um `config/set` como o ESP faz (seção 10.5): tamanho, schema, esp_id, canais, unicidade e revisão.
     * [activeRevision] e [channels] opcionais: sem eles valida só schema/estrutura.
     */
    fun parseConfigSet(
        payload: String,
        topicEspId: String,
        activeRevision: Long? = null,
        channels: List<EspChannel>? = null
    ): ContractResult<ConfigSet> =
        decode<ConfigSet>(payload, MAX_CONFIG_BYTES) { s ->
            header(s.schemaVersion, s.espId, topicEspId, requestId = s.requestId)
                ?: if (s.expectedRevision < 0) schema("expected_revision < 0")
                else if ((s.issuedAtMs ?: 0L) < 0L) schema("issued_at_ms < 0")
                else if (s.ttlMs != null && s.ttlMs !in 1L..65000L) schema("ttl_ms fora de 1..65000")
                else mappingsError(s.mappings, channels?.map { it.channelId }?.toSet())
                    ?: if (activeRevision != null && s.expectedRevision != activeRevision) {
                        ContractResult.Invalid(
                            EspErrorCode.REVISION_MISMATCH,
                            "expected_revision=${s.expectedRevision}, ativa=$activeRevision"
                        )
                    } else null
        }

    // ---------- Mapeamentos ----------

    /**
     * Regras estruturais (seção 3.4): canal existente, `led_id` único, canal único.
     * [channelIds] null = não verificar existência do canal.
     */
    fun structuralViolations(mappings: List<EspMapping>, channelIds: Set<String>?): List<MappingViolation> {
        val out = mutableListOf<MappingViolation>()
        if (mappings.size > MAX_MAPPINGS) out += MappingViolation(MappingViolationType.TOO_MANY_MAPPINGS)
        if (channelIds != null) {
            mappings.filter { it.channelId !in channelIds }.forEach {
                out += MappingViolation(MappingViolationType.UNKNOWN_CHANNEL, it.ledId, it.channelId)
            }
        }
        mappings.groupBy { it.ledId }.filterValues { it.size > 1 }.keys.forEach {
            out += MappingViolation(MappingViolationType.LED_ID_DUPLICATED, ledId = it)
        }
        mappings.groupBy { it.channelId }.filterValues { it.size > 1 }.keys.forEach {
            out += MappingViolation(MappingViolationType.CHANNEL_DUPLICATED, channelId = it)
        }
        return out
    }

    /**
     * Validação do app antes de publicar `config/set` (seção 3.4, regras 1-6): estrutura + domínio + conflito entre ESPs.
     * [knownEsps]: mapeamentos atuais de cada ESP conhecido; o próprio [espId] é ignorado.
     * Não prova unicidade frente a ESPs offline ou desconhecidos.
     */
    fun validateMappingsForPublish(
        espId: String,
        mappings: List<EspMapping>,
        channels: List<EspChannel>,
        domain: DomainLedIds,
        knownEsps: Map<String, List<EspMapping>> = emptyMap()
    ): List<MappingViolation> {
        val out = structuralViolations(mappings, channels.map { it.channelId }.toSet()).toMutableList()
        for (m in mappings) {
            if (m.ledId !in domain.idsFor(m.kind)) {
                val otherKind = MappingKind.entries.any { it != m.kind && m.ledId in domain.idsFor(it) }
                out += MappingViolation(
                    if (otherKind) MappingViolationType.KIND_MISMATCH else MappingViolationType.UNKNOWN_LED_ID,
                    ledId = m.ledId
                )
            }
            val owner = knownEsps.entries.firstOrNull { (id, list) -> id != espId && list.any { it.ledId == m.ledId } }
            if (owner != null) {
                out += MappingViolation(MappingViolationType.LED_ID_OWNED_BY_OTHER_ESP, m.ledId, otherEspId = owner.key)
            }
        }
        return out
    }

    // ---------- Internos ----------

    private fun statusOnlineError(s: EspStatusOnline, topicEspId: String): ContractResult.Invalid? {
        header(s.schemaVersion, s.espId, topicEspId, bootId = s.bootId)?.let { return it }
        val channels = s.capabilities.channels
        val problem = when {
            s.configRevision < 0 -> "config_revision < 0"
            s.capabilities.firmwareVersion.length !in 1..32 -> "firmware_version fora de 1..32"
            channels.size > MAX_CHANNELS -> "channels > $MAX_CHANNELS"
            (s.uptimeMs ?: 0L) < 0L -> "uptime_ms < 0"
            s.rssi != null && s.rssi !in -127..0 -> "rssi fora de -127..0"
            (s.freeHeap ?: 0L) < 0L -> "free_heap < 0"
            s.errorCode != null && s.errorCode !in CONFIG_INVALID_CODES -> "error_code inválido em status"
            s.status == EspOnlineStatus.CONFIG_INVALID && s.errorCode == null -> "CONFIG_INVALID sem error_code"
            s.status == EspOnlineStatus.ONLINE && s.errorCode != null -> "ONLINE com error_code"
            // Com CONFIG_INVALID a tabela de canais pode vir vazia/inconsistente: não é validada.
            s.status == EspOnlineStatus.CONFIG_INVALID -> null
            else -> channelsProblem(channels)
        }
        return problem?.let { schema(it) }
    }

    private fun channelsProblem(channels: List<EspChannel>): String? {
        for (c in channels) {
            if (!CHANNEL_ID_REGEX.matches(c.channelId)) return "channel_id inválido: ${c.channelId}"
            if (c.gpio !in ALLOWED_CHANNEL_GPIOS) return "gpio ${c.gpio} não permitido como canal"
        }
        if (channels.map { it.channelId }.toSet().size != channels.size) return "channel_id repetido"
        if (channels.map { it.gpio }.toSet().size != channels.size) return "gpio repetido"
        return null
    }

    private fun mappingsError(mappings: List<EspMapping>, channels: Set<String>?): ContractResult.Invalid? {
        if (mappings.size > MAX_MAPPINGS) return schema("mappings > $MAX_MAPPINGS")
        for (m in mappings) {
            if (m.ledId.length !in 1..64) return schema("led_id fora de 1..64")
            if (!CHANNEL_ID_REGEX.matches(m.channelId)) return schema("channel_id inválido: ${m.channelId}")
        }
        val first = structuralViolations(mappings, channels).firstOrNull() ?: return null
        val code = when (first.type) {
            MappingViolationType.UNKNOWN_CHANNEL -> EspErrorCode.UNKNOWN_CHANNEL
            MappingViolationType.LED_ID_DUPLICATED -> EspErrorCode.LED_ID_CONFLICT
            MappingViolationType.CHANNEL_DUPLICATED -> EspErrorCode.CHANNEL_CONFLICT
            else -> EspErrorCode.INVALID_SCHEMA
        }
        return ContractResult.Invalid(code, "${first.type} led=${first.ledId} canal=${first.channelId}")
    }

    private fun schema(detail: String) = ContractResult.Invalid(EspErrorCode.INVALID_SCHEMA, detail)

    private fun header(
        schemaVersion: Int,
        espId: String?,
        topicEspId: String?,
        bootId: String? = null,
        requestId: String? = null
    ): ContractResult.Invalid? {
        if (schemaVersion != SCHEMA_VERSION) return schema("schema_version=$schemaVersion")
        if (espId != null && !ESP_ID_REGEX.matches(espId)) return schema("esp_id inválido: $espId")
        if (espId != null && topicEspId != null && espId != topicEspId) {
            return ContractResult.Invalid(EspErrorCode.ESP_ID_MISMATCH, "payload=$espId tópico=$topicEspId")
        }
        if (bootId != null && !BOOT_ID_REGEX.matches(bootId)) return schema("boot_id inválido")
        if (requestId != null && !UUID_REGEX.matches(requestId)) return schema("request_id inválido")
        return null
    }

    /**
     * [tolerant] = ignora chaves desconhecidas (recebimento ESP -> app; firmware mais novo pode acrescentar campos).
     * [forbiddenKeys] = chaves que o schema proíbe explicitamente mesmo no modo tolerante (exemplos inválidos da seção 6).
     */
    private inline fun <reified T> decode(
        payload: String,
        maxBytes: Int,
        tolerant: Boolean = false,
        forbiddenKeys: Set<String> = emptySet(),
        validate: (T) -> ContractResult.Invalid?
    ): ContractResult<T> {
        val size = utf8Size(payload)
        if (size > maxBytes) return ContractResult.Invalid(EspErrorCode.PAYLOAD_TOO_LARGE, "$size bytes > $maxBytes")
        val value = try {
            if (forbiddenKeys.isNotEmpty()) {
                val keys = (lenientJson.parseToJsonElement(payload) as? JsonObject)?.keys.orEmpty()
                keys.firstOrNull { it in forbiddenKeys }?.let { return schema("campo proibido: $it") }
            }
            (if (tolerant) lenientJson else strictJson).decodeFromString<T>(payload)
        } catch (e: IllegalArgumentException) { // SerializationException herda de IllegalArgumentException
            return schema(e.message ?: "JSON inválido")
        }
        return validate(value) ?: ContractResult.Valid(value)
    }
}
