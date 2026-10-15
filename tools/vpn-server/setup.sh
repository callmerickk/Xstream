#!/usr/bin/env bash
# Xstream VPN server — one command on a fresh Ubuntu machine (e.g. Oracle Cloud "Always Free", Singapore / Tokyo):
#   curl -fsSL https://raw.githubusercontent.com/callmerickk/StreamNest/main/tools/vpn-server/setup.sh | sudo bash
# It installs WireGuard, opens UDP 51820, and runs the helper that gives every Xstream phone its own key/address.
# At the end it prints the 4 values for Developer's Corner › Xstream VPN servers.
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "Run with sudo"; exit 1; }
apt-get update -y && apt-get install -y wireguard iptables python3 curl
read -rp "Short id for this server (e.g. sg1): " SID </dev/tty
read -rp "Firebase API key (Xstream CloudConfig.API_KEY): " API_KEY </dev/tty
read -rp "Firebase database URL (https://….firebaseio.com): " DB_URL </dev/tty
read -rp "Owner email (the Developer's Corner account): " EMAIL </dev/tty
read -rsp "Owner password: " PASSWORD </dev/tty; echo
umask 077
cat >/etc/streamx-vpn.env <<E
API_KEY=$API_KEY
DB_URL=$DB_URL
EMAIL=$EMAIL
PASSWORD=$PASSWORD
SERVER_ID=$SID
SUBNET=10.66.0.0/16
WG_IF=wg0
E
NIC=$(ip route show default | awk '{print $5; exit}')
[ -f /etc/wireguard/server.key ] || wg genkey | tee /etc/wireguard/server.key | wg pubkey >/etc/wireguard/server.pub
cat >/etc/wireguard/wg0.conf <<E
[Interface]
Address = 10.66.0.1/16
ListenPort = 51820
PrivateKey = $(cat /etc/wireguard/server.key)
PostUp = iptables -I INPUT -p udp --dport 51820 -j ACCEPT; iptables -I FORWARD -i wg0 -j ACCEPT; iptables -I FORWARD -o wg0 -j ACCEPT; iptables -t nat -A POSTROUTING -o $NIC -j MASQUERADE
PostDown = iptables -D INPUT -p udp --dport 51820 -j ACCEPT; iptables -D FORWARD -i wg0 -j ACCEPT; iptables -D FORWARD -o wg0 -j ACCEPT; iptables -t nat -D POSTROUTING -o $NIC -j MASQUERADE
E
echo "net.ipv4.ip_forward=1" >/etc/sysctl.d/99-streamx.conf && sysctl -p /etc/sysctl.d/99-streamx.conf
# Faster on long links (Singapore/Tokyo ↔ India): BBR congestion control.
printf "net.core.default_qdisc=fq\nnet.ipv4.tcp_congestion_control=bbr\n" >>/etc/sysctl.d/99-streamx.conf && sysctl -p /etc/sysctl.d/99-streamx.conf || true
curl -fsSL https://raw.githubusercontent.com/callmerickk/StreamNest/main/tools/vpn-server/sync.py -o /usr/local/bin/streamx-vpn-sync.py
cat >/etc/systemd/system/streamx-vpn-sync.service <<E
[Unit]
Description=Xstream VPN helper
After=wg-quick@wg0.service network-online.target
[Service]
ExecStart=/usr/bin/python3 /usr/local/bin/streamx-vpn-sync.py
Restart=always
[Install]
WantedBy=multi-user.target
E
systemctl daemon-reload
systemctl enable --now wg-quick@wg0 streamx-vpn-sync
IP=$(curl -fsS https://api.ipify.org || hostname -I | awk '{print $1}')
echo
echo "Done. In Xstream › Developer's Corner › Xstream VPN servers, add:"
echo "  Address:          $IP:51820"
echo "  Server public key: $(cat /etc/wireguard/server.pub)"
echo "  Country code / place: e.g. SG / Singapore"
echo "Oracle Cloud: also allow UDP 51820 in the VCN's Security List (Networking › Virtual cloud networks › Security Lists › Ingress)."
