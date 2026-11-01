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

用法：
  python3 tools/fakeclient.py --port 25560 --name TestBot \
      --commands 'arcade join' --delay 2 --stay 40

退出码：0 = 正常挂到时间结束；1 = 连不上/被踢/出错（stderr 有原因）。
"""

import argparse
import socket
import struct
import sys
import time
import uuid
import zlib

# ---- 协议常量（Paper 26.2 / protocol 776）----
PROTOCOL = 776

STATE_LOGIN, STATE_CONFIG, STATE_PLAY = "login", "config", "play"

# 登录阶段 服务端 → 客户端
CB_LOGIN_DISCONNECT = 0x00
CB_LOGIN_FINISHED = 0x02
CB_LOGIN_COMPRESSION = 0x03
# 登录阶段 客户端 → 服务端
SB_LOGIN_HELLO = 0x00
SB_LOGIN_ACKNOWLEDGED = 0x03

# 配置阶段 服务端 → 客户端
CB_CFG_DISCONNECT = 0x02
CB_CFG_FINISH = 0x03
CB_CFG_KEEPALIVE = 0x04
CB_CFG_PING = 0x05
CB_CFG_SELECT_KNOWN_PACKS = 0x0E
CB_CFG_CODE_OF_CONDUCT = 0x13
# 配置阶段 客户端 → 服务端
SB_CFG_CLIENT_INFO = 0x00
SB_CFG_FINISH = 0x03
SB_CFG_KEEPALIVE = 0x04
SB_CFG_PONG = 0x05
SB_CFG_SELECT_KNOWN_PACKS = 0x07
SB_CFG_ACCEPT_CODE_OF_CONDUCT = 0x09

# 游戏阶段 服务端 → 客户端
CB_PLAY_KEEPALIVE = 0x2C
CB_PLAY_DISCONNECT = 0x20
CB_PLAY_PING = 0x3D
CB_PLAY_START_CONFIGURATION = 0x76
# 游戏阶段 客户端 → 服务端
SB_PLAY_KEEPALIVE = 0x1C
SB_PLAY_PONG = 0x2D
SB_PLAY_CHAT_COMMAND = 0x07


def log(msg):
    print(time.strftime("[fakeclient %H:%M:%S] ") + msg, flush=True)


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


class Conn:
    def __init__(self, host, port):
        self.sock = socket.create_connection((host, port), timeout=15)
        self.sock.settimeout(1.0)
        self.buf = bytearray()
        self.threshold = -1     # -1 = 还没收到 Set Compression
        self.state = STATE_LOGIN
        self.closed = False

    # ---- 读 ----
    def _fill(self):
        try:
            chunk = self.sock.recv(65536)
        except socket.timeout:
            return False
        if not chunk:
            self.closed = True
            return False
        self.buf += chunk
        return True

    def read_packet(self):
        """返回 (packet_id, payload) 或 None（暂时没数据）。"""
        while True:
            try:
                length, pos = read_varint(self.buf, 0)
            except ValueError:
                if not self._fill():
                    return None
                continue
            if len(self.buf) < pos + length:
                if not self._fill():
                    return None
                continue
            body = bytes(self.buf[pos:pos + length])
            del self.buf[:pos + length]
            if self.threshold >= 0:
                data_len, dpos = read_varint(body, 0)
                body = body[dpos:]
                if data_len:
                    body = zlib.decompress(body)
                # data_len == 0 表示未压缩
            pid, ppos = read_varint(body, 0)
            return pid, body[ppos:]

    # ---- 写 ----
    def send(self, packet_id, payload=b""):
        body = write_varint(packet_id) + payload
        if self.threshold >= 0:
            if len(body) >= self.threshold:
                compressed = zlib.compress(body)
                frame = write_varint(len(body)) + compressed
            else:
                frame = write_varint(0) + body       # 0 = 不压缩
            self.sock.sendall(write_varint(len(frame)) + frame)
        else:
            self.sock.sendall(write_varint(len(body)) + body)


# ---------------- 登录流程 ----------------

def login(conn, name, port):
    host = "127.0.0.1"
    handshake = (write_varint(PROTOCOL) + write_string(host)
                 + struct.pack(">H", port)
                 + write_varint(2))
    conn.send(0x00, handshake)
    conn.send(SB_LOGIN_HELLO, write_string(name) + uuid.uuid4().bytes)

    deadline = time.time() + 20
    while time.time() < deadline:
        packet = conn.read_packet()
        if packet is None:
            if conn.closed:
                raise RuntimeError("服务端在登录阶段断开")
            continue
        pid, payload = packet
        if conn.state == STATE_LOGIN:
            if pid == CB_LOGIN_COMPRESSION:
                threshold, _ = read_varint(payload, 0)
                conn.threshold = threshold
                log(f"压缩已启用，阈值 {threshold}")
            elif pid == CB_LOGIN_FINISHED:
                conn.send(SB_LOGIN_ACKNOWLEDGED)
                conn.state = STATE_CONFIG
                log("登录成功，进入配置阶段")
            elif pid == CB_LOGIN_DISCONNECT:
                reason, _ = read_string(payload, 0)
                raise RuntimeError(f"被踢（登录阶段）：{reason}")
        elif conn.state == STATE_CONFIG:
            handle_config(conn, pid, payload)
            if conn.state == STATE_PLAY:
                return
        if conn.state == STATE_PLAY:
            return
    raise RuntimeError("登录超时（20s 没进游戏阶段）")


def handle_config(conn, pid, payload):
    if pid == CB_CFG_KEEPALIVE:
        conn.send(SB_CFG_KEEPALIVE, payload[:8])
    elif pid == CB_CFG_PING:
        conn.send(SB_CFG_PONG, payload[:4])
    elif pid == CB_CFG_SELECT_KNOWN_PACKS:
        conn.send(SB_CFG_SELECT_KNOWN_PACKS, write_varint(0))   # "我什么包都没有"
    elif pid == CB_CFG_CODE_OF_CONDUCT:
        conn.send(SB_CFG_ACCEPT_CODE_OF_CONDUCT)
    elif pid == CB_CFG_FINISH:
        conn.send(SB_CFG_FINISH)
        conn.state = STATE_PLAY
        log("配置完成，进入游戏阶段")
    elif pid == CB_CFG_DISCONNECT:
        reason, _ = read_string(payload, 0)
        raise RuntimeError(f"被踢（配置阶段）：{reason}")


# ---------------- 主循环 ----------------

def pump(conn, until, actions):
    """一边应答心跳一边等；到时间点就执行 actions（按时间排序的 (at, 命令)）。"""
    idx = 0
    while time.time() < until:
        while idx < len(actions) and time.time() >= actions[idx][0]:
            send_command(conn, actions[idx][1])
            idx += 1
        packet = conn.read_packet()
        if packet is None:
            if conn.closed:
                raise RuntimeError("服务端断开连接")
            continue
        pid, payload = packet
        if conn.state == STATE_PLAY:
            if pid == CB_PLAY_KEEPALIVE:
                conn.send(SB_PLAY_KEEPALIVE, payload[:8])
            elif pid == CB_PLAY_PING:
                conn.send(SB_PLAY_PONG, payload[:4])
            elif pid == CB_PLAY_START_CONFIGURATION:
                conn.send(0x10)                  # SERVERBOUND_CONFIGURATION_ACKNOWLEDGED
                conn.state = STATE_CONFIG
                log("服务端要求重新配置")
            elif pid == CB_PLAY_DISCONNECT:
                reason, _ = read_string(payload, 0)
                raise RuntimeError(f"被踢（游戏阶段）：{reason}")
        elif conn.state == STATE_CONFIG:
            handle_config(conn, pid, payload)


def send_command(conn, command):
    # 注意：聊天命令包的字符串**不带**前导斜杠（服务端自己会补）
    conn.send(SB_PLAY_CHAT_COMMAND, write_string(command))
    log(f"发送命令：/{command}")


def main():
    ap = argparse.ArgumentParser(description="冒烟台用的假玩家")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=25560)
    ap.add_argument("--name", default="TestBot")
    ap.add_argument("--commands", default="",
                    help="分号分隔的命令；每项可写成 '<秒>:' 前缀表示相对上一条的延迟（不带前导 /）")
    ap.add_argument("--delay", type=float, default=2.0, help="进入游戏后多久开始发命令（秒）")
    ap.add_argument("--gap", type=float, default=1.0, help="命令之间的间隔（秒）")
    ap.add_argument("--stay", type=float, default=40.0, help="最后一条命令之后再挂多久（秒）")
    ap.add_argument("--connect-timeout", type=float, default=180.0, help="等服务器开服的超时（秒）")
    args = ap.parse_args()

    # 支持 "3:hill join;7:arcade join" 这种逐条延迟；不带前缀的用 --gap
    commands = []
    for item in args.commands.split(";"):
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

    # 服务器可能还没开好 / 登录可能被拖慢（刚开服时实测有 20s+ 的）：连不上就重试，
    # 登录阶段超时也重连（一个卡住的连接会一直挂着，不值得等）。
    deadline = time.time() + args.connect_timeout
    conn = None
    while time.time() < deadline:
        try:
            attempt = Conn(args.host, args.port)
        except OSError:
            time.sleep(1.0)
            continue
        log(f"已连上 {args.host}:{args.port}，以 {args.name} 登录")
        try:
            login(attempt, args.name, args.port)
            conn = attempt
            break
        except Exception as exc:                  # noqa: BLE001 —— 登录阶段重试
            log(f"登录失败（重连）：{exc}")
            try:
                attempt.sock.close()
            except OSError:
                pass
            time.sleep(1.0)
    if conn is None:
        log("连不上服务器 / 登录一直失败")
        return 1

    try:
        at = time.time() + args.delay
        actions = []
        for delay, cmd in commands:
            at += delay
            actions.append((at, cmd))
        end = (actions[-1][0] if actions else at) + args.stay
        pump(conn, end, actions)
        log("正常退出")
        return 0
    except Exception as exc:                  # noqa: BLE001 —— 冒烟工具，出错要看到原因
        log(f"失败：{exc}")
        return 1
    finally:
        try:
            conn.sock.close()
        except OSError:
            pass


if __name__ == "__main__":
    sys.exit(main())
