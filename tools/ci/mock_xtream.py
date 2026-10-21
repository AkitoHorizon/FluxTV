#!/usr/bin/env python3
"""Faux serveur Xtream Codes pour tester Flux TV sur émulateur (accessible via http://10.0.2.2:8000)."""
import base64, io, json, os, re, sys, time, random
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs
from PIL import Image, ImageDraw, ImageFont

MEDIA = sys.argv[1] if len(sys.argv) > 1 else "media"
HOST = "http://10.0.2.2:8000"
random.seed(4)
NOW = int(time.time())

FONT = None
for f in ["/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf"]:
    if os.path.exists(f): FONT = f

PALETTE = [(229,9,20),(45,127,249),(242,183,5),(29,185,84),(142,68,173),(230,126,34),(22,160,133),(120,40,30),(30,60,110)]

def font(size):
    return ImageFont.truetype(FONT, size) if FONT else ImageFont.load_default()

def gradient(w, h, c1, c2):
    img = Image.new("RGB", (w, h), c1)
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / h
        d.line([(0, y), (w, y)], fill=tuple(int(c1[i]*(1-t)+c2[i]*t) for i in range(3)))
    return img

_cache = {}
def image(kind, n):
    key = (kind, n)
    if key in _cache: return _cache[key]
    c = PALETTE[n % len(PALETTE)]
    dark = tuple(max(0, v//5) for v in c)
    if kind == "poster":
        img = gradient(300, 450, c, dark); d = ImageDraw.Draw(img)
        d.ellipse([60, 110, 240, 290], outline=(255,255,255), width=6)
        d.text((24, 360), f"FILM {n}", font=font(34), fill=(255,255,255))
    elif kind == "backdrop":
        img = gradient(1280, 720, dark, c); d = ImageDraw.Draw(img)
        d.ellipse([760, 120, 1180, 540], fill=tuple(min(255, v+60) for v in c))
        d.rectangle([880, 300, 980, 720], fill=(15,10,10))
    elif kind == "episode":
        img = gradient(640, 360, c, dark); d = ImageDraw.Draw(img)
        d.text((24, 290), f"EPISODE {n}", font=font(36), fill=(255,255,255))
    else:  # logo de chaîne (PNG transparent)
        img = Image.new("RGBA", (400, 225), (0,0,0,0)); d = ImageDraw.Draw(img)
        d.rounded_rectangle([40, 40, 360, 185], radius=30, fill=c + (255,))
        d.text((90, 80), f"CH {n}", font=font(56), fill=(255,255,255,255))
    b = io.BytesIO(); img.save(b, "PNG"); _cache[key] = b.getvalue()
    return _cache[key]

TITLES = ["Horizon Nord", "Le Dernier Convoi", "Néon Paradis", "Forêt Noire", "Les Braises de Valmont", "Code Écarlate",
          "Le Très Long Titre De Film Pour Vérifier Le Retour À La Ligne Partout", "Été 85", "Ça tourne !", "L'Île",
          "Opération Saguenay", "La Nuit Bleue", "Midi Pile", "Zéro Gravité", "Le Phare"]

live_cats = [{"category_id": str(i), "category_name": n, "parent_id": 0} for i, n in [(1, "Généralistes"), (2, "Sport"), (3, "Info & Docs")]]
live = [{"num": i, "name": f"Chaîne Test {i}" if i != 3 else "Une chaîne au nom vraiment très long pour le test", "stream_type": "live", "stream_id": 100+i,
         "stream_icon": f"{HOST}/img/logo/{i}.png" if i % 5 else "", "epg_channel_id": f"ch{i}", "added": str(NOW), "category_id": str(1 + i % 3)}
        for i in range(1, 25)]
vod_cats = [{"category_id": str(i), "category_name": n, "parent_id": 0} for i, n in [(10, "Action"), (11, "Comédie"), (12, "Drame"), (13, "Catégorie vide")]]
vod = [{"num": i, "name": f"{TITLES[i % len(TITLES)]}" + (f" {i}" if i >= len(TITLES) else ""), "stream_type": "movie", "stream_id": 1000+i,
        "stream_icon": f"{HOST}/img/poster/{i}.png" if i % 7 else "", "rating": str(round(5 + (i*37 % 50)/10, 1)), "rating_5based": 3.5,
        "added": str(NOW - i*86400), "category_id": str(10 + i % 3), "container_extension": "mp4"} for i in range(1, 46)]
series_cats = [{"category_id": str(i), "category_name": n, "parent_id": 0} for i, n in [(20, "Séries Drame"), (21, "Séries Comédie")]]
series = [{"num": i, "name": f"Série {TITLES[(i*3) % len(TITLES)]}", "series_id": 500+i, "cover": f"{HOST}/img/poster/{i+50}.png",
           "plot": "Une série de test pour vérifier l'affichage des résumés sur plusieurs lignes, avec des accents : é, è, à, ç, œ.",
           "releaseDate": f"{2015+i%10}-03-01", "rating": str(round(6 + (i % 4)/2, 1)), "last_modified": str(NOW - i*3600),
           "backdrop_path": [f"{HOST}/img/backdrop/{i}.png"], "category_id": str(20 + i % 2)} for i in range(1, 13)]

def series_info(sid):
    i = sid - 500
    eps = {}
    for s in range(1, 4):
        eps[str(s)] = [{"id": f"{sid}{s}{e:02d}", "episode_num": e, "season": s, "container_extension": "mkv",
                        "title": f"Série Test - S{s:02d}E{e:02d} - Épisode {e} de la saison {s}",
                        "info": {"movie_image": f"{HOST}/img/episode/{s*10+e}.png", "plot": f"Résumé de l'épisode {e}.", "duration": "00:05:30"}}
                       for e in range(1, 7)]
    return {"info": {"name": series[i-1]["name"], "plot": series[i-1]["plot"], "genre": "Drame, Thriller", "cast": "Acteur Un, Actrice Deux",
                     "director": "Réalisatrice Test", "rating": "7.5", "releaseDate": "2021-01-01", "backdrop_path": [f"{HOST}/img/backdrop/{i}.png"],
                     "episode_run_time": "45"}, "episodes": eps}

ECHO = os.environ.get("MOCK_ECHO") == "1"

def m3u():
    """Liste complète get.php (type=m3u_plus), comme un serveur Xtream Codes."""
    cat = lambda cats, cid: next(c["category_name"] for c in cats if c["category_id"] == cid)
    out = ["#EXTM3U"]
    for c in live:
        out += [f'#EXTINF:-1 tvg-id="{c["epg_channel_id"]}" tvg-name="{c["name"]}" tvg-logo="{c["stream_icon"]}" group-title="{cat(live_cats, c["category_id"])}",{c["name"]}',
                f'{HOST}/live/demo/demo/{c["stream_id"]}.ts']
    for v in vod:
        out += [f'#EXTINF:-1 tvg-name="{v["name"]}" tvg-logo="{v["stream_icon"]}" group-title="{cat(vod_cats, v["category_id"])}",{v["name"]}',
                f'{HOST}/movie/demo/demo/{v["stream_id"]}.mp4']
    for sr in series:
        for s in range(1, 4):
            for e in range(1, 7):
                t = f'{sr["name"]} S{s:02d} E{e:02d}'
                out += [f'#EXTINF:-1 tvg-name="{t}" tvg-logo="{sr["cover"]}" group-title="{cat(series_cats, sr["category_id"])}",{t}',
                        f'{HOST}/series/demo/demo/{sr["series_id"]}{s}{e:02d}.mkv']
    return "\n".join(out) + "\n"

def vod_info(vid):
    i = vid - 1000
    return {"info": {"plot": "Un film de démonstration. Ce résumé est volontairement long pour vérifier qu'il est coupé proprement après quelques lignes sans déborder sur les rangées en dessous ni chevaucher les boutons.",
                     "genre": "Action / Aventure", "duration": "01:52:00", "director": "Réalisateur Test", "cast": "Comédien A, Comédienne B, Comédien C",
                     "rating": "7.8", "releasedate": "2024-05-02", "backdrop_path": [f"{HOST}/img/backdrop/{i % 12 + 1}.png"]},
            "movie_data": {"stream_id": vid, "container_extension": "mp4"}}

def short_epg(sid):
    b = lambda s: base64.b64encode(s.encode()).decode()
    start = NOW - 1200
    return {"epg_listings": [
        {"title": b("Le Journal de 20 h"), "description": b("Toute l'actualité du jour."), "start_timestamp": str(start), "stop_timestamp": str(start + 3600)},
        {"title": b("Film du soir"), "description": b("Un grand film."), "start_timestamp": str(start + 3600), "stop_timestamp": str(start + 9000)}]}

class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a): pass

    def send_bytes(self, data, ctype, code=200):
        self.send_response(code); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(data)))
        self.end_headers(); self.wfile.write(data)

    def send_file(self, path, ctype):
        size = os.path.getsize(path)
        rng = self.headers.get("Range")
        start, end = 0, size - 1
        m = re.match(r"bytes=(\d*)-(\d*)", rng or "")
        if m:
            if m.group(1): start = int(m.group(1))
            if m.group(2): end = min(int(m.group(2)), size - 1)
        length = end - start + 1
        self.send_response(206 if m else 200)
        self.send_header("Content-Type", ctype); self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if m: self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        with open(path, "rb") as f:
            f.seek(start); left = length
            try:
                while left > 0:
                    chunk = f.read(min(65536, left))
                    if not chunk: break
                    self.wfile.write(chunk); left -= len(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        form = {k: v[0] for k, v in parse_qs(self.rfile.read(n).decode()).items()}
        self.handle_api(urlparse(self.path).path, form)

    def do_GET(self):
        u = urlparse(self.path); q = {k: v[0] for k, v in parse_qs(u.query).items()}
        p = u.path
        if p.endswith("/get.php"):
            if q.get("username") != "demo" or q.get("password") != "demo": return self.send_bytes(b"", "text/plain", 401)
            return self.send_bytes(m3u().encode(), "audio/x-mpegurl")
        if p.endswith("/player_api.php"): return self.handle_api(p, q)
        m = re.match(r"/img/(\w+)/(\d+)\.png", p)
        if m: return self.send_bytes(image(m.group(1), int(m.group(2))), "image/png")
        if p.startswith("/movie/"): return self.send_file(os.path.join(MEDIA, "test.mp4"), "video/mp4")
        if p.startswith("/series/"): return self.send_file(os.path.join(MEDIA, "test.mkv"), "video/x-matroska")
        if p.startswith("/live/"): return self.send_file(os.path.join(MEDIA, "test.ts"), "video/mp2t")
        self.send_bytes(b"not found", "text/plain", 404)

    def handle_api(self, p, q):
        if True:
            if q.get("username") != "demo" or q.get("password") != "demo":
                return self.send_bytes(json.dumps({"user_info": {"auth": 0}}).encode(), "application/json")
            a = q.get("action")
            # Imite un vrai serveur rencontré : toute « action » est ignorée, seules les infos du compte reviennent
            if ECHO: a = None
            data = {
                None: {"user_info": {"auth": 1, "status": "Active", "exp_date": str(NOW + 90*86400), "active_cons": "0", "max_connections": "2"}, "server_info": {}},
                "get_live_categories": live_cats, "get_live_streams": live,
                "get_vod_categories": vod_cats, "get_vod_streams": vod,
                "get_series_categories": series_cats, "get_series": series,
            }.get(a)
            if a == "get_series_info": data = series_info(int(q.get("series_id", 501)))
            if a == "get_vod_info": data = vod_info(int(q.get("vod_id", 1001)))
            if a == "get_short_epg": data = short_epg(q.get("stream_id"))
            if data is None: data = []
            return self.send_bytes(json.dumps(data, ensure_ascii=False).encode(), "application/json")

if __name__ == "__main__":
    print("Mock Xtream sur :8000", flush=True)
    ThreadingHTTPServer(("0.0.0.0", 8000), H).serve_forever()
