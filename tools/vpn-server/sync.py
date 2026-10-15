#!/usr/bin/env python3
"""Xstream VPN helper: gives each Xstream phone its own address on this WireGuard server.

Phones write their public key to Firebase (vpnPeers/<uid>/<device>/<server id>/pub). This helper (signed in as the
owner account) adds each key to wg0, picks an address, and writes it back as .../ip. Only the standard library is used.
Settings come from /etc/streamx-vpn.env (written by setup.sh).
"""
import ipaddress, json, os, subprocess, time, urllib.request, urllib.parse

ENV = {}
for line in open("/etc/streamx-vpn.env"):
    if "=" in line and not line.startswith("#"):
        k, v = line.strip().split("=", 1); ENV[k] = v
API_KEY, DB, EMAIL, PASSWORD, SID = ENV["API_KEY"], ENV["DB_URL"].rstrip("/"), ENV["EMAIL"], ENV["PASSWORD"], ENV["SERVER_ID"]
WG = ENV.get("WG_IF", "wg0")
NET = ipaddress.ip_network(ENV.get("SUBNET", "10.66.0.0/16"))
STORE = "/etc/wireguard/streamx-peers.json"

def req(url, data=None, method=None):
    body = json.dumps(data).encode() if data is not None else None
    r = urllib.request.Request(url, data=body, method=method or ("POST" if body else "GET"), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(r, timeout=20) as f:
        return json.loads(f.read().decode() or "null")

token, token_at = None, 0
def auth():
    global token, token_at
    if token and time.time() - token_at < 3000: return token
    o = req(f"https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key={API_KEY}",
            {"email": EMAIL, "password": PASSWORD, "returnSecureToken": True})
    token, token_at = o["idToken"], time.time()
    return token

peers = json.load(open(STORE)) if os.path.exists(STORE) else {}   # pub -> ip
def save(): json.dump(peers, open(STORE, "w"))
def wg_add(pub, ip): subprocess.run(["wg", "set", WG, "peer", pub, "allowed-ips", f"{ip}/32"], check=False)
def wg_del(pub): subprocess.run(["wg", "set", WG, "peer", pub, "remove"], check=False)

def next_ip():
    used = set(peers.values())
    for h in NET.hosts():
        s = str(h)
        if s.endswith(".0") or s == str(next(NET.hosts())): continue  # .1 = the server
        if s not in used: return s
    raise RuntimeError("subnet full")

for p, ip in peers.items(): wg_add(p, ip)

while True:
    try:
        t = auth()
        tree = req(f"{DB}/vpnPeers.json?auth={t}") or {}
        seen = set()
        for uid, devs in (tree or {}).items():
            for dev, srvs in (devs or {}).items():
                e = (srvs or {}).get(SID)
                if not isinstance(e, dict) or not e.get("pub"): continue
                pub = e["pub"]; seen.add(pub)
                if pub not in peers:
                    peers[pub] = next_ip(); save(); wg_add(pub, peers[pub])
                if e.get("ip") != peers[pub]:
                    req(f"{DB}/vpnPeers/{uid}/{dev}/{SID}.json?auth={t}", {"ip": peers[pub]}, "PATCH")
        for pub in [p for p in peers if p not in seen]:
            wg_del(pub); peers.pop(pub); save()
    except Exception as ex:
        print("sync:", ex, flush=True); token = None
    time.sleep(3)
