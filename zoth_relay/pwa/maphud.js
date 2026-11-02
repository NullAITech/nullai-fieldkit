/* Spotter mini-map HUD + compass (built Nov 2 2026 at the hackathon).
 * Keyless: raster tiles from openstreetmap.org (OSM tile usage policy: light
 * interactive use, visible attribution). Map data © OpenStreetMap
 * contributors, ODbL. Falls back to a compass-only rose when offline/no GPS.
 * API: const h = MapHud.mount(canvas, {zoom:16, onHeading(deg)})
 *      h.setHeading(deg); h.setPosition(lat, lon, acc); h.start(); h.stop()
 */
(function (root) {
  'use strict';
  var GOLD = '#e5c058', VOID = '#06070a', TILE = 'https://tile.openstreetmap.org/';
  var cache = {};
  function tile(z, x, y) {
    var k = z + '/' + x + '/' + y;
    if (!cache[k]) { var im = new Image(); im.crossOrigin = 'anonymous'; im.src = TILE + k + '.png'; cache[k] = im; }
    return cache[k];
  }
  function ll2px(lat, lon, z) {
    var n = 256 * Math.pow(2, z), r = lat * Math.PI / 180;
    return [(lon + 180) / 360 * n, (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * n];
  }
  function mount(canvas, opts) {
    opts = opts || {};
    var ctx = canvas.getContext('2d'), z = opts.zoom || 16;
    var S = { heading: null, lat: null, lon: null, acc: null, raf: 0, watch: null, sent: 0, on: false };
    function onOri(e) {
      var h = e.webkitCompassHeading != null ? e.webkitCompassHeading : (e.absolute || e.type === 'deviceorientationabsolute') && e.alpha != null ? 360 - e.alpha : null;
      if (h != null) setHeading(h);
    }
    function setHeading(h) {
      S.heading = ((+h % 360) + 360) % 360;
      var now = Date.now();
      if (opts.onHeading && now - S.sent > 1000) { S.sent = now; opts.onHeading(Math.round(S.heading)); }
    }
    function draw() {
      S.raf = root.requestAnimationFrame(draw);
      var dpr = Math.min(root.devicePixelRatio || 1, 2), W = canvas.clientWidth * dpr | 0, H = canvas.clientHeight * dpr | 0;
      if (canvas.width !== W) canvas.width = W; if (canvas.height !== H) canvas.height = H;
      var cx = W / 2, cy = H / 2, R = Math.min(W, H) / 2 - 2;
      ctx.clearRect(0, 0, W, H);
      ctx.save(); ctx.beginPath(); ctx.arc(cx, cy, R, 0, 6.283); ctx.clip();
      ctx.fillStyle = VOID; ctx.fillRect(0, 0, W, H);
      var rot = S.heading == null ? 0 : -S.heading * Math.PI / 180; // heading-up
      if (S.lat != null) {
        var p = ll2px(S.lat, S.lon, z), tx = Math.floor(p[0] / 256), ty = Math.floor(p[1] / 256);
        ctx.save(); ctx.translate(cx, cy); ctx.rotate(rot); ctx.scale(dpr, dpr);
        ctx.filter = 'grayscale(1) invert(1) sepia(1) saturate(2.2) brightness(.62)'; // gold-on-black look
        for (var dx = -2; dx <= 2; dx++) for (var dy = -2; dy <= 2; dy++) {
          var im = tile(z, tx + dx, ty + dy);
          if (im.complete && im.naturalWidth) ctx.drawImage(im, (tx + dx) * 256 - p[0], (ty + dy) * 256 - p[1]);
        }
        ctx.filter = 'none';
        if (S.acc) { ctx.strokeStyle = 'rgba(229,192,88,.5)'; ctx.beginPath(); ctx.arc(0, 0, Math.min(R, S.acc / (156543 * Math.cos(S.lat * Math.PI / 180) / Math.pow(2, z))), 0, 6.283); ctx.stroke(); }
        ctx.restore();
      }
      // compass rose ticks + N
      ctx.translate(cx, cy); ctx.rotate(rot);
      ctx.strokeStyle = GOLD; ctx.fillStyle = GOLD; ctx.lineWidth = dpr;
      for (var a = 0; a < 360; a += 15) {
        var r0 = a % 90 === 0 ? R - 10 * dpr : R - 5 * dpr, ar = a * Math.PI / 180;
        ctx.beginPath(); ctx.moveTo(Math.sin(ar) * r0, -Math.cos(ar) * r0); ctx.lineTo(Math.sin(ar) * R, -Math.cos(ar) * R); ctx.stroke();
      }
      ctx.font = 'bold ' + (11 * dpr) + 'px sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
      ['N', 'E', 'S', 'W'].forEach(function (l, i) { var ar = i * Math.PI / 2, rr = R - 18 * dpr; ctx.fillStyle = l === 'N' ? '#ff6b5a' : GOLD; ctx.fillText(l, Math.sin(ar) * rr, -Math.cos(ar) * rr); });
      ctx.restore();
      ctx.strokeStyle = GOLD; ctx.lineWidth = 2 * dpr; ctx.beginPath(); ctx.arc(cx, cy, R, 0, 6.283); ctx.stroke();
      // you-are-here arrow (always points up = heading)
      ctx.fillStyle = GOLD; ctx.beginPath(); ctx.moveTo(cx, cy - 10 * dpr); ctx.lineTo(cx + 7 * dpr, cy + 8 * dpr); ctx.lineTo(cx, cy + 4 * dpr); ctx.lineTo(cx - 7 * dpr, cy + 8 * dpr); ctx.closePath(); ctx.fill();
      ctx.font = (10 * dpr) + 'px sans-serif'; ctx.textAlign = 'center';
      ctx.fillText(S.heading == null ? 'no compass' : Math.round(S.heading) + '°', cx, cy + 22 * dpr);
      if (S.lat == null) ctx.fillText('waiting for GPS', cx, cy + 34 * dpr);
    }
    function start() {
      if (S.on) return; S.on = true;
      var DOE = root.DeviceOrientationEvent;
      var add = function () { root.addEventListener('deviceorientationabsolute', onOri); root.addEventListener('deviceorientation', onOri); };
      if (DOE && typeof DOE.requestPermission === 'function') DOE.requestPermission().then(function (s) { if (s === 'granted') add(); }).catch(function () {}); else add();
      if (navigator.geolocation) S.watch = navigator.geolocation.watchPosition(function (p) { S.lat = p.coords.latitude; S.lon = p.coords.longitude; S.acc = p.coords.accuracy; if (p.coords.heading != null && S.heading == null) setHeading(p.coords.heading); }, function () {}, { enableHighAccuracy: true, maximumAge: 5000 });
      draw();
    }
    function stop() {
      S.on = false; root.cancelAnimationFrame(S.raf);
      root.removeEventListener('deviceorientationabsolute', onOri); root.removeEventListener('deviceorientation', onOri);
      if (S.watch != null && navigator.geolocation) navigator.geolocation.clearWatch(S.watch); S.watch = null;
    }
    return { start: start, stop: stop, setHeading: setHeading,
      setPosition: function (lat, lon, acc) { S.lat = +lat; S.lon = +lon; S.acc = acc || null; if (!S.on) { S.on = true; draw(); } },
      get on() { return S.on; } };
  }
  root.MapHud = { mount: mount, ATTRIBUTION: '© OpenStreetMap contributors' };
  if (root.MAPHUD_AUTOBOOT) root.addEventListener('DOMContentLoaded', function () { root.maphud = mount(document.getElementById('maphud')); });
})(window);
