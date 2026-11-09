"""Combray's self-hosted site-report service.

The phone posts reports (screenshot, address, title, comment, versions) with a
reporter token; Paseo lists, reads and resolves them with an agent token. Reports
hold private browsing data, so the service is for a loopback or tailnet bind
behind TLS, never the public internet.
"""

API_VERSION = 1
SERVICE_NAME = "combray-site-reports"
