from django.urls import re_path
from .consumers import TelemetryConsumer

print("📡 ROUTING: WebSocket patterns loaded")

websocket_urlpatterns = [
    re_path(r"^ws/telemetry/$", TelemetryConsumer.as_asgi()),
]