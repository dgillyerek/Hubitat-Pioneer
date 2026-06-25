import socket
import time

IP = "192.168.86.74"
PORT = 23
QUERIES = [
    "?P", "?V", "?M", "?F", "?FN",
    "?RGC", "?RGB00", "?RGB05", "?RGB19", "?RGB25", "?RGB06",
    "?AP", "?ZV", "?Z2M",
    "?ZEA", "?ZEP",
]


def query(cmd: str) -> str:
    with socket.create_connection((IP, PORT), timeout=3) as sock:
        sock.sendall((cmd + "\r").encode("ascii"))
        time.sleep(0.3)
        sock.settimeout(1)
        data = b""
        try:
            while True:
                chunk = sock.recv(256)
                if not chunk:
                    break
                data += chunk
        except socket.timeout:
            pass
    return data.decode("ascii", errors="replace").strip() or "(no response)"


if __name__ == "__main__":
    for q in QUERIES:
        print(f"{q:6} -> {query(q)}")
        time.sleep(0.2)
