# Point /etc/resolv.conf at the stub resolver (127.0.0.53) so that
# DNSSEC/mDNS/LLMNR features of systemd-resolved are available.
# systemd-resolved.service is enabled via systemd-resolved-enable recipe
# (preset drop-in), not here, so the systemd recipe itself is not rebuilt.
RESOLV_CONF = "stub-resolv"
