/* Spotter Presence — animated AI face (built Nov 2 2026 at the hackathon).
 * Original, dependency-free canvas renderer. Techniques inspired by projects
 * catalogued on webdev.teknium.io ("Uncommon Web"): drawille-canvas / dotmax
 * (braille 2x4 packing), three.js AsciiEffect (luminance char ramps),
 * dithercn + Paper Shaders (Bayer ordered dither), Monospace Web (lattice
 * grid), Cobe (Fibonacci point-sphere). No code from them is included.
 *
 * API: const p = Presence.mount(canvas, {mode:'2d'|'3d', style:'braille'|'ascii'|'dither'|'lattice'})
 *      p.setState('idle'|'listening'|'thinking'|'speaking'); p.setLevels(muse, user);
 *      p.pulse(); p.setMode(m); p.setStyle(s); p.destroy()
 * Android compat: window.muse(museLevel, userLevel, speaking)
 */
(function (root) {
  'use strict';
  var GOLD = [229, 192, 88], VOID = '#06070a';
  var B4 = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5];
  var ASCII = ' .:-=+*#%@';
  var STYLES = ['braille', 'ascii', 'dither', 'lattice'];

  function fib(n) { // Fibonacci sphere points
    var pts = [], g = Math.PI * (3 - Math.sqrt(5));
    for (var i = 0; i < n; i++) {
      var y = 1 - (i / (n - 1)) * 2, r = Math.sqrt(1 - y * y), a = g * i;
      pts.push([Math.cos(a) * r, y, Math.sin(a) * r]);
    }
    return pts;
  }
  var SPHERE = fib(1400);

  function mount(canvas, opts) {
    opts = opts || {};
    var ctx = canvas.getContext('2d');
    var S = { mode: opts.mode || '2d', style: opts.style || 'braille', state: 'idle',
      muse: 0, user: 0, mouth: 0, pulse: 0, blink: 0, nextBlink: 2, gx: 0, gy: 0, tgx: 0, tgy: 0,
      ngaze: 1, t0: performance.now(), last: 0, raf: 0, alive: true, cols: 0, rows: 0 };
    var buf = null, BW = 0, BH = 0, cell = 8;

    function size() {
      var dpr = Math.min(root.devicePixelRatio || 1, 2);
      var w = canvas.clientWidth || canvas.width, h = canvas.clientHeight || canvas.height;
      canvas.width = Math.max(1, w * dpr | 0); canvas.height = Math.max(1, h * dpr | 0);
      cell = Math.max(6, Math.round(Math.min(canvas.width, canvas.height) / 46));
      S.cols = Math.max(8, canvas.width / cell | 0); S.rows = Math.max(6, canvas.height / (cell * 1.6) | 0);
      if (S.style === 'braille') { BW = S.cols * 2; BH = S.rows * 4; }
      else if (S.style === 'ascii') { BW = S.cols; BH = S.rows; }
      else { BW = canvas.width / cell | 0; BH = canvas.height / cell | 0; }
      buf = new Float32Array(BW * BH);
    }

    function ell(x, y, cx, cy, rx, ry) { var dx = (x - cx) / rx, dy = (y - cy) / ry; return dx * dx + dy * dy; }
    function sm(e0, e1, x) { x = Math.max(0, Math.min(1, (x - e0) / (e1 - e0))); return x * x * (3 - 2 * x); }

    function field2d(t) {
      var asp = BW / BH * (S.style === 'braille' ? 1.25 : S.style === 'ascii' ? 0.625 : 1);
      var open = S.mouth, bl = 1 - S.blink, st = S.state;
      var eyeR = st === 'listening' ? 0.13 : 0.1;
      for (var j = 0; j < BH; j++) {
        var y = (j / BH) * 2 - 1;
        for (var i = 0; i < BW; i++) {
          var x = ((i / BW) * 2 - 1) * asp, v = 0;
          var hd = ell(x, y, 0, 0.02, 0.56, 0.74);
          if (hd < 1) {
            v = 0.18 + 0.3 * (1 - hd) + 0.25 * sm(0.75, 1, hd); // body + rim light
            v += 0.12 * (-(x) * 0.6 - y * 0.4); // key light top-left
            for (var e = -1; e <= 1; e += 2) {
              var ed = ell(x, y, e * 0.22, -0.14, eyeR, eyeR * 1.15 * Math.max(0.08, bl));
              if (ed < 1) {
                v = 0.95;
                if (ell(x, y, e * 0.22 + S.gx * 0.05, -0.14 + S.gy * 0.05, eyeR * 0.45, eyeR * 0.5 * Math.max(0.1, bl)) < 1) v = 0.05;
              }
            }
            var mh = 0.018 + open * 0.17, mw = 0.17 + open * 0.05;
            var md = ell(x, y, 0, 0.36 + open * 0.03, mw, mh);
            if (md < 1) v = open > 0.08 ? (md > 0.55 ? 1 : 0.02) : 0.9; // lit lips, dark open mouth
            if (st === 'thinking') v *= 0.8 + 0.2 * Math.sin(y * 18 - t * 6);
          } else {
            var r = Math.sqrt(hd), ring = 0;
            if (st === 'speaking') ring = Math.max(0, Math.sin(r * 16 - t * 9)) * (0.5 * S.mouth + 0.08) / (r * r);
            else if (st === 'listening') ring = Math.max(0, Math.sin(r * 12 + t * 5)) * (0.12 + S.user) / (r * r);
            if (st === 'thinking') {
              var a = Math.atan2(y, x), orb = Math.abs(r - 1.22);
              if (orb < 0.05) ring = Math.max(ring, Math.pow(Math.max(0, Math.cos(a * 3 - t * 3)), 8));
            }
            v = ring * sm(1.0, 1.15, r) * 0.7 + 0.05 * Math.exp(-(r - 1) * 6);
          }
          buf[j * BW + i] = v;
        }
      }
    }

    function field3d(t) {
      buf.fill(0);
      var asp = S.style === 'ascii' ? 1.6 : S.style === 'braille' ? 0.8 : 1;
      var yaw = Math.sin(t * 0.45) * 0.55 + S.gx * 0.3, pitch = Math.sin(t * 0.31) * 0.12 + S.gy * 0.15;
      var cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
      var sc = Math.min(BW / asp, BH) * 0.36, open = S.mouth, bl = 1 - S.blink;
      for (var k = 0; k < SPHERE.length; k++) {
        var p = SPHERE[k], x = p[0] * 0.82, y = p[1] * 1.05, z = p[2] * 0.9, b = 0.55;
        if (z > 0.35) { // front face features
          for (var e = -1; e <= 1; e += 2) {
            var ed = ell(x, y, e * 0.3, 0.2, 0.15, 0.14 * Math.max(0.1, bl));
            if (ed < 1) { b = 1.4; z += 0.06; }
          }
          var md = ell(x, y, 0, -0.45, 0.25 + open * 0.05, 0.05 + open * 0.2);
          if (md < 1) { b = open > 0.08 ? (md > 0.5 ? 1.5 : 0.05) : 1.2; z -= open * 0.25; y -= open * 0.06; }
        }
        var X = x * cy + z * sy, Z = -x * sy + z * cy;
        var Y = y * cp - Z * sp; Z = y * sp + Z * cp;
        var depth = (Z + 1) * 0.5;
        var px = (BW / 2 + X * sc * asp) | 0, py = (BH / 2 - Y * sc) | 0;
        if (px < 0 || py < 0 || px >= BW || py >= BH) continue;
        var val = b * (0.15 + 0.85 * depth * depth);
        var id = py * BW + px; if (val > buf[id]) buf[id] = val;
      }
      // orbit rings
      var n = 120, R = 1.3 + 0.08 * S.mouth;
      for (var q = 0; q < n; q++) {
        var a = q / n * Math.PI * 2 + t * (S.state === 'thinking' ? 2.2 : 0.4);
        var rx = Math.cos(a) * R, rz = Math.sin(a) * R, ry = rz * 0.25;
        var X2 = rx * cy + rz * sy, Z2 = -rx * sy + rz * cy;
        var px2 = (BW / 2 + X2 * sc * asp) | 0, py2 = (BH / 2 - ry * sc) | 0;
        if (px2 < 0 || py2 < 0 || px2 >= BW || py2 >= BH) continue;
        var lit = S.state === 'thinking' ? Math.pow(Math.max(0, Math.cos(a * 2 - t * 4)), 4) : 0.25;
        if (S.state === 'speaking') lit = 0.25 + S.mouth;
        if (S.state === 'listening') lit = 0.2 + S.user * 1.5;
        var i2 = py2 * BW + px2; buf[i2] = Math.max(buf[i2], lit * (0.4 + 0.6 * (Z2 / R + 1) / 2));
      }
    }

    function col(a) { return 'rgba(' + GOLD[0] + ',' + GOLD[1] + ',' + GOLD[2] + ',' + a.toFixed(3) + ')'; }

    function paint() {
      var W = canvas.width, H = canvas.height, i, j, v;
      ctx.fillStyle = VOID; ctx.fillRect(0, 0, W, H);
      var cw = W / S.cols, ch = H / S.rows;
      if (S.style === 'braille' || S.style === 'ascii') {
        ctx.font = (ch * 0.95 | 0) + 'px ui-monospace,Menlo,Consolas,monospace';
        ctx.textBaseline = 'top'; ctx.fillStyle = col(1);
        for (j = 0; j < S.rows; j++) {
          var line = '';
          for (i = 0; i < S.cols; i++) {
            if (S.style === 'ascii') {
              v = Math.max(0, Math.min(0.999, buf[j * BW + i]));
              line += ASCII[v * ASCII.length | 0];
            } else {
              var bits = 0, map = [[0, 0, 1], [0, 1, 2], [0, 2, 4], [1, 0, 8], [1, 1, 16], [1, 2, 32], [0, 3, 64], [1, 3, 128]];
              for (var m = 0; m < 8; m++) {
                var bx = i * 2 + map[m][0], by = j * 4 + map[m][1];
                if (buf[by * BW + bx] > (B4[(by & 3) * 4 + (bx & 3)] + 0.5) / 16) bits |= map[m][2];
              }
              line += String.fromCharCode(0x2800 + bits);
            }
          }
          ctx.fillText(line, 0, j * ch);
        }
      } else {
        var cx = W / BW, cyy = H / BH;
        for (j = 0; j < BH; j++) for (i = 0; i < BW; i++) {
          v = buf[j * BW + i]; if (v <= 0.02) continue;
          if (S.style === 'dither') {
            if (v > (B4[(j & 3) * 4 + (i & 3)] + 0.5) / 16) { ctx.fillStyle = col(Math.min(1, 0.55 + v * 0.45)); ctx.fillRect(i * cx, j * cyy, cx - 1, cyy - 1); }
          } else { // lattice
            ctx.fillStyle = col(Math.min(1, 0.25 + v)); ctx.beginPath();
            ctx.arc(i * cx + cx / 2, j * cyy + cyy / 2, Math.max(0.6, Math.min(1, v) * cx * 0.48), 0, 6.283); ctx.fill();
          }
        }
        if (S.style === 'lattice') { // faint grid lines
          ctx.strokeStyle = col(0.06); ctx.lineWidth = 1; ctx.beginPath();
          for (i = 0; i <= BW; i += 4) { ctx.moveTo(i * cx, 0); ctx.lineTo(i * cx, H); }
          for (j = 0; j <= BH; j += 4) { ctx.moveTo(0, j * cyy); ctx.lineTo(W, j * cyy); }
          ctx.stroke();
        }
      }
    }

    function frame(now) {
      if (!S.alive) return;
      S.raf = root.requestAnimationFrame(frame);
      if (document.hidden || now - S.last < 33) return; // ~30fps cap, pause in background
      var dt = Math.min(0.1, (now - S.last) / 1000); S.last = now;
      var t = (now - S.t0) / 1000;
      // mouth: real level when provided, else synthetic syllable envelope while speaking
      var target = S.muse;
      if (S.state === 'speaking' && S.muse < 0.02) target = 0.25 + 0.35 * Math.abs(Math.sin(t * 11)) * Math.abs(Math.sin(t * 3.7));
      target = Math.min(1, target + S.pulse);
      S.pulse *= 0.8; S.muse *= 0.9;
      S.mouth += (target - S.mouth) * Math.min(1, dt * 18);
      if (S.state !== 'speaking' && S.muse < 0.02) S.mouth *= 0.85;
      S.nextBlink -= dt;
      if (S.nextBlink <= 0) { S.blink = 1; S.nextBlink = 2 + Math.random() * 4; }
      S.blink = Math.max(0, S.blink - dt * 7);
      S.ngaze -= dt;
      if (S.ngaze <= 0) { S.tgx = (Math.random() * 2 - 1) * (S.state === 'thinking' ? 1 : 0.5); S.tgy = S.state === 'thinking' ? -0.8 : (Math.random() * 2 - 1) * 0.3; S.ngaze = 1 + Math.random() * 2.5; }
      S.gx += (S.tgx - S.gx) * dt * 4; S.gy += (S.tgy - S.gy) * dt * 4;
      S.user *= 0.92;
      if (S.mode === '3d') field3d(t); else field2d(t);
      paint();
    }

    var ro = root.ResizeObserver ? new ResizeObserver(size) : null;
    if (ro) ro.observe(canvas); else root.addEventListener('resize', size);
    size(); S.raf = root.requestAnimationFrame(frame);

    return {
      setState: function (s) { s = s || 'idle'; if (s !== S.state) { S.state = s; S.ngaze = 0; } },
      setLevels: function (muse, user) { if (muse != null) S.muse = Math.max(S.muse, +muse || 0); if (user != null) S.user = Math.max(S.user, +user || 0); },
      pulse: function (a) { S.pulse = Math.min(0.6, S.pulse + (a || 0.35)); },
      setMode: function (m) { S.mode = m === '3d' ? '3d' : '2d'; },
      setStyle: function (s) { if (STYLES.indexOf(s) >= 0) { S.style = s; size(); } },
      get mode() { return S.mode; }, get style() { return S.style; }, get state() { return S.state; },
      destroy: function () { S.alive = false; root.cancelAnimationFrame(S.raf); if (ro) ro.disconnect(); }
    };
  }

  var api = { mount: mount, STYLES: STYLES };
  root.Presence = api;
  if (root.PRESENCE_AUTOBOOT) {
    root.addEventListener('DOMContentLoaded', function () {
      var c = document.getElementById('presence');
      var q = new URLSearchParams(location.search);
      var p = root.presence = mount(c, { mode: q.get('mode') || '2d', style: q.get('style') || 'braille' });
      root.muse = function (m, u, s) { p.setLevels(m, u); p.setState(s ? 'speaking' : (u > 0.08 ? 'listening' : (p.state === 'thinking' ? 'thinking' : 'idle'))); };
    });
  }
})(window);
