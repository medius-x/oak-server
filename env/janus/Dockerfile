# OAK janus: default configs + token_auth = true + rotated admin_secret (see JanusVoiceAccessService).
#
# Two stock files are customised (both dumped from the image and edited — see the diff vs
# janus.jcfg.sample / janus.transport.http.jcfg.sample):
#   - janus.jcfg: token_auth = true + admin_secret = "janus_admin_secret" (== .env JANUS_ADMIN_SECRET;
#     the stock default "janusoverlord" is a live hole with 7088 host-mapped);
#   - janus.transport.http.jcfg: admin_http = true — MANDATORY: with token_auth (stored tokens)
#     and no Admin/monitor transport Janus fatals at startup ("No Admin/monitor transport is
#     available, but the stored token based authentication mechanism is enabled... giving up!",
#     exit 1 with empty logs — a live half-day hunt).
# Both are BAKED IN rather than bind-mounted so the whole gateway config travels with one
# rebuildable image. After editing either file: docker compose build janus.
FROM canyan/janus-gateway:latest
COPY janus.jcfg /usr/local/etc/janus/janus.jcfg
COPY janus.transport.http.jcfg /usr/local/etc/janus/janus.transport.http.jcfg
