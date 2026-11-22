"""
Simulador de ESP32 para testar o app Pick-by-Light sem hardware (contrato v1, MQTT5_E_ESP32.md).

Uso (broker local no Docker):
    python tools/esp_sim.py
    python tools/esp_sim.py --channels 4 --esp esp-a1b2c3 --host localhost
    python tools/esp_sim.py --protocol 3        # força MQTT 3.1.1
    python tools/esp_sim.py --ack-all           # responde ACK a ACENDER/APAGAR de qualquer posição

Depois de rodar, digite um comando + Enter (h = ajuda). Ctrl+C sai.
Requer: pip install --user paho-mqtt
"""
import argparse
import json
import os
import secrets
import sys
import threading
import time
import uuid
from datetime import datetime

import paho.mqtt.client as mqtt
from paho.mqtt.packettypes import PacketTypes
from paho.mqtt.properties import Properties

GPIOS = [4, 13, 16, 17, 18, 19, 21, 22, 23, 25, 26, 27, 32, 33]  # tabela 3.2 da spec
LIMIT_OTHER = 512   # discovery, config/get, config/result, OFFLINE (seção 6)
LIMIT_CONFIG = 2048  # config/set, config/state e status ONLINE/CONFIG_INVALID (etapa 5c)
DEDUP_SIZE = 8       # seção 10.5: últimos 8 request_id

HELP = """
Comandos (digite + Enter):
  s  liga/desliga MODO SILENCIOSO (não responde nada -> testa Timeout)
  m  próximo config/set responde REVISION_MISMATCH (uma vez)
  l  próximo config/set é aplicado mas o config/result se PERDE (uma vez; o retry testa a deduplicação)
  e  liga/desliga ACK com ERRO nos comandos de LED
  o  publica OFFLINE (reason LWT, retido)
  r  republica status ONLINE + config/state (volta do OFFLINE)
  c  liga/desliga CONFIG_INVALID (STORAGE_FAILURE) no status
  n  simula reboot: boot_id novo, revisão 0, sem mappings e volta a pedir restore (spec 10.5)
  p  mostra o estado atual (revisão, mappings, LEDs, restore pendente)
  x  apaga os retidos deste ESP no broker e sai
  q  desligamento controlado (OFFLINE reason SHUTDOWN) e sai
  h  esta ajuda
"""


def now():
    return datetime.now().strftime("%H:%M:%S.%f")[:-3]


def compact(obj):
    return json.dumps(obj, separators=(",", ":"), ensure_ascii=False)


def short(rid):
    return (rid or "-")[:8]


class EspSim:
    def __init__(self, args):
        self.a = args
        self.esp = args.esp
        self.boot = args.boot or secrets.token_hex(4)
        self.root = f"wms/pickbylight/v1/esp/{self.esp}"
        self.v5 = args.protocol == 5
        self.channels = [
            {"channel_id": f"CH{i + 1:02d}", "gpio": GPIOS[i], "active_high": True}
            for i in range(args.channels)
        ]
        self.rev = 0
        self.mappings = []
        self.leds = {}            # led_id -> "ACESO"
        self.dedup = {}           # request_id -> result dict (ordem de inserção)
        self.started = time.time()
        self.silent = False
        self.led_error = False
        self.config_invalid = False
        self.next_mismatch = False
        self.next_lose_result = False
        self.pending = not args.no_restore  # RESTORE_PENDING (spec 10.5): sem config até o app restaurar
        self.restore_interval = 10.0
        self.next_restore_at = 0.0
        self.connected = False
        self.lock = threading.Lock()

        kwargs = dict(callback_api_version=mqtt.CallbackAPIVersion.VERSION2, client_id=self.esp,
                      protocol=mqtt.MQTTv5 if self.v5 else mqtt.MQTTv311)
        if not self.v5:
            kwargs["clean_session"] = True
        self.c = mqtt.Client(**kwargs)
        self.c.on_connect = self.on_connect
        self.c.on_disconnect = self.on_disconnect
        self.c.on_message = self.on_message
        will_props = None
        if self.v5:
            will_props = Properties(PacketTypes.WILLMESSAGE)
            will_props.WillDelayInterval = 5
        self.c.will_set(f"{self.root}/status", compact(self.offline_payload("LWT")), qos=1, retain=True,
                        properties=will_props)

    # ---------- payloads ----------
    def uptime(self):
        return int((time.time() - self.started) * 1000)

    def status_payload(self):
        p = {"schema_version": 1, "esp_id": self.esp, "boot_id": self.boot,
             "status": "CONFIG_INVALID" if self.config_invalid else "ONLINE",
             "config_revision": self.rev,
             "capabilities": {"firmware_version": "sim-1.0.0",
                              "mqtt_protocol": "5.0" if self.v5 else "3.1.1",
                              "channels": self.channels}}
        if self.config_invalid:
            p["error_code"] = "STORAGE_FAILURE"
        return p

    def offline_payload(self, reason):
        return {"schema_version": 1, "esp_id": self.esp, "boot_id": self.boot,
                "status": "OFFLINE", "reason": reason}

    def state_payload(self, last_request_id=None):
        p = {"schema_version": 1, "esp_id": self.esp, "boot_id": self.boot,
             "config_revision": self.rev, "mappings": self.mappings, "uptime_ms": self.uptime()}
        if last_request_id:
            p["last_request_id"] = last_request_id
        return p

    def result_payload(self, request_id, ok, error_code=None):
        p = {"schema_version": 1, "esp_id": self.esp, "boot_id": self.boot, "request_id": request_id,
             "status": "OK" if ok else "ERRO", "config_revision": self.rev}
        if not ok:
            p["error_code"] = error_code
        return p

    # ---------- envio ----------
    def pub(self, topic, obj, retain=False, correlation=None, label="", limit=None):
        if limit is None:
            big = topic.endswith("/status") and obj.get("status") != "OFFLINE"
            limit = LIMIT_CONFIG if big else LIMIT_OTHER
        data = compact(obj)
        size = len(data.encode("utf-8"))
        props = None
        if self.v5:
            props = Properties(PacketTypes.PUBLISH)
            props.PayloadFormatIndicator = 1
            props.ContentType = "application/json"
            if correlation is not None:
                props.CorrelationData = correlation
        self.c.publish(topic, data, qos=1, retain=retain, properties=props)
        tag = " [retido]" if retain else ""
        print(f"{now()}  >> {label or topic.split('/')[-1]}{tag} ({size} B)")
        if self.a.verbose:
            print(f"            {data}")
        if size > limit:
            print(f"            !! {size} B passa do limite de {limit} B: o app vai DESCARTAR esta mensagem")

    def announce(self, last_request_id=None):
        self.pub(f"{self.root}/status", self.status_payload(), retain=True, label="status")
        if self.pending:  # spec 10.5 item 3: estado vazio sobrescreveria no app a config a restaurar
            print("            (RESTORE_PENDING: config/state só depois da restauração)")
            return
        self.pub(f"{self.root}/config/state", self.state_payload(last_request_id), retain=True,
                 label="config/state", limit=LIMIT_CONFIG)

    def restore_loop(self):
        while True:
            time.sleep(0.5)
            with self.lock:
                if not (self.pending and self.connected) or time.time() < self.next_restore_at:
                    continue
                rid = str(uuid.uuid4())
                self.pub(f"{self.root}/config/restore",
                         {"schema_version": 1, "esp_id": self.esp, "boot_id": self.boot, "request_id": rid},
                         label=f"config/restore request_id={short(rid)} (próximo em {int(self.restore_interval)} s)")
                self.next_restore_at = time.time() + self.restore_interval
                self.restore_interval = min(self.restore_interval * 2, 60.0)

    # ---------- conexão ----------
    def start(self):
        props = None
        if self.v5:
            props = Properties(PacketTypes.CONNECT)
            props.SessionExpiryInterval = 0
            self.c.connect(self.a.host, self.a.port, keepalive=15, clean_start=True, properties=props)
        else:
            self.c.connect(self.a.host, self.a.port, keepalive=15)
        self.c.loop_start()
        threading.Thread(target=self.restore_loop, daemon=True).start()

    def on_connect(self, client, userdata, flags, reason_code, properties):
        if reason_code.is_failure:
            print(f"{now()}  !! conexão recusada: {reason_code}")
            return
        proto = "5.0" if self.v5 else "3.1.1"
        print(f"{now()}  == conectado em {self.a.host}:{self.a.port} (MQTT {proto}) como {self.esp}, boot_id {self.boot}")
        for t in ("wms/pickbylight/v1/discovery/request", f"{self.root}/config/get",
                  f"{self.root}/config/set", "wms/pickbylight/comando"):
            client.subscribe(t, qos=1)
        with self.lock:
            self.connected = True
            self.next_restore_at = 0.0  # pede o restore logo após (re)conectar
            self.announce()
        print("            digite h + Enter para ver os comandos")

    def on_disconnect(self, client, userdata, flags, reason_code, properties):
        self.connected = False
        print(f"{now()}  == desconectado ({reason_code}); o paho tenta reconectar sozinho")

    # ---------- recebimento ----------
    def on_message(self, client, userdata, msg):
        topic = msg.topic
        raw = msg.payload.decode("utf-8", errors="replace")
        try:
            m = json.loads(raw)
        except json.JSONDecodeError:
            print(f"{now()}  << {topic}: JSON inválido, descartado (sem resposta)")
            return
        with self.lock:
            try:
                self.route(topic, m, raw, msg)
            except Exception as ex:  # o simulador nunca cai por causa de uma mensagem
                print(f"{now()}  !! erro tratando {topic}: {ex}")

    def route(self, topic, m, raw, msg):
        if topic.endswith("discovery/request"):
            print(f"{now()}  << discovery/request  request_id={short(m.get('request_id'))}")
            if self.skip():
                return
            self.pub(f"{self.root}/status", self.status_payload(), retain=True, label="status (resposta ao discovery)")
        elif topic.endswith("/config/get"):
            print(f"{now()}  << config/get  request_id={short(m.get('request_id'))}")
            if self.pending:
                print("            (RESTORE_PENDING: config/get ignorado, spec 10.5 item 3)")
                return
            if self.skip():
                return
            self.pub(f"{self.root}/config/state", self.state_payload(m.get("request_id")), retain=True,
                     label="config/state (resposta ao get)", limit=LIMIT_CONFIG)
        elif topic.endswith("/config/set"):
            self.handle_set(m, raw, msg)
        elif topic == "wms/pickbylight/comando":
            self.handle_command(m)

    def skip(self):
        if self.silent:
            print("            (modo silencioso: sem resposta)")
        return self.silent

    def handle_set(self, m, raw, msg):
        rid = m.get("request_id")
        maps = m.get("mappings") or []
        print(f"{now()}  << config/set  request_id={short(rid)}  expected_revision={m.get('expected_revision')}  mappings={len(maps)}")
        for mp in maps:
            print(f"            {mp.get('channel_id')} <- {mp.get('kind')} {mp.get('led_id')}")
        if self.skip():
            return
        corr = getattr(msg.properties, "CorrelationData", None) if self.v5 and msg.properties else None
        result_topic = f"{self.root}/config/result"

        if rid in self.dedup:  # seção 10.5 passo 3: repetido -> republica o mesmo resultado
            print("            DUPLICATA: mesmo request_id, republicando o resultado anterior sem reaplicar")
            self.pub(result_topic, self.dedup[rid], correlation=corr, label="config/result (duplicata)")
            return

        if len(raw.encode("utf-8")) > LIMIT_CONFIG:
            self.reply_error(result_topic, rid, "PAYLOAD_TOO_LARGE", corr)
            return
        error = self.validate_set(m)
        if self.next_mismatch:
            self.next_mismatch = False
            error = "REVISION_MISMATCH"
            print("            (forçado pelo comando m)")
        if error:
            self.reply_error(result_topic, rid, error, corr)
            return

        old = {mp["led_id"] for mp in self.mappings}
        self.mappings = maps
        self.rev += 1
        for led in old - {mp["led_id"] for mp in maps}:  # spec 10.5: LED sem mapping é apagado
            self.leds.pop(led, None)
        self.config_invalid = False
        result = self.result_payload(rid, ok=True)
        self.remember(rid, result)
        print(f"            APLICADO: revisão {self.rev}")
        if self.pending:
            self.pending = False
            print(f"            RESTAURADO pelo app ({len(maps)} mappings); para de pedir restore até o próximo boot")
        if self.next_lose_result:
            self.next_lose_result = False
            print("            (comando l: config/result PERDIDO; o retry com o mesmo request_id vai cair na deduplicação)")
        else:
            self.pub(result_topic, result, correlation=corr, label="config/result OK")
        self.announce(last_request_id=rid)

    def validate_set(self, m):
        if m.get("schema_version") != 1 or not isinstance(m.get("mappings"), list) or not m.get("request_id"):
            return "INVALID_SCHEMA"
        if m.get("esp_id") != self.esp:
            return "ESP_ID_MISMATCH"
        if m.get("expected_revision") != self.rev:
            return "REVISION_MISMATCH"
        valid_channels = {c["channel_id"] for c in self.channels}
        led_ids, chans = set(), set()
        for mp in m["mappings"]:
            if mp.get("channel_id") not in valid_channels:
                return "UNKNOWN_CHANNEL"
            if mp.get("led_id") in led_ids:
                return "LED_ID_CONFLICT"
            if mp.get("channel_id") in chans:
                return "CHANNEL_CONFLICT"
            led_ids.add(mp.get("led_id"))
            chans.add(mp.get("channel_id"))
        return None

    def reply_error(self, topic, rid, code, corr):
        result = self.result_payload(rid, ok=False, error_code=code)
        self.remember(rid, result)
        print(f"            REJEITADO: {code} (revisão continua {self.rev})")
        self.pub(topic, result, correlation=corr, label=f"config/result ERRO {code}")

    def remember(self, rid, result):
        self.dedup[rid] = result
        while len(self.dedup) > DEDUP_SIZE:
            self.dedup.pop(next(iter(self.dedup)))

    def owner_channel(self, led_id):
        return next((mp["channel_id"] for mp in self.mappings if mp["led_id"] == led_id), None)

    def handle_command(self, m):
        acao = m.get("acao")
        est, prat, pos = m.get("estante_led_id"), m.get("prateleira_led_id"), m.get("posicao_led_id")
        print(f"{now()}  << comando {acao}  estante={est} prateleira={prat} posicao={pos}")
        if self.skip():
            return
        if acao == "PING":
            self.pub("wms/pickbylight/ack", {"acao": "PONG", "status": "OK", "esp_id": self.esp, "boot_id": self.boot},
                     label="PONG")
            return
        if acao not in ("ACENDER", "APAGAR", "TESTE_LED"):
            print("            ação desconhecida: ignorada")
            return
        # seção 5.1: responde só o dono; no TESTE_LED sem posição, o dono da prateleira e depois o da estante
        target = pos if acao != "TESTE_LED" else (pos or prat or est)
        owned = [i for i in (est, prat, pos) if i and self.owner_channel(i)]
        for led in owned:
            ch = self.owner_channel(led)
            if acao == "APAGAR":
                self.leds.pop(led, None)
                print(f"            LED {ch} ({led}) APAGADO")
            else:
                self.leds[led] = "ACESO"
                extra = f" por {m.get('duracao_ms')} ms" if acao == "TESTE_LED" else ""
                print(f"            LED {ch} ({led}) ACESO{extra}")
        if not (self.owner_channel(target) or self.a.ack_all):
            print(f"            {target} não está mapeado neste ESP: sem ACK (o app espera 5 s)")
            return
        ack = {"acao": "ACK", "posicao_led_id": pos, "status": "ERRO" if self.led_error else "OK",
               "esp_id": self.esp, "boot_id": self.boot}
        if self.led_error:
            ack["error_code"] = "INTERNAL_ERROR"
        self.pub("wms/pickbylight/ack", ack, label=f"ACK {ack['status']}")

    # ---------- teclado ----------
    def command(self, k):
        with self.lock:
            if k == "s":
                self.silent = not self.silent
                print(f"            modo silencioso {'LIGADO' if self.silent else 'DESLIGADO'}")
            elif k == "m":
                self.next_mismatch = True
                print("            próximo config/set vai responder REVISION_MISMATCH")
            elif k == "l":
                self.next_lose_result = True
                print("            próximo config/set será aplicado, mas o config/result vai se perder")
            elif k == "e":
                self.led_error = not self.led_error
                print(f"            ACK com ERRO {'LIGADO' if self.led_error else 'DESLIGADO'}")
            elif k == "o":
                self.pub(f"{self.root}/status", self.offline_payload("LWT"), retain=True, label="status OFFLINE")
            elif k == "r":
                self.announce()
            elif k == "c":
                self.config_invalid = not self.config_invalid
                print(f"            CONFIG_INVALID {'LIGADO' if self.config_invalid else 'DESLIGADO'}")
                self.pub(f"{self.root}/status", self.status_payload(), retain=True, label="status")
            elif k == "n":
                self.boot = secrets.token_hex(4)
                self.dedup.clear()  # nada sobrevive ao reboot: config vem do app (10.5)
                self.leds.clear()
                self.rev = 0
                self.mappings = []
                self.pending = not self.a.no_restore
                self.restore_interval = 10.0
                self.next_restore_at = 0.0
                print(f"            REBOOT: boot_id novo {self.boot}, revisão 0, sem mappings")
                self.announce()
            elif k == "p":
                print(f"            boot_id {self.boot} | revisão {self.rev} | restore pendente {self.pending} | silencioso {self.silent} | ACK erro {self.led_error}")
                for mp in self.mappings:
                    print(f"            {mp['channel_id']} <- {mp['kind']} {mp['led_id']}  {self.leds.get(mp['led_id'], 'apagado')}")
                if not self.mappings:
                    print("            (sem mappings)")
            elif k == "h":
                print(HELP)
            elif k:
                print("            comando desconhecido (h = ajuda)")

    def clear_retained_and_exit(self):
        for t in (f"{self.root}/status", f"{self.root}/config/state"):
            self.c.publish(t, b"", qos=1, retain=True)
        print(f"{now()}  == retidos apagados")
        self.stop(send_disconnect=True)

    def shutdown(self):
        self.pub(f"{self.root}/status", self.offline_payload("SHUTDOWN"), retain=True, label="status OFFLINE (SHUTDOWN)")
        self.stop(send_disconnect=True)

    def stop(self, send_disconnect):
        time.sleep(0.5)  # deixa o QoS 1 sair antes do DISCONNECT
        if send_disconnect:
            self.c.disconnect()
        self.c.loop_stop()


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="Simulador de ESP32 (contrato v1)")
    ap.add_argument("--host", default="localhost")
    ap.add_argument("--port", type=int, default=1883)
    ap.add_argument("--esp", default="esp-a1b2c3", help="esp_id no formato esp-xxxxxx (hex minúsculo)")
    ap.add_argument("--boot", default=None, help="boot_id fixo (padrão: aleatório a cada execução)")
    ap.add_argument("--channels", type=int, default=4, choices=range(1, 15), metavar="1..14")
    ap.add_argument("--protocol", type=int, default=5, choices=[3, 5])
    ap.add_argument("--ack-all", action="store_true", help="ACK em ACENDER/APAGAR mesmo sem mapping (regressão da separação)")
    ap.add_argument("--verbose", action="store_true", help="mostra o JSON completo de cada envio")
    ap.add_argument("--no-restore", action="store_true",
                    help="comportamento antigo: começa configurável, sem pedir restore (app sem a etapa 5e)")
    args = ap.parse_args()

    sim = EspSim(args)
    sim.start()
    try:
        while True:
            k = input().strip().lower()
            if k == "q":
                sim.shutdown()
                break
            if k == "x":
                sim.clear_retained_and_exit()
                break
            sim.command(k)
    except (KeyboardInterrupt, EOFError):
        print("\nsaindo sem DISCONNECT limpo: o broker vai publicar o LWT OFFLINE")
        sim.c.loop_stop()
        os._exit(0)


if __name__ == "__main__":
    main()
