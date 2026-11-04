#!/usr/bin/env python3
"""假玩家（协议直连）—— 给冒烟台用，因为 Paper 26.2 没有 MockBukkit、也无法在控制台"造"一个玩家。

为什么需要它：街机派对这类流程必须**有真人进等待区**才能开局
（`/arcade start` 只能强制跨过人数门槛，跨不过"一个人都没有"）。
没有客户端的话，"单局派对能不能起"就只能靠人肉进服验证，无法进冒烟台。

它能做的事很有限，但够用：
  * 按真实协议登录（离线模式，无加密），停在某个点不动；
  * 应答 keep-alive / ping（不然 30 秒被踢）；
  * 到点往聊天栏发几条命令（如 `arcade join`），然后就一直挂着。

协议号/包 ID 不是猜的：从 `server.jar` 里 `net/minecraft/network/protocol/**` 的
`*Protocols.class` 按注册顺序读出来（见 AGENTS.md 硬规则第 6 条"机制不确定就读字节码"）。
当前值对应 Paper 26.2（protocol 776）。

## 登录为什么曾经很不稳（2026-11-03 修）

服务端在 `Done (` 之后还要收尾（15 个世界存盘 + 11 个模板），这段时间里：

1. **异步 pre-login 排队**：Paper 的 `ServerLoginPacketListenerImpl.handleHello` 把
   "插件 pre-login 事件 + UUID 解析"丢进虚拟线程池，之后**主线程 tick** 才发
   `Set Compression` / `Login Finished`。服务器忙的时候这一段能拖 5–20s；
   而服务端自身有 **30s 门槛**（tick 600 次的 `multiplayer.disconnect.slow_login`
   + netty 的 30s 读超时），所以"干等 60s"没有意义 —— 30s 一到必被踢。
2. **我们原来的就绪栅栏会把 bot 憋死**：所有 bot 登录成功后要等 `threading.Barrier`，
   可等待期间**没人读 socket**，服务端 15s 发一次 keep-alive，30s 没响应就
   `lost connection: Timed out` —— 先登录的那个反而先被踢，两个 bot 永远凑不齐。

因此现在改成：
  * **停滞即重连**：登录阶段只要 `--login-stall` 秒内**一个包都没收到**就主动断开重连
    （阈值随尝试次数放宽，最多 `--login-stall-max`），这样同一个 180s 窗口里能试
    十几次，而不是被 30s 拖死 5–6 次；
  * **等服务期间照样读包**：就绪闸门（Gate）不再是裸 barrier，等待时一直在
    `service()` 里应答 keep-alive/ping，先登录的 bot 不会因为"等队友"被踢；
  * **闸门过闸那一刻 = 命令时间表的 t0**，所有 bot 用同一个 t0，保证"同时在场"；
  * 过闸时打一行 `== ALL READY (N/N) ==`，`smoke.sh` 靠它决定什么时候开始喂控制台命令。

用法：
  python3 tools/fakeclient.py --port 25560 --name TestBot \
      --commands 'arcade join' --delay 2 --stay 40

  # 两个 bot 同时在场（登完一起起算时间表）
  python3 tools/fakeclient.py --name "Beta,Gamma" \
      --commands '8:hub create arcadeparty:hillking hillking_mini_game;16:hub join room_arcadeparty_1' \
      --delay 1 --stay 60

  # 排查登录问题：把每个收到的包都打出来
  python3 tools/fakeclient.py --name Beta --trace --stay 20

退出码：0 = 正常挂到时间结束（多 bot 时要求**每个** bot 都过了就绪闸门）；
1 = 连不上/被踢/没凑齐人/出错（日志里有原因）。
"""

import argparse
import re
import select
import socket
import struct
import sys
import threading
import time
import uuid
import zlib

# ---- 协议常量（Paper 26.2 / protocol 776；顺序取自 *Protocols.class 的 addPacket 序列）----
PROTOCOL = 776

STATE_LOGIN, STATE_CONFIG, STATE_PLAY = "login", "config", "play"

# 登录阶段 服务端 → 客户端
CB_LOGIN_DISCONNECT = 0x00
CB_LOGIN_HELLO = 0x01           # 正版验证请求（离线模式不会来）
CB_LOGIN_FINISHED = 0x02
CB_LOGIN_COMPRESSION = 0x03
CB_LOGIN_CUSTOM_QUERY = 0x04    # velocity/插件登录询问
CB_LOGIN_COOKIE_REQUEST = 0x05
# 登录阶段 客户端 → 服务端
SB_LOGIN_HELLO = 0x00
SB_LOGIN_CUSTOM_QUERY_ANSWER = 0x02
SB_LOGIN_ACKNOWLEDGED = 0x03
SB_LOGIN_COOKIE_RESPONSE = 0x04

# 配置阶段 服务端 → 客户端
CB_CFG_COOKIE_REQUEST = 0x00
CB_CFG_DISCONNECT = 0x02
CB_CFG_FINISH = 0x03
CB_CFG_KEEPALIVE = 0x04
CB_CFG_PING = 0x05
CB_CFG_REGISTRY_DATA = 0x07
CB_CFG_SELECT_KNOWN_PACKS = 0x0E
CB_CFG_CODE_OF_CONDUCT = 0x13
# 配置阶段 客户端 → 服务端
SB_CFG_CLIENT_INFO = 0x00
SB_CFG_COOKIE_RESPONSE = 0x01
SB_CFG_FINISH = 0x03
SB_CFG_KEEPALIVE = 0x04
SB_CFG_PONG = 0x05
SB_CFG_SELECT_KNOWN_PACKS = 0x07
SB_CFG_ACCEPT_CODE_OF_CONDUCT = 0x09

# 游戏阶段 服务端 → 客户端
CB_PLAY_DISCONNECT = 0x20
CB_PLAY_KEEPALIVE = 0x2C
CB_PLAY_PING = 0x3D
CB_PLAY_START_CONFIGURATION = 0x76
# 游戏阶段 客户端 → 服务端
SB_PLAY_CHAT_COMMAND = 0x07
SB_PLAY_CONFIG_ACK = 0x10
SB_PLAY_KEEPALIVE = 0x1C
SB_PLAY_PONG = 0x2D

MAX_PACKET = 32 * 1024 * 1024       # 26.2 的 registry 包很大，但不会这么大


def log(msg):
    print(time.strftime("[fakeclient %H:%M:%S] ") + msg, flush=True)


class Retryable(Exception):
    """这次尝试没成，但值得马上重连再试一次。"""


class Fatal(Exception):
    """再试也没用（协议对不上之类），直接失败。"""


class ServerStopped(Exception):
    """服务端正常关服（multiplayer.disconnect.server_shutdown）——不是我们的错，按正常结束处理。"""


# ---------------- 编解码 ----------------

def write_varint(value):
    out = bytearray()
    while True:
        chunk = value & 0x7F
        value >>= 7
        if value:
            out.append(chunk | 0x80)
        else:
            out.append(chunk)
            return bytes(out)


def read_varint(buf, pos=0):
    value = 0
    shift = 0
    while True:
        if pos >= len(buf):
            raise ValueError("varint 越界")
        byte = buf[pos]
        pos += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, pos
        shift += 7
        if shift > 35:
            raise ValueError("varint 过长")


def write_string(text):
    raw = text.encode("utf-8")
    return write_varint(len(raw)) + raw


def read_string(buf, pos=0):
    length, pos = read_varint(buf, pos)
    return buf[pos:pos + length].decode("utf-8", "replace"), pos + length


def reason_text(payload, pos=0):
    """把踢人原因变成一行可读文本。

    登录阶段的 disconnect 原因是 JSON 字符串；配置/游戏阶段是 NBT 组件（二进制），
    直接当字符串读会把日志弄成"二进制文件"（grep 都得加 -a），所以这里把可打印片段挑出来。
    """
    try:
        text, _ = read_string(payload, pos)
    except ValueError:
        text = ""
    chunks = re.findall(r"[ -~]{6,}", text)
    if not chunks:
        chunks = [c.decode("ascii", "replace")
                  for c in re.findall(rb"[ -~]{6,}", bytes(payload))]
    if not chunks:
        return payload[:80].hex()
    return " / ".join(chunks)[:200]


# ---------------- 连接 ----------------

class Conn:
    """一条连接：负责分帧、压缩、收发。读之前一律先 select，绝不盲等。"""

    def __init__(self, host, port, connect_timeout=10.0):
        self.sock = socket.create_connection((host, port), timeout=connect_timeout)
        try:
            self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except OSError:
            pass
        self.sock.settimeout(None)      # 阻塞模式，但只在 select 说"可读"之后才 recv
        self.buf = bytearray()
        self.threshold = -1             # -1 = 还没收到 Set Compression
        self.closed = False
        self.rx_bytes = 0
        self.packets = 0

    def close(self):
        self.closed = True
        try:
            self.sock.close()
        except OSError:
            pass

    # ---- 读 ----
    def _read_some(self, timeout):
        if self.closed:
            return False
        try:
            ready, _, _ = select.select([self.sock], [], [], timeout)
        except (OSError, ValueError):
            self.closed = True
            return False
        if not ready:
            return False
        try:
            chunk = self.sock.recv(262144)
        except OSError:
            self.closed = True
            return False
        if not chunk:
            self.closed = True          # 对端正常关闭
            return False
        self.buf += chunk
        self.rx_bytes += len(chunk)
        return True

    def _take_frame(self):
        """从缓冲区里切一个完整的（未解压的）包体；不够就返回 None。"""
        if not self.buf:
            return None
        try:
            length, pos = read_varint(self.buf, 0)
        except ValueError:
            if len(self.buf) >= 5:
                raise Fatal("包长 varint 解不开（协议号/包 ID 对不上？）")
            return None
        if length <= 0 or length > MAX_PACKET:
            raise Fatal(f"包长异常：{length}")
        if len(self.buf) < pos + length:
            return None
        body = bytes(self.buf[pos:pos + length])
        del self.buf[:pos + length]
        return body

    def _decode(self, body):
        if self.threshold >= 0:
            data_len, dpos = read_varint(body, 0)
            body = body[dpos:]
            if data_len:
                try:
                    body = zlib.decompress(body)
                except zlib.error as exc:
                    raise Fatal(f"解压失败：{exc}")
        pid, ppos = read_varint(body, 0)
        return pid, body[ppos:]

    def read_packet(self, timeout=0.05):
        """返回 (packet_id, payload)；timeout 内没凑出整包就返回 None。"""
        deadline = time.monotonic() + timeout
        while True:
            body = self._take_frame()
            if body is not None:
                self.packets += 1
                return self._decode(body)
            if self.closed:
                raise Retryable("服务端关闭了连接")
            left = deadline - time.monotonic()
            if left <= 0:
                return None
            self._read_some(left)

    # ---- 写 ----
    def send(self, packet_id, payload=b""):
        if self.closed:
            raise Retryable("连接已关闭，发不出包")
        body = write_varint(packet_id) + payload
        if self.threshold >= 0:
            if len(body) >= self.threshold:
                frame = write_varint(len(body)) + zlib.compress(body)
            else:
                frame = write_varint(0) + body       # 0 = 不压缩
        else:
            frame = body
        try:
            self.sock.sendall(write_varint(len(frame)) + frame)
        except OSError as exc:
            self.closed = True
            raise Retryable(f"发包失败：{exc}")


# ---------------- 一个假玩家 ----------------

class Bot:
    def __init__(self, args, name):
        self.args = args
        self.name = name
        self.conn = None
        self.state = STATE_LOGIN
        self.last_rx = 0.0
        self.login_started = 0.0

    # ---- 连接 ----
    def open(self):
        self.conn = Conn(self.args.host, self.args.port,
                         connect_timeout=self.args.tcp_timeout)
        self.state = STATE_LOGIN
        self.last_rx = self.login_started = time.monotonic()
        handshake = (write_varint(PROTOCOL) + write_string(self.args.host)
                     + struct.pack(">H", self.args.port) + write_varint(2))
        self.conn.send(0x00, handshake)
        self.conn.send(SB_LOGIN_HELLO, write_string(self.name) + uuid.uuid4().bytes)
        log(f"[{self.name}] 已连接 {self.args.host}:{self.args.port}")

    def close(self):
        if self.conn is not None:
            self.conn.close()

    # ---- 登录 ----
    def login(self, stall):
        """读到进游戏阶段为止。stall 秒内服务端一个包都不给，就抛 Retryable 立刻重连。

        服务端自己有 30s 门槛（tick 600 次 slow_login + netty 30s 读超时），
        所以"干等"只会把窗口浪费掉 —— 快失败、快重连才是对的。
        """
        while self.state != STATE_PLAY:
            self.service(0.05)
            now = time.monotonic()
            if now - self.last_rx > stall:
                raise Retryable(f"{stall:.0f}s 内服务端没有任何响应"
                                f"（还在启动/主线程忙，主动断开重连）")
            if now - self.login_started > self.args.login_timeout:
                raise Retryable(f"登录超过 {self.args.login_timeout:.0f}s 还没进游戏阶段")

    # ---- 读一个包并处理 ----
    def service(self, timeout=0.05):
        packet = self.conn.read_packet(timeout)
        if packet is None:
            return False
        self.last_rx = time.monotonic()
        pid, payload = packet
        if self.args.trace:
            log(f"[{self.name}] <- {self.state} 0x{pid:02x} ({len(payload)}B)")
        self.dispatch(pid, payload)
        return True

    def dispatch(self, pid, payload):
        if self.state == STATE_LOGIN:
            self._on_login(pid, payload)
        elif self.state == STATE_CONFIG:
            self._on_config(pid, payload)
        else:
            self._on_play(pid, payload)

    def _on_login(self, pid, payload):
        if pid == CB_LOGIN_COMPRESSION:
            threshold, _ = read_varint(payload, 0)
            self.conn.threshold = threshold
            log(f"[{self.name}] 压缩已启用（阈值 {threshold}）")
        elif pid == CB_LOGIN_FINISHED:
            self.conn.send(SB_LOGIN_ACKNOWLEDGED)
            self.state = STATE_CONFIG
            log(f"[{self.name}] 登录已接受，进入配置阶段")
        elif pid == CB_LOGIN_DISCONNECT:
            raise Retryable(f"被踢（登录阶段）：{reason_text(payload)}")
        elif pid == CB_LOGIN_COOKIE_REQUEST:
            key, _ = read_string(payload, 0)
            self.conn.send(SB_LOGIN_COOKIE_RESPONSE, write_string(key) + write_varint(0))
            log(f"[{self.name}] 应答 Cookie 请求（{key}）")
        elif pid == CB_LOGIN_CUSTOM_QUERY:
            query_id, _ = read_varint(payload, 0)
            self.conn.send(SB_LOGIN_CUSTOM_QUERY_ANSWER,
                           write_varint(query_id) + write_varint(0))
            log(f"[{self.name}] 应答 Custom Query（id={query_id}）")
        elif pid == CB_LOGIN_HELLO:
            raise Fatal("服务端要求正版验证（本客户端只支持 offline-mode）")

    def _on_config(self, pid, payload):
        if pid == CB_CFG_KEEPALIVE:
            self.conn.send(SB_CFG_KEEPALIVE, payload[:8])
        elif pid == CB_CFG_PING:
            self.conn.send(SB_CFG_PONG, payload[:4])
        elif pid == CB_CFG_SELECT_KNOWN_PACKS:
            self.conn.send(SB_CFG_SELECT_KNOWN_PACKS, write_varint(0))   # "我什么包都没有"
        elif pid == CB_CFG_CODE_OF_CONDUCT:
            self.conn.send(SB_CFG_ACCEPT_CODE_OF_CONDUCT)
        elif pid == CB_CFG_COOKIE_REQUEST:
            key, _ = read_string(payload, 0)
            self.conn.send(SB_CFG_COOKIE_RESPONSE, write_string(key) + write_varint(0))
        elif pid == CB_CFG_FINISH:
            self.conn.send(SB_CFG_FINISH)
            self.state = STATE_PLAY
            log(f"[{self.name}] 配置完成，进入游戏阶段")
        elif pid == CB_CFG_DISCONNECT:
            if b"server_shutdown" in bytes(payload):
                raise ServerStopped("服务端关服")
            raise Retryable(f"被踢（配置阶段）：{reason_text(payload)}")

    def _on_play(self, pid, payload):
        if pid == CB_PLAY_KEEPALIVE:
            self.conn.send(SB_PLAY_KEEPALIVE, payload[:8])
        elif pid == CB_PLAY_PING:
            self.conn.send(SB_PLAY_PONG, payload[:4])
        elif pid == CB_PLAY_START_CONFIGURATION:
            self.conn.send(SB_PLAY_CONFIG_ACK)      # SERVERBOUND_CONFIGURATION_ACKNOWLEDGED
            self.state = STATE_CONFIG
            log(f"[{self.name}] 服务端要求重新配置")
        elif pid == CB_PLAY_DISCONNECT:
            if b"server_shutdown" in bytes(payload):
                raise ServerStopped("服务端关服")
            raise Retryable(f"被踢（游戏阶段）：{reason_text(payload)}")

    # ---- 发命令 ----
    def send_command(self, command):
        # 注意：聊天命令包的字符串**不带**前导斜杠（服务端自己会补）
        self.conn.send(SB_PLAY_CHAT_COMMAND, write_string(command))
        log(f"[{self.name}] 发送命令：/{command}")


# ---------------- 就绪闸门 ----------------

class Gate:
    """所有假玩家都进游戏阶段 = 过闸。

    **不再是裸 barrier**：等待期间调用方一直在 `bot.service()` 里应答心跳，
    否则先登录的 bot 会在 30s 后被服务端 `lost connection: Timed out`。
    过闸那一刻（返回的 monotonic 时间）就是所有 bot 命令时间表的 t0。
    """

    def __init__(self, names):
        self.total = len(names)
        self.present = set()
        self.opened_at = None
        self.announced = False
        self._lock = threading.Lock()

    def arrive(self, name):
        with self._lock:
            self.present.add(name)
            count = len(self.present)
            if self.opened_at is None and count >= self.total:
                self.opened_at = time.monotonic()
            opened = self.opened_at
            announce = opened is not None and not self.announced and count >= self.total
            if announce:
                self.announced = True
        log(f"[ready] {name}（{count}/{self.total}）")
        if announce:
            log(f"== ALL READY ({count}/{self.total}) ==")
        return opened

    def leave(self, name):
        with self._lock:
            self.present.discard(name)

    def is_open(self):
        with self._lock:
            return self.opened_at

    def count(self):
        with self._lock:
            return len(self.present)


# ---------------- 主循环 ----------------

def run_schedule(args, bot, t0, actions):
    """按 t0 起算的时间表发命令，期间一直读包（应答心跳）。"""
    end = t0 + (actions[-1][0] if actions else args.delay) + args.stay
    idx = 0
    while True:
        now = time.monotonic()
        while idx < len(actions) and now >= t0 + actions[idx][0]:
            bot.send_command(actions[idx][1])
            idx += 1
        if idx >= len(actions) and now >= end:
            return
        nxt = t0 + actions[idx][0] if idx < len(actions) else end
        bot.service(max(0.01, min(0.2, nxt - now)))


def do_login(args, name, connect_deadline):
    """不停重连直到登录成功；失败抛 Retryable。"""
    attempt = 0
    while True:
        attempt += 1
        if time.monotonic() > connect_deadline:
            raise Retryable(f"在 {args.connect_timeout:.0f}s 内没登录成功"
                            f"（试了 {attempt - 1} 次）")
        stall = min(args.login_stall_max, args.login_stall + attempt_growth(args, attempt))
        bot = Bot(args, name)
        try:
            bot.open()
            started = time.monotonic()
            bot.login(stall)
            log(f"[{name}] 进入游戏阶段（第 {attempt} 次尝试，"
                f"{time.monotonic() - started:.1f}s）")
            return bot
        except OSError as exc:
            bot.close()
            log(f"[{name}] 连不上服务器：{exc}（第 {attempt} 次）")
            time.sleep(args.retry_gap)
        except Retryable as exc:
            bot.close()
            log(f"[{name}] 第 {attempt} 次登录失败：{exc}"
                f"；{args.retry_gap:.1f}s 后重连")
            time.sleep(args.retry_gap)
        except Fatal:
            bot.close()
            raise


def attempt_growth(args, attempt):
    """第 1 次最没耐心，后面逐步放宽（给忙不过来的服务器一点时间）。"""
    return max(0, attempt - 1) * args.login_stall_growth


def run_one(args, name, actions, gate):
    """跑一个假玩家：登录 → 等所有人就绪 → 按时间表发命令 → 挂到结束。返回 0/1。"""
    connect_deadline = time.monotonic() + args.connect_timeout
    ready = True

    while True:
        try:
            bot = do_login(args, name, connect_deadline)
        except Retryable as exc:
            log(f"[{name}] 登录放弃：{exc}")
            return 1
        except Fatal as exc:
            log(f"[{name}] 致命错误：{exc}")
            return 1
        except ServerStopped as exc:
            log(f"[{name}] {exc}，退出")
            return 0 if ready else 1

        if gate is None:
            t0 = time.monotonic()
            log(f"[ready] {name}（1/1）")
            log("== ALL READY (1/1) ==")
            break

        opened = gate.arrive(name)
        if opened is not None:
            t0 = opened
            break

        # 等其它 bot：**边等边读包**，不然自己会被 keep-alive 超时踢掉。
        wait_deadline = time.monotonic() + args.sync_timeout
        next_note = time.monotonic() + 10
        dropped = None
        try:
            while gate.is_open() is None:
                if time.monotonic() > wait_deadline:
                    log(f"[{name}] 等其它假玩家超时（{args.sync_timeout:.0f}s），"
                        f"只有 {gate.count()}/{gate.total} 在场，自己先跑")
                    ready = False
                    break
                bot.service(0.2)
                if time.monotonic() > next_note and gate.is_open() is None:
                    left = wait_deadline - time.monotonic()
                    log(f"[{name}] 已在线，等其它假玩家"
                        f"（{gate.count()}/{gate.total}，还剩 {left:.0f}s）")
                    next_note = time.monotonic() + 10
        except ServerStopped as exc:
            gate.leave(name)
            bot.close()
            log(f"[{name}] {exc}，退出")
            return 0 if gate.is_open() is not None else 1
        except (Retryable, Fatal) as exc:
            dropped = exc
        if dropped is not None:
            # 等待期间被踢（说明服务端还是没稳住）→ 重新登录再排队
            gate.leave(name)
            bot.close()
            log(f"[{name}] 等待其它假玩家期间掉线：{dropped}；重新登录")
            if time.monotonic() > connect_deadline:
                return 1
            continue
        opened = gate.is_open()
        if opened is not None:
            t0 = opened
        else:
            t0 = time.monotonic()
        break

    try:
        run_schedule(args, bot, t0, actions)
        log(f"[{name}] 正常退出")
        return 0 if ready else 1
    except ServerStopped as exc:
        log(f"[{name}] {exc}，正常退出")
        return 0 if ready else 1
    except Retryable as exc:
        log(f"[{name}] 失败：{exc}")
        return 1
    except Fatal as exc:
        log(f"[{name}] 致命错误：{exc}")
        return 1
    finally:
        if gate is not None:
            gate.leave(name)
        bot.close()


def main():
    ap = argparse.ArgumentParser(description="冒烟台用的假玩家")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=25560)
    ap.add_argument("--name", default="TestBot",
                    help="玩家名；逗号分隔可以起多个（TestBot,SecondBot）并发登录")
    ap.add_argument("--stagger", type=float, default=2.0,
                    help="多个假玩家之间错开多少秒登录（错开只是别挤在一起，不是命令时间表）")
    ap.add_argument("--commands", default="",
                    help="分号分隔的命令；每项可写成 '<秒>:' 前缀表示相对上一条的延迟（不带前导 /）")
    ap.add_argument("--delay", type=float, default=2.0,
                    help="就绪（所有人都在场）后多久开始发命令（秒）")
    ap.add_argument("--gap", type=float, default=1.0, help="命令之间的间隔（秒）")
    ap.add_argument("--stay", type=float, default=40.0, help="最后一条命令之后再挂多久（秒）")
    ap.add_argument("--connect-timeout", type=float, default=180.0,
                    help="总共花多久尝试登录（秒）；期间不停快失败重连")
    ap.add_argument("--login-timeout", type=float, default=60.0,
                    help="单次登录最多等多久（秒）。这只是兜底：更早触发的是 --login-stall")
    ap.add_argument("--login-stall", type=float, default=10.0,
                    help="单次尝试里，服务端连续多少秒一个包都不给就主动断开重连（默认 10）。"
                         "服务端自己有 30s 登录门槛，干等只会浪费窗口")
    ap.add_argument("--login-stall-growth", type=float, default=4.0,
                    help="每多试一次，停滞阈值放宽多少秒（默认 4）")
    ap.add_argument("--login-stall-max", type=float, default=28.0,
                    help="停滞阈值上限（秒，默认 28，必须小于服务端 30s 门槛）")
    ap.add_argument("--retry-gap", type=float, default=0.5, help="两次尝试之间歇多久（秒）")
    ap.add_argument("--tcp-timeout", type=float, default=10.0, help="TCP 连接超时（秒）")
    ap.add_argument("--sync-timeout", type=float, default=90.0,
                    help="多个假玩家**等所有人就绪**的最长时间（秒）。设 0 = 不等（各自起算）")
    ap.add_argument("--trace", action="store_true", help="打印每一个收到的包（排查登录问题用）")
    args = ap.parse_args()

    def parse_actions(text):
        """支持 "3:hill join;7:arcade join" 这种逐条延迟；不带前缀的用 --gap。"""
        commands = []
        for item in text.split(";"):
            item = item.strip()
            if not item:
                continue
            delay = args.gap
            head, sep, tail = item.partition(":")
            if sep and head.strip().isdigit():
                delay = float(head.strip())
                item = tail.strip()
            if item:
                commands.append((delay, item))
        out = []
        offset = args.delay
        for delay, cmd in commands:
            offset += delay
            out.append((offset, cmd))
        return out

    # **按 bot 分配命令表**：用 `;;` 分隔，第 i 段给第 i 个 bot（不够就重复最后一段）。
    # 用途：多房间 —— 让 A 进 room1、B 进 room2，才能验证"两个房间各跑一局"。
    shared = parse_actions(args.commands)
    split = [seg for seg in args.commands.split(";;")] if ";;" in args.commands else None
    per_bot = [parse_actions(seg) for seg in split] if split else None

    names = [n.strip() for n in args.name.split(",") if n.strip()] or ["TestBot"]
    names = names[:4]                      # 最多 4 个，防止手滑起一堆
    gate = Gate(names) if (len(names) > 1 and args.sync_timeout > 0) else None
    def actions_for(idx):
        if per_bot:
            return per_bot[min(idx, len(per_bot) - 1)]
        return shared

    if per_bot:
        log(f"假玩家 {', '.join(names)}；**按人分配命令表**（;; 分隔，共 {len(per_bot)} 段）")
        for idx, nm in enumerate(names):
            acts = actions_for(idx)
            log(f"  {nm}: " + ("; ".join(f"+{off:.0f}s /{cmd}" for off, cmd in acts) if acts else "（无）"))
    else:
        log(f"假玩家 {', '.join(names)}；命令表 "
            + ("; ".join(f"+{off:.0f}s /{cmd}" for off, cmd in shared) if shared else "（无）"))
    log(f"就绪闸门 {'开' if gate else '关'}")

    results = []
    threads = []
    for idx, nm in enumerate(names):
        t = threading.Thread(target=lambda nm=nm, i=idx: results.append(
            run_one(args, nm, actions_for(i), gate)),
                             name=nm, daemon=True)
        t.start()
        threads.append(t)
        if idx < len(names) - 1:
            time.sleep(args.stagger)       # 错开登录，避免同一 tick 挤进来
    for t in threads:
        t.join()
    failed = sum(1 for r in results if r != 0)
    log(f"多假玩家结束：{len(results)} 个，失败 {failed} 个"
        + (f"（就绪闸门 {'已过' if gate and gate.is_open() else '没过'}）" if gate else ""))
    return 0 if results and failed == 0 and len(results) == len(names) else 1


if __name__ == "__main__":
    sys.exit(main())
