#!/usr/bin/env python3
"""Local receiver and dashboard for Redmi Wi-Fi Motion Sensor."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse
import json, time, threading

HOST, PORT = "0.0.0.0", 8765
LOCK = threading.Lock()
STATE = {"samples": [], "events": []}
DASHBOARD = r'''<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Wi-Fi Motion Sensor</title><style>*{box-sizing:border-box}body{margin:0;background:#101722;color:#edf3fa;font:15px system-ui}main{max-width:950px;margin:auto;padding:22px}.panel{background:#192536;border:1px solid #304156;border-radius:13px;padding:17px;margin:14px 0}.muted{color:#a8b6c8}.big{font-size:2rem;font-weight:700}canvas{width:100%;height:220px;background:#0b121c;border-radius:8px}.event{padding:9px;border-bottom:1px solid #304156}.warn{color:#ffcc75}</style></head><body><main><h1>Redmi Wi-Fi Motion Sensor</h1><div class="muted">Local dashboard • RSSI experiment • no camera or microphone</div><section class="panel"><div class="muted">Latest Wi-Fi signal</div><div class="big" id="rssi">Waiting for phone…</div><div id="meta" class="muted">Start Android app and enter this computer's LAN IP.</div></section><section class="panel"><h2>Live signal history</h2><canvas id="chart" width="900" height="240"></canvas></section><section class="panel"><h2>Possible movement events</h2><div id="events" class="muted">No events yet.</div></section><section class="panel"><h2>Connection help</h2><p>On ThinkPad run <code>ipconfig</code> and use its Wi-Fi IPv4 address in the phone app. Both devices must be on the same eero network. Allow Python through Windows Firewall on Private networks only.</p></section></main><script>async function refresh(){try{let d=await(await fetch('/api/status')).json(),s=d.samples||[];if(s.length){let a=s[s.length-1];rssi.textContent=a.rssi_dbm+' dBm';meta.textContent='Frequency '+a.frequency_mhz+' MHz • recent range '+a.range_db+' dB • '+s.length+' samples'}let ev=d.events||[];events.innerHTML=ev.length?ev.slice().reverse().map(e=>'<div class="event"><span class="warn">POSSIBLE SIGNAL DISTURBANCE</span> — '+new Date(e.timestamp_ms).toLocaleTimeString()+' — '+e.rssi_dbm+' dBm, range '+e.range_db+' dB</div>').join(''):'No events yet.';draw(s)}catch(e){}}function draw(s){let c=document.getElementById('chart'),g=c.getContext('2d'),w=c.width,h=c.height;g.clearRect(0,0,w,h);g.fillStyle='#0b121c';g.fillRect(0,0,w,h);g.strokeStyle='#304156';g.fillStyle='#a8b6c8';for(let y=-100;y<=-30;y+=10){let py=(-30-y)/70*h;g.beginPath();g.moveTo(0,py);g.lineTo(w,py);g.stroke();g.fillText(y+' dBm',5,py-3)}if(s.length<2)return;g.strokeStyle='#59c3ff';g.lineWidth=2;g.beginPath();s.forEach((p,i)=>{let x=i/Math.max(1,s.length-1)*w,y=(-30-p.rssi_dbm)/70*h;i?g.lineTo(x,y):g.moveTo(x,y)});g.stroke()}setInterval(refresh,1000);refresh()</script></body></html>'''

class Handler(BaseHTTPRequestHandler):
    def send_body(self, code, body, content_type="application/json; charset=utf-8"):
        raw = body if isinstance(body, bytes) else body.encode()
        self.send_response(code); self.send_header("Content-Type", content_type); self.send_header("Content-Length", str(len(raw))); self.send_header("Cache-Control", "no-store"); self.end_headers(); self.wfile.write(raw)
    def do_GET(self):
        path = urlparse(self.path).path
        if path in ("/", "/index.html"): return self.send_body(200, DASHBOARD, "text/html; charset=utf-8")
        if path == "/api/status":
            with LOCK: data = json.dumps(STATE)
            return self.send_body(200, data)
        return self.send_body(404, '{"error":"not found"}')
    def do_POST(self):
        path = urlparse(self.path).path
        if path not in ("/api/sample", "/api/event"): return self.send_body(404, '{"error":"not found"}')
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if size <= 0 or size > 16000: return self.send_body(400, '{"error":"invalid body size"}')
            item = json.loads(self.rfile.read(size))
            if not isinstance(item, dict) or "rssi_dbm" not in item: return self.send_body(400, '{"error":"missing rssi_dbm"}')
            item["received_at_ms"] = int(time.time()*1000)
            with LOCK:
                key = "samples" if path.endswith("sample") else "events"
                STATE[key].append(item); STATE[key] = STATE[key][-180 if key == "samples" else -100:]
            return self.send_body(200, '{"ok":true}')
        except Exception as exc: return self.send_body(400, json.dumps({"error": str(exc)}))
    def log_message(self, fmt, *args): print("[%s] %s" % (self.log_date_time_string(), fmt % args))

if __name__ == "__main__":
    print(f"Listening on http://{HOST}:{PORT}")
    print("Open http://127.0.0.1:8765 on the ThinkPad; enter its LAN IPv4 in the Android app.")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
