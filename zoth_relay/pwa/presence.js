/* Spotter Presence — High-Craft Anatomical 2D & 3D Human AI Face
 * Original, dependency-free canvas renderer inspired by projects catalogued on
 * webdev.teknium.io ("Uncommon Web"): Unicode U+2800 2x4 sub-pixel braille packing,
 * Bayer 8x8 ordered dithering, luminance & contour ASCII ramps, halftone lattice,
 * and 3D craniofacial ray-relief projection.
 *
 * API: const p = Presence.mount(canvas, {mode:'2d'|'3d', style:'braille'|'ascii'|'dither'|'lattice'})
 *      p.setState('idle'|'listening'|'thinking'|'speaking'); p.setLevels(muse, user);
 *      p.pulse(); p.setMode(m); p.setStyle(s); p.destroy()
 * Android compat: window.muse(museLevel, userLevel, speaking)
 */
(function (root) {
  'use strict';

  var VOID = '#06070a';
  var STYLES = ['braille', 'ascii', 'dither', 'lattice'];
  var ASCII = ' .,:;+x*#%@W';

  // 8x8 Ordered Bayer Matrix (0..63)
  var B8 = [
     0, 32,  8, 40,  2, 34, 10, 42,
    48, 16, 56, 24, 50, 18, 58, 26,
    12, 44,  4, 36, 14, 46,  6, 38,
    60, 28, 52, 20, 62, 30, 54, 22,
     3, 35, 11, 43,  1, 33,  9, 41,
    51, 19, 59, 27, 49, 17, 57, 25,
    15, 47,  7, 39, 13, 45,  5, 37,
    63, 31, 55, 23, 61, 29, 53, 21
  ];

  // Chiaroscuro Multi-Tone Palette (0=void, 1..5=deep bronze -> specular ivory, 6=cyan-silver rim/mesh)
  var PALETTE = [
    'rgba(0,0,0,0)',
    'rgba(128, 94, 42, 0.56)',   // 1: deep bronze shadow (sockets, neck shadow, side planes)
    'rgba(176, 134, 58, 0.76)',  // 2: warm sculpted bronze mid-shadow
    'rgba(218, 176, 78, 0.90)',  // 3: sovereign gold midtone
    'rgba(246, 212, 114, 0.98)', // 4: bright key-lit plane (brow, bridge, cheekbone, lip)
    'rgba(255, 248, 220, 1.00)', // 5: incandescent ivory catchlight (cornea, nasal tip, Cupid's bow)
    'rgba(130, 216, 255, 0.88)'  // 6: cyber-silver rim light & 3D topological contour
  ];

  function clamp(x, a, b) { return x < a ? a : (x > b ? b : x); }
  function sm(e0, e1, x) {
    var t = clamp((x - e0) / (e1 - e0), 0, 1);
    return t * t * (3 - 2 * t);
  }
  function gauss(dx, dy, sx, sy) {
    var u = dx / sx, v = dy / sy;
    var r2 = u * u + v * v;
    return r2 > 9 ? 0 : Math.exp(-r2);
  }
  function gauss1(d, s) {
    var u = d / s;
    return u * u > 9 ? 0 : Math.exp(-u * u);
  }

  /**
   * Evaluates the 3D anatomical human head, facial features, ears, and neck at
   * canonical coordinates (u, v) in [-1, 1] x [-1, 1].
   * Designed with high-contrast chiaroscuro sculpting so every anatomical plane
   * (brow ridge, almond eyelids, sclera, iris, pupil, catchlight, nasal bridge,
   * nasal tip, alar wings, nostrils, philtrum, Cupid's bow, upper/lower vermilion
   * lips, cheekbones, jawline, chin, and sternocleidomastoid neck tendons) reads
   * unmistakably as a lifelike human face in Braille, ASCII, Dither, and Lattice.
   */
  function evalAnatomy(u, v, rig, is3DMode, out) {
    var au = Math.abs(u);
    var su = u < 0 ? -1 : 1;

    var jaw = rig.jawOpen;        // 0..1
    var round = rig.lipRound;     // 0..1
    var spread = rig.mouthSpread; // -0.2..0.5
    var press = rig.lipPress;     // 0..1

    // Lower jaw & chin drop with speech articulation
    var jawRegion = sm(0.18, 0.33, v) * (1 - sm(0.55, 0.76, v));
    var vJaw = v - jaw * 0.088 * jawRegion;

    // --- 1. HUMAN CRANIOFACIAL SILHOUETTE ---
    var halfW = 0;
    if (vJaw >= -0.76 && vJaw <= 0.50) {
      if (vJaw < -0.22) {
        // Cranial vault dome (smooth elliptical arch from temples v=-0.22 up to crown v=-0.76)
        var tCr = clamp((vJaw + 0.22) / -0.54, 0, 1);
        halfW = 0.425 * Math.sqrt(Math.max(0, 1 - tCr * tCr));
        // Subtle temple indentation (temporal fossa)
        halfW -= 0.012 * gauss1(vJaw + 0.20, 0.08);
      } else if (vJaw < 0.05) {
        // Zygomatic cheekbone flare around v = -0.02..0.03
        halfW = 0.420 + 0.020 * gauss1(vJaw + 0.01, 0.10);
      } else {
        // Taper from cheekbones through buccal hollow & gonial jaw angle (v=0.23) to chin tip (v=0.50)
        var tJw = clamp((vJaw - 0.05) / 0.45, 0, 1);
        var gonial = 0.022 * gauss1(vJaw - 0.22, 0.10);
        halfW = 0.432 * Math.pow(Math.max(0, 1 - Math.pow(tJw, 1.48)), 0.56) + gonial;
        halfW -= jaw * 0.014 * sm(0.10, 0.40, vJaw);
      }
    }

    var headDist = halfW > 0.004 ? au / halfW : 99;
    var inHead = (vJaw >= -0.76 && vJaw <= 0.50 && headDist <= 1.0);

    // --- 2. EARS ---
    var earCy = -0.01 + 0.04 * ((au - 0.41) / 0.07);
    var earDx = (au - 0.435) / 0.048, earDy = (v - earCy) / 0.125;
    var earR2 = earDx * earDx + earDy * earDy;
    var inEar = (au > 0.39 && earR2 < 1.0);

    // --- 3. NECK & STERNOCLEIDOMASTOID + SHOULDERS ---
    var neckW = 0.242 - 0.018 * gauss1(v - 0.60, 0.12) + 0.18 * sm(0.72, 0.96, v);
    var inNeck = (v > 0.35 && v < 0.96 && au < neckW);

    if (!inHead && !inEar && !inNeck) {
      out.mask = 0; out.z = -1; out.lit = 0; out.tone = 0;
      return;
    }

    var z = -0.4, lit = 0, tone = 2;

    // --- NECK SCULPTING ---
    if (inNeck) {
      var nxN = clamp(au / neckW, 0, 1);
      var zNeck = 0.14 * Math.sqrt(Math.max(0, 1 - nxN * nxN)) - 0.16;
      // Sternocleidomastoid (SCM) diagonal V-tendons from behind ears (0.22, 0.46) to sternum (0.035, 0.89)
      var scmC = 0.215 - (v - 0.46) * 0.41;
      var scm = gauss1(au - scmC, 0.036) * sm(0.45, 0.54, v) * (1 - sm(0.86, 0.95, v));
      var throatHollow = gauss(u, v - 0.85, 0.055, 0.07);
      // Deep submental cast shadow beneath the chin and jawline
      var jawBorderV = 0.495 + jaw * 0.085 - 0.20 * (au * au / 0.16);
      var submentalShadow = sm(jawBorderV + 0.11, jawBorderV - 0.01, v) * sm(0.32, 0.0, au);

      var neckLit = (0.18 + 0.28 * (1 - nxN * nxN) + 0.26 * scm - 0.14 * throatHollow) * (1 - 0.86 * submentalShadow);
      // Left-key directional bias + right-rim edge highlight on the neck
      neckLit += (-u * 0.24) * (1 - 0.7 * submentalShadow);
      var neckRim = gauss1(nxN - 0.92, 0.06) * (u > 0 ? 0.42 : 0.22);
      neckLit += neckRim;

      z = zNeck + 0.03 * scm;
      lit = clamp(neckLit, 0.02, 0.85);
      tone = neckRim > 0.22 ? 6 : (lit > 0.36 ? 3 : (lit > 0.18 ? 2 : 1));
    }

    // --- EAR SCULPTING ---
    if (inEar && (!inHead || headDist > 0.96)) {
      var er = Math.sqrt(earR2);
      var helix = gauss1(er - 0.82, 0.15);
      var antihelix = gauss1(er - 0.50, 0.14);
      var concha = gauss1(er - 0.26, 0.20);
      var zEar = -0.06 + 0.08 * Math.sqrt(Math.max(0, 1 - earR2)) + 0.025 * helix;
      var earLit = 0.14 + 0.42 * helix + 0.24 * antihelix - 0.12 * concha;
      if (zEar > z) {
        z = zEar;
        lit = clamp(earLit, 0.04, 0.78);
        tone = helix > 0.5 && u > 0 ? 6 : (lit > 0.35 ? 3 : 2);
      }
    }

    // --- CRANIOFACIAL SCULPTING ---
    if (inHead) {
      var hN = clamp(headDist, 0, 1);
      // 3D Cranial depth relief
      var zFace = 0.44 * Math.sqrt(Math.max(0.01, 1 - hN * hN * 0.90));
      if (vJaw < -0.26) {
        var tc = (vJaw + 0.26) / -0.50;
        zFace *= Math.sqrt(Math.max(0.02, 1 - tc * tc * 0.86));
      } else if (vJaw > 0.26) {
        var tj = (vJaw - 0.26) / 0.25;
        zFace *= (1 - 0.24 * tj * tj);
      }

      // Base chiaroscuro skin luminance (calibrated so midtones are ~0.24..0.44 and shadows are dark!)
      // Key light from upper-left-front: illuminates left (-u) and upper (-vJaw) planes
      var cylFalloff = Math.sqrt(Math.max(0, 1 - hN * hN));
      var keyBias = clamp(0.58 - u * 0.55 - vJaw * 0.22, 0.12, 1.15);
      var fLit = (0.10 + 0.26 * cylFalloff * keyBias);
      var fTone = 3;

      // A. FOREHEAD & TEMPLE HOLLOWS (Frontal Eminences + Temporal Fossa Shadow)
      var frontalHighlight = gauss(u + 0.06, vJaw + 0.42, 0.22, 0.14);
      var templeShadow = gauss(au - 0.32, vJaw + 0.22, 0.085, 0.11);
      var crownRim = gauss1(hN - 0.94, 0.055) * sm(-0.15, -0.72, vJaw);
      fLit += 0.24 * frontalHighlight - 0.16 * templeShadow + 0.32 * crownRim;
      zFace += 0.04 * frontalHighlight - 0.04 * templeShadow;

      // B. SUPRAORBITAL BROW RIDGE & ARCHED EYEBROWS
      // Natural human brow arch: rises from medial head (au=0.055) to peak at lateral 2/3 (au=0.22), then tapers to tail (au=0.32)
      var browPeak = 0.026 * Math.sin(clamp((au - 0.05) / 0.27, 0, 1) * Math.PI);
      var browY = -0.192 - browPeak - rig.browRaise * 0.024
                + rig.browFurrow * 0.016 * (1 - sm(0.05, 0.20, au));

      // Lit bony brow ridge just above the eyebrow arch
      var supraBrowHighlight = gauss(au - 0.18, vJaw - (browY - 0.026), 0.135, 0.022);
      fLit += 0.28 * supraBrowHighlight;
      zFace += 0.055 * supraBrowHighlight;

      // Glabella (bridge between brows) & Corrugator Furrow Lines
      var glabellaLit = gauss(u, vJaw + 0.19, 0.045, 0.032);
      var glabellaShadow = gauss(au - 0.032, vJaw + 0.175, 0.012, 0.028) * (0.35 + 0.65 * rig.browFurrow);
      fLit += 0.18 * glabellaLit - 0.26 * glabellaShadow;

      // C. DEEP ORBITAL EYE SOCKETS (Crucial dark negative space under the brow ridge!)
      var eyeCx = 0.188, eyeCy = -0.076;
      var orbitalSocket = gauss(au - eyeCx, vJaw - (eyeCy - 0.008), 0.112, 0.062);
      fLit *= (1 - 0.85 * orbitalSocket);
      zFace -= 0.078 * orbitalSocket;

      // Sculpted Eyebrow Arch (drawn AFTER socket shadow so the brow arch stands out crisply above the dark socket!)
      var inBrowSpan = sm(0.048, 0.072, au) * (1 - sm(0.29, 0.33, au));
      var browThick = 0.0165 * (1.18 - 0.42 * sm(0.18, 0.32, au));
      var browBand = gauss1(vJaw - browY, browThick) * inBrowSpan;
      if (browBand > 0.08) {
        var browTex = 0.82 + 0.18 * Math.sin((au - 0.05) * 210 - (vJaw - browY) * 150);
        fLit = Math.max(fLit, 0.76 * browBand * browTex);
        zFace += 0.026 * browBand;
        fTone = 4;
      }

      // D. ANATOMICAL HUMAN EYES (Almond Palpebral Fissure, Upper/Lower Eyelids, Sclera, Iris, Pupil, Catchlight)
      var ex = u - su * eyeCx;
      var ey = vJaw - eyeCy;
      var eyeHalfW = 0.085;

      if (Math.abs(ex) < eyeHalfW * 1.25 && Math.abs(ey) < 0.066) {
        var nxE = clamp(ex / eyeHalfW, -1, 1);
        var canthalTilt = -0.005 * nxE * su;
        var almondEnv = Math.max(0, 1 - nxE * nxE);
        var openAmt = clamp(1 - rig.blink, 0.03, 1.0);
        var gazeLid = rig.gy * 0.007 * openAmt;

        var upperLidY = (-0.027 * Math.pow(almondEnv, 0.72) * openAmt) + gazeLid + canthalTilt + (1 - openAmt) * 0.008;
        var lowerLidY = (0.021 * Math.pow(almondEnv, 0.80) * (0.35 + 0.65 * openAmt)) + gazeLid * 0.4 + canthalTilt;

        // 1. Upper Eyelid Fold (lit mobile tarsal plate between supratarsal crease and upper lashes)
        var creaseY = upperLidY - 0.016 * (0.45 + 0.55 * openAmt) * Math.pow(almondEnv, 0.7);
        var tarsalPlate = sm(creaseY, creaseY + 0.006, ey) * sm(upperLidY + 0.002, upperLidY - 0.004, ey) * almondEnv;
        var creaseLine = gauss1(ey - creaseY, 0.0045) * sm(0.05, 0.35, almondEnv);
        fLit += 0.44 * tarsalPlate;
        fLit *= (1 - 0.82 * creaseLine);

        // 2. Lower Eyelid Rim & Aegyosal Roll
        var lowerLidRim = gauss1(ey - (lowerLidY + 0.0055), 0.0048) * almondEnv;
        var tearTrough = gauss1(ey - (lowerLidY + 0.019), 0.0068) * sm(-0.9, 0.5, nxE * su);
        fLit += 0.48 * lowerLidRim;
        fLit *= (1 - 0.68 * tearTrough);

        // 3. Inside the Open Palpebral Fissure (Sclera, Iris, Pupil, Specular Catchlight)
        var dUpper = Math.abs(ey - upperLidY);
        var dLower = Math.abs(ey - lowerLidY);

        if (Math.abs(ex) < eyeHalfW && ey >= upperLidY && ey <= lowerLidY && openAmt > 0.08) {
          var ballNorm = Math.sqrt(ex * ex + ey * ey) / eyeHalfW;
          zFace += 0.055 * Math.sqrt(Math.max(0, 1 - ballNorm * ballNorm * 0.7));

          // Sclera (bright white of the eye at medial & lateral corners around the iris!)
          var upperCastShadow = sm(upperLidY, upperLidY + 0.007, ey);
          var scleraLit = (0.86 - 0.18 * ballNorm * ballNorm) * (0.62 + 0.38 * upperCastShadow);
          fLit = scleraLit;
          fTone = 4;

          // Iris & Pupil tracking (rig.gx, rig.gy)
          var irisCx = rig.gx * 0.025;
          var irisCy = rig.gy * 0.012 - 0.002;
          var idx = ex - irisCx, idy = ey - irisCy;
          var irisR = Math.sqrt(idx * idx + idy * idy);
          var irisRad = 0.034;
          var pupilRad = 0.0145;

          if (irisR < irisRad) {
            var rN = irisR / irisRad;
            if (irisR < pupilRad) {
              fLit = 0.0;
              fTone = 1;
            } else {
              var limbalRing = sm(0.72, 0.96, rN);
              var lowerCrescent = clamp((idy / irisRad + 0.25) * 0.80, 0, 1);
              fLit = (0.28 + 0.46 * lowerCrescent) * (1 - 0.86 * limbalRing);
              fTone = fLit > 0.36 ? 4 : 2;
            }
          }

          // Blazing Corneal Specular Catchlight
          var catchMain = gauss(idx + 0.010, idy + 0.008, 0.0092, 0.0082);
          var catchSec = gauss(idx - 0.011, idy - 0.008, 0.0052, 0.0048);
          if (catchMain > 0.15 || catchSec > 0.22) {
            fLit = 1.20;
            fTone = 5;
          }

          if (dUpper < 0.0042) {
            fLit = 0.01;
          }
        } else if (Math.abs(ex) < eyeHalfW * 1.06) {
          if (dUpper < 0.0055) {
            fLit = openAmt < 0.25 ? 0.65 : 0.02;
            fTone = openAmt < 0.25 ? 4 : 1;
          }
        }
      }

      // E. NOSE (Nasion, Nasal Bridge Dorsum, Side-Nose Shadow Valleys, Nasal Tip, Alar Wings, Nostrils, Columella)
      var bridgeSpan = sm(-0.15, -0.06, vJaw) * (1 - sm(0.10, 0.145, vJaw));
      var bridgeW = 0.026 + 0.014 * sm(-0.10, 0.10, vJaw);

      // 1. Bright Nasal Bridge Highlight down the center (slightly left-key shifted)
      var dorsalHighlight = gauss1(u + 0.006, bridgeW * 0.78) * bridgeSpan;
      // 2. Dark Paranasal Shadow Valleys flanking BOTH sides of the nasal bridge (defines the nose structure!)
      var paranasalShadowL = gauss(u + (bridgeW + 0.028), vJaw - 0.01, 0.026, 0.105);
      var paranasalShadowR = gauss(u - (bridgeW + 0.024), vJaw - 0.01, 0.032, 0.110);
      fLit *= (1 - 0.72 * paranasalShadowL - 0.85 * paranasalShadowR);
      fLit += 0.46 * dorsalHighlight;
      zFace += 0.11 * gauss1(u, bridgeW * 1.4) * bridgeSpan;

      // 3. Sculpted Nasal Tip (Pronasale bulb at vJaw = 0.112)
      var nasalTipBall = gauss(u + 0.005, vJaw - 0.112, 0.038, 0.032);
      var nasalTipRim = gauss1(Math.sqrt(u * u + Math.pow(vJaw - 0.112, 2)) - 0.042, 0.012) * sm(0.09, 0.135, vJaw);
      fLit += 0.56 * nasalTipBall + 0.22 * nasalTipRim;
      zFace += 0.155 * gauss(u, vJaw - 0.112, 0.052, 0.044);
      if (nasalTipBall > 0.45) fTone = 5;

      // 4. Alar Wings (Nostril flares at u = ±0.072, vJaw = 0.130) & Alar Crease Shadows
      var alarDome = gauss(au - 0.068, vJaw - 0.128, 0.026, 0.022);
      var alarCrease = gauss(au - 0.092, vJaw - 0.126, 0.014, 0.026)
                     + gauss(au - 0.038, vJaw - 0.118, 0.012, 0.016); // groove between tip and alar wing
      fLit *= (1 - 0.75 * alarCrease);
      fLit += 0.36 * alarDome;
      zFace += 0.065 * alarDome;

      // 5. Columella & Pitch-Black Nostrils + Subnasal Cast Shadow
      var columella = gauss(u, vJaw - 0.146, 0.014, 0.018);
      var nostrilHole = gauss(au - 0.044, vJaw - 0.144, 0.021, 0.012);
      var subNasalShadow = gauss(u, vJaw - 0.162, 0.082, 0.016);
      fLit += 0.32 * columella;
      fLit *= (1 - 0.96 * clamp(nostrilHole * 1.4, 0, 1)) * (1 - 0.78 * subNasalShadow);
      zFace -= 0.05 * nostrilHole;

      // F. ZYGOMATIC CHEEKBONES, INFRAORBITAL PLANES & BUCCAL HOLLOWS
      // High cheekbone plane catching the light below the outer eye
      var zygomaticHigh = gauss(au - 0.255, vJaw - 0.025, 0.095, 0.058) * (u < 0 ? 1.15 : 0.82);
      // Buccal hollow shadow beneath the cheekbone (carves the sculptural cheekbone & jaw!)
      var buccalShadow = gauss(au - 0.275, vJaw - 0.215, 0.085, 0.105);
      fLit += 0.32 * zygomaticHigh;
      fLit *= (1 - 0.62 * buccalShadow);
      zFace += 0.065 * zygomaticHigh - 0.042 * buccalShadow;

      // G. NASOLABIAL FOLDS (Crease curving from alar wing at 0.092, 0.13 down past mouth corner at 0.175, 0.28)
      var nlfU = 0.090 + (vJaw - 0.13) * 0.54;
      var nlfSpan = sm(0.12, 0.16, vJaw) * (1 - sm(0.28, 0.35, vJaw));
      var nlfShadow = gauss1(au - nlfU, 0.014) * nlfSpan * (0.65 + 0.45 * Math.max(0, spread) + 0.35 * jaw);
      var nlfCheekRoll = gauss1(au - (nlfU + 0.022), 0.022) * nlfSpan;
      fLit *= (1 - 0.76 * nlfShadow);
      fLit += 0.22 * nlfCheekRoll;
      zFace -= 0.022 * nlfShadow;

      // H. PHILTRUM & PHILTRAL COLUMNS (Between Nose and Cupid's Bow: v in 0.16..0.24)
      var philSpan = sm(0.155, 0.175, v) * (1 - sm(0.225, 0.25, v));
      var philGroove = gauss1(u, 0.016) * philSpan;
      var philColumns = gauss1(au - 0.024, 0.010) * philSpan;
      fLit *= (1 - 0.65 * philGroove);
      fLit += 0.32 * philColumns;
      zFace += 0.018 * philColumns - 0.018 * philGroove;

      // I. ANATOMICAL HUMAN LIPS (Cupid's Bow White Roll, Shaded Upper Lip, Dark Stomion/Oral Cavity, Teeth, Pillowy Lower Lip, Mentolabial Shadow)
      var mouthHalfW = 0.158 + spread * 0.026 - round * 0.034;
      var mNorm = clamp(au / mouthHalfW, 0, 1.25);

      if (mNorm < 1.16 && v > 0.195 && vJaw < 0.395) {
        var mEnv = Math.max(0, 1 - mNorm * mNorm);
        var cornerLift = -spread * 0.014 * mNorm * mNorm;
        var stomionY = 0.268 + cornerLift;

        // Distinct M-shaped Cupid's bow on upper vermilion border
        var cupidsArch = 0.015 * gauss1(au - 0.034, 0.022) - 0.009 * gauss1(u, 0.014);
        var upperBorderY = stomionY - (0.031 - press * 0.007) * Math.pow(mEnv, 0.68) - cupidsArch;

        // Speech opening gap
        var openGap = Math.max(0, (jaw * 0.078 + round * 0.016) * (1 - press) * Math.pow(mEnv, 0.80));
        var upperInnerY = stomionY - openGap * 0.18;
        var lowerInnerY = stomionY + openGap * 0.82;
        var lowerBorderY = lowerInnerY + (0.039 - press * 0.007 + round * 0.008) * Math.pow(mEnv, 0.72);

        // 1. Bright Cupid's Bow White Roll (cutaneous upper lip rim catching top key light!)
        var whiteRoll = gauss1(v - (upperBorderY - 0.004), 0.0055) * sm(1.05, 0.08, mNorm);
        fLit += 0.56 * whiteRoll;
        if (whiteRoll > 0.36) fTone = 5;

        // 2. Oral Commissures (dark shadow dimples at left/right corners of the mouth)
        var cornerDimple = gauss(au - mouthHalfW * 0.96, v - stomionY, 0.018, 0.012);
        fLit *= (1 - 0.88 * cornerDimple);

        if (mNorm <= 1.0) {
          if (v >= upperBorderY && v <= upperInnerY) {
            // UPPER VERMILION LIP: slopes inward/downward -> naturally shaded midtone with subtle central tubercle
            var tUp = clamp((v - upperBorderY) / Math.max(0.004, upperInnerY - upperBorderY), 0, 1);
            var tubercle = gauss1(u, 0.028) * gauss1(tUp - 0.45, 0.30);
            zFace += (0.030 + 0.015 * round) * Math.sin(tUp * Math.PI) * mEnv;
            fLit = 0.28 + 0.24 * (1 - tUp) + 0.18 * tubercle;
            fTone = 3;
          } else if (v > upperInnerY && v < lowerInnerY && openGap > 0.005) {
            // OPEN ORAL CAVITY: pitch-black interior + upper teeth row glint when speaking
            zFace -= 0.065;
            fLit = 0.0;
            fTone = 1;
            var teethH = Math.min(0.016, openGap * 0.42);
            if (v < upperInnerY + teethH && au < mouthHalfW * 0.66) {
              var incisorSep = 0.82 + 0.18 * Math.cos(u * 220);
              var archShade = 1.0 - 0.50 * Math.pow(au / (mouthHalfW * 0.66), 2);
              fLit = 0.78 * archShade * incisorSep;
              fTone = 5;
            }
          } else if (v >= lowerInnerY && v <= lowerBorderY) {
            // LOWER VERMILION LIP: faces upward -> catches a bright specular highlight cushion!
            var tLo = clamp((v - lowerInnerY) / Math.max(0.004, lowerBorderY - lowerInnerY), 0, 1);
            zFace += (0.042 + 0.020 * round) * Math.sin(tLo * Math.PI) * mEnv;
            var lipCushion = gauss1(tLo - 0.36, 0.26) * gauss1(u, mouthHalfW * 0.72);
            var lowerLipEdge = sm(0.75, 1.0, tLo);
            fLit = (0.38 + 0.62 * lipCushion) * (1 - 0.65 * lowerLipEdge);
            fTone = lipCushion > 0.38 ? 5 : 4;
          }

          // Pitch-black Stomion parting line where upper and lower lips meet
          if (openGap <= 0.006 && Math.abs(v - upperInnerY) < 0.0062) {
            fLit = 0.0;
            zFace -= 0.016;
          }
        }
      }

      // J. MENTOLABIAL SULCUS (Deep shadow groove under the lower lip) & SCULPTED CHIN + JAWLINE RIM
      var mentoShadow = gauss(u, vJaw - 0.348, 0.098, 0.025);
      var chinBoss = gauss(u + 0.008, vJaw - 0.428, 0.080, 0.048);
      var chinCleft = gauss(u, vJaw - 0.445, 0.012, 0.026);
      fLit *= (1 - 0.82 * mentoShadow) * (1 - 0.35 * chinCleft);
      fLit += 0.42 * chinBoss;
      zFace += 0.072 * chinBoss - 0.042 * mentoShadow;

      // Crisp Mandibular Jawline & Rim Light (separates the face from the dark submental neck shadow!)
      var jawRimBand = gauss1(hN - 0.92, 0.055) * sm(-0.08, 0.48, vJaw);
      var rightRim = jawRimBand * (u > 0 ? 0.62 : 0.36);
      fLit += rightRim;
      if (rightRim > 0.26 && u > 0.12) {
        fTone = 6; // cool cyan-silver rim light along the right jawline & temple!
      } else if (fTone === 3) {
        if (fLit > 0.68) fTone = 5;
        else if (fLit > 0.44) fTone = 4;
        else if (fLit > 0.24) fTone = 3;
        else if (fLit > 0.12) fTone = 2;
        else fTone = 1;
      }

      // In 3D Mode, overlay sculptural anatomical topology lines (jawline contour and subtle 3D geodesic grid)
      if (is3DMode) {
        var latWarp = vJaw - zFace * 0.20;
        var lonWarp = u + (u < 0 ? -1 : 1) * zFace * 0.14;
        var latWire = Math.pow(Math.max(0, Math.cos(latWarp * 26)), 38) * 0.34;
        var lonWire = Math.pow(Math.max(0, Math.cos(lonWarp * 28)), 38) * 0.34;
        var jawWire = gauss1(hN - 0.94, 0.028) * 0.82;
        var topo = Math.max(jawWire, Math.max(latWire, lonWire));
        if (topo > 0.18 && fLit > 0.05) {
          fLit = Math.max(fLit, 0.36 + 0.50 * topo);
          if (jawWire > 0.48) fTone = 6;
        }
      }

      z = zFace;
      lit = clamp(fLit, 0.0, 1.25);
      tone = fTone;
    }

    out.mask = 1;
    out.z = z;
    out.lit = lit;
    out.tone = tone;
  }

  function mount(canvas, opts) {
    opts = opts || {};
    var ctx = canvas.getContext('2d');
    var S = {
      mode: opts.mode || '2d',
      style: opts.style || 'braille',
      state: 'idle',
      muse: 0, user: 0,
      mouth: 0, pulse: 0,
      jawOpen: 0, lipRound: 0, mouthSpread: 0.08, lipPress: 0,
      blink: 0, nextBlink: 2.2, doubleBlink: false,
      gx: 0, gy: 0, tgx: 0, tgy: 0, ngaze: 1.2,
      browRaise: 0, browFurrow: 0,
      yaw: 0, pitch: 0, roll: 0,
      dragActive: false, dragStartX: 0, dragStartY: 0, userYaw: 0, userPitch: 0,
      t0: performance.now(), last: 0, raf: 0, alive: true,
      cols: 0, rows: 0
    };

    var buf = null, toneBuf = null;
    var BW = 0, BH = 0, cell = 7;
    var evalOut = { mask: 0, z: 0, lit: 0, tone: 0 };

    function size() {
      var dpr = Math.min(root.devicePixelRatio || 1, 2);
      var w = canvas.clientWidth || canvas.width, h = canvas.clientHeight || canvas.height;
      canvas.width = Math.max(1, w * dpr | 0);
      canvas.height = Math.max(1, h * dpr | 0);
      var divisor = S.style === 'braille' ? 72 : S.style === 'ascii' ? 66 : 88;
      cell = Math.max(5, Math.round(Math.min(canvas.width, canvas.height) / divisor));
      S.cols = Math.max(16, canvas.width / cell | 0);
      S.rows = Math.max(12, canvas.height / (cell * 1.62) | 0);
      if (S.style === 'braille') {
        BW = S.cols * 2;
        BH = S.rows * 4;
      } else if (S.style === 'ascii') {
        BW = S.cols;
        BH = S.rows;
      } else {
        BW = Math.max(24, canvas.width / cell | 0);
        BH = Math.max(24, canvas.height / cell | 0);
      }
      buf = new Float32Array(BW * BH);
      toneBuf = new Uint8Array(BW * BH);
    }

    /**
     * Unified High-Resolution Ray-Relief Renderer for both 2D Sculpted Portrait & 3D Volumetric Bust
     */
    function renderField(t, is3D) {
      buf.fill(0);
      toneBuf.fill(0);

      var asp = (BW / BH) * (S.style === 'braille' ? 1.24 : S.style === 'ascii' ? 0.62 : 1.0);
      var yaw = S.yaw, pitch = S.pitch, roll = S.roll;
      var cy = Math.cos(yaw), sy = Math.sin(yaw);
      var cp = Math.cos(pitch), sp = Math.sin(pitch);
      var cr = Math.cos(roll), sr = Math.sin(roll);

      var swayX = Math.sin(t * 0.72) * 0.010;
      var swayY = Math.sin(t * 1.42) * 0.009;
      var scale = is3D ? 0.92 : 0.84;

      for (var j = 0; j < BH; j++) {
        var syNorm = ((j / BH) * 2 - 1) * scale - 0.03 + swayY;
        for (var i = 0; i < BW; i++) {
          var sxNorm = (((i / BW) * 2 - 1) * asp) * scale - swayX;

          // Undo 2D head roll
          var rx = sxNorm * cr + syNorm * sr;
          var ry = -sxNorm * sr + syNorm * cr;

          // Smooth C-infinity cranial relief depth + 2-step anatomical nose/brow parallax refinement
          // Ensures zero silhouette tearing while giving genuine 3D nose/brow/lip profile projection!
          var zSmooth = 0.34 * Math.exp(-(rx * rx) / 0.18 - ((ry + 0.05) * (ry + 0.05)) / 0.38);
          var u1 = rx * cy - zSmooth * sy;
          var v1 = ry * cp + (-rx * sy + zSmooth * cy) * sp;

          // Add local 3D nose & brow protrusion parallax along the viewing ray
          var noseProtrude = 0.14 * gauss(u1, v1 - 0.09, 0.075, 0.095)
                           + 0.05 * gauss(Math.abs(u1) - 0.18, v1 + 0.19, 0.12, 0.04);
          var zTotal = zSmooth + noseProtrude;
          var u = rx * cy - zTotal * sy;
          var v = ry * cp + (-rx * sy + zTotal * cy) * sp;

          var idx = j * BW + i;
          evalAnatomy(u, v, S, is3D, evalOut);

          if (evalOut.mask > 0) {
            var vLit = evalOut.lit;
            if (S.state === 'thinking') {
              vLit *= 0.86 + 0.14 * Math.sin(v * 22 - t * 5.5);
            }
            buf[idx] = vLit;
            toneBuf[idx] = evalOut.tone;
          } else if (!is3D) {
            // 2D Subtle Neural Resonance Halo outside silhouette
            var rOut = Math.sqrt((rx / 0.45) * (rx / 0.45) + ((ry + 0.04) / 0.65) * ((ry + 0.04) / 0.65));
            if (rOut > 1.02 && rOut < 1.48) {
              var wave = 0;
              if (S.state === 'speaking') {
                wave = Math.max(0, Math.sin(rOut * 18 - t * 9.5)) * (0.12 + 0.42 * S.mouth) / (rOut * rOut);
              } else if (S.state === 'listening') {
                wave = Math.max(0, Math.sin(rOut * 14 + t * 5.5)) * (0.10 + 0.50 * S.user) / (rOut * rOut);
              } else if (S.state === 'thinking') {
                var ang = Math.atan2(ry, rx);
                if (Math.abs(rOut - 1.16) < 0.04) {
                  wave = Math.pow(Math.max(0, Math.cos(ang * 3 - t * 3.4)), 6) * 0.52;
                }
              }
              if (wave > 0.05) {
                buf[idx] = wave * sm(1.02, 1.12, rOut);
                toneBuf[idx] = 6;
              }
            }
          }
        }
      }

      // In 3D mode, render depth-occluded 3D Orbital Neural Rings around the Bust
      if (is3D) {
        var nOrb = 180;
        var rOrb = 0.58 + 0.05 * S.mouth;
        for (var q = 0; q < nOrb; q++) {
          var a = (q / nOrb) * Math.PI * 2 + t * (S.state === 'thinking' ? 1.8 : 0.48);
          var ox = Math.cos(a) * rOrb;
          var oz = Math.sin(a) * rOrb;
          var oy = 0.18 + Math.sin(a * 2 + t * 0.7) * 0.09;

          var Xo = ox * cy + oz * sy;
          var Zo = -ox * sy + oz * cy;
          var Yo = oy * cp - Zo * sp;
          Zo = oy * sp + Zo * cp;

          var pxO = ((Xo / (asp * scale) + 1) * 0.5 * BW) | 0;
          var pyO = (((Yo + 0.03) / scale + 1) * 0.5 * BH) | 0;
          if (pxO < 0 || pyO < 0 || pxO >= BW || pyO >= BH) continue;

          var idO = pyO * BW + pxO;
          // Depth occlusion: behind the head (Zo < 0) only draws in empty space; in front (Zo >= 0) draws over!
          if (Zo > 0.05 || buf[idO] <= 0.02) {
            var litO = 0.38;
            if (S.state === 'thinking') litO = 0.25 + 0.75 * Math.pow(Math.max(0, Math.cos(a * 3 - t * 3.5)), 4);
            else if (S.state === 'speaking') litO = 0.38 + 0.62 * S.mouth * Math.abs(Math.sin(a * 4 - t * 8));
            else if (S.state === 'listening') litO = 0.32 + 0.68 * S.user;
            buf[idO] = Math.max(buf[idO], litO * (0.45 + 0.55 * (Zo / rOrb + 1) * 0.5));
            toneBuf[idO] = 6;
          }
        }
      }
    }

    function paint() {
      var W = canvas.width, H = canvas.height, i, j, v;
      ctx.fillStyle = VOID;
      ctx.fillRect(0, 0, W, H);

      var cw = W / S.cols, ch = H / S.rows;

      if (S.style === 'braille' || S.style === 'ascii') {
        ctx.font = '600 ' + (ch * 0.96 | 0) + 'px ui-monospace,Menlo,Consolas,monospace';
        ctx.textBaseline = 'top';

        var map = [
          [0, 0, 1], [0, 1, 2], [0, 2, 4],
          [1, 0, 8], [1, 1, 16], [1, 2, 32],
          [0, 3, 64], [1, 3, 128]
        ];

        for (j = 0; j < S.rows; j++) {
          var runChars = '';
          var runTone = 0;
          var runStartCol = 0;

          for (i = 0; i < S.cols; i++) {
            var chCode = ' ';
            var cellTone = 0;

            if (S.style === 'ascii') {
              var idxA = j * BW + i;
              v = clamp(buf[idxA], 0, 0.999);
              if (v > 0.05) {
                chCode = ASCII[(v * ASCII.length) | 0];
                cellTone = toneBuf[idxA] || 3;
              }
            } else {
              // Unicode U+2800 2x4 Sub-Pixel Braille Packing with 8x8 Bayer Ordered Dither
              var bits = 0;
              var maxV = 0;
              var bestTone = 3;
              for (var m = 0; m < 8; m++) {
                var bx = i * 2 + map[m][0];
                var by = j * 4 + map[m][1];
                var bIdx = by * BW + bx;
                var bv = buf[bIdx];
                if (bv > maxV) {
                  maxV = bv;
                  bestTone = toneBuf[bIdx] || 3;
                }
                var thresh = 0.06 + ((B8[(by & 7) * 8 + (bx & 7)] + 0.5) / 64.0) * 0.82;
                if (bv > thresh) {
                  bits |= map[m][2];
                }
              }
              if (bits !== 0) {
                chCode = String.fromCharCode(0x2800 + bits);
                cellTone = bestTone;
              }
            }

            if (i === 0) {
              runTone = cellTone;
              runStartCol = 0;
              runChars = chCode;
            } else if (cellTone === runTone || chCode === ' ') {
              runChars += chCode;
            } else {
              if (runTone > 0 && runChars.trim().length > 0) {
                ctx.fillStyle = PALETTE[runTone];
                ctx.fillText(runChars, runStartCol * cw, j * ch);
              }
              runTone = cellTone;
              runStartCol = i;
              runChars = chCode;
            }
          }
          if (runTone > 0 && runChars.trim().length > 0) {
            ctx.fillStyle = PALETTE[runTone];
            ctx.fillText(runChars, runStartCol * cw, j * ch);
          }
        }
      } else {
        // Dither (8x8 Bayer) & Lattice (Halftone Dot Matrix) modes
        var cx = W / BW, cyy = H / BH;
        for (j = 0; j < BH; j++) {
          for (i = 0; i < BW; i++) {
            var idD = j * BW + i;
            v = buf[idD];
            if (v <= 0.05) continue;
            var tBand = toneBuf[idD] || 3;
            if (S.style === 'dither') {
              var thD = 0.05 + ((B8[(j & 7) * 8 + (i & 7)] + 0.5) / 64.0) * 0.84;
              if (v > thD) {
                ctx.fillStyle = PALETTE[tBand];
                ctx.fillRect(i * cx, j * cyy, Math.max(1, cx - 0.8), Math.max(1, cyy - 0.8));
              }
            } else {
              ctx.fillStyle = PALETTE[tBand];
              ctx.beginPath();
              ctx.arc(i * cx + cx * 0.5, j * cyy + cyy * 0.5, Math.max(0.7, Math.min(1.15, v) * cx * 0.48), 0, 6.283);
              ctx.fill();
            }
          }
        }
        if (S.style === 'lattice') {
          ctx.strokeStyle = 'rgba(212, 170, 76, 0.05)';
          ctx.lineWidth = 1;
          ctx.beginPath();
          for (i = 0; i <= BW; i += 4) { ctx.moveTo(i * cx, 0); ctx.lineTo(i * cx, H); }
          for (j = 0; j <= BH; j += 4) { ctx.moveTo(0, j * cyy); ctx.lineTo(W, j * cyy); }
          ctx.stroke();
        }
      }

      // Subtle HUD pill in bottom-left showing current Mode & Style (tap to cycle style)
      var dpr = Math.min(root.devicePixelRatio || 1, 2);
      var padX = 10 * dpr, padY = H - 26 * dpr;
      var badgeLabel = (S.mode === '3d' ? '3D ANATOMICAL BUST \u00b7 ' : '2D SCULPTED FACE \u00b7 ') + S.style.toUpperCase();
      ctx.font = '600 ' + Math.round(10 * dpr) + 'px ui-monospace,Menlo,Consolas,monospace';
      var tw = ctx.measureText(badgeLabel).width + 14 * dpr;
      var th = 18 * dpr;
      ctx.fillStyle = 'rgba(14, 19, 29, 0.82)';
      ctx.fillRect(padX, padY, tw, th);
      ctx.strokeStyle = 'rgba(212, 170, 76, 0.38)';
      ctx.lineWidth = 1;
      ctx.strokeRect(padX, padY, tw, th);
      ctx.fillStyle = 'rgba(242, 206, 108, 0.92)';
      ctx.textBaseline = 'middle';
      ctx.fillText(badgeLabel, padX + 7 * dpr, padY + th * 0.5);
    }

    function frame(now) {
      if (!S.alive) return;
      S.raf = root.requestAnimationFrame(frame);
      if (document.hidden || now - S.last < 30) return;
      var dt = Math.min(0.08, (now - S.last) / 1000);
      S.last = now;
      var t = (now - S.t0) / 1000;

      // --- 1. SPEECH VISEME & PROSODY RIG ---
      var target = S.muse;
      if (S.state === 'speaking' && S.muse < 0.03) {
        var syl = Math.abs(Math.sin(t * 9.6)) * (0.55 + 0.45 * Math.sin(t * 3.4));
        target = 0.18 + 0.48 * syl;
      }
      target = Math.min(1, target + S.pulse);
      S.pulse *= 0.80;
      S.muse *= 0.88;
      S.mouth += (target - S.mouth) * Math.min(1, dt * 19);
      if (S.state !== 'speaking' && S.muse < 0.02) S.mouth *= 0.82;

      var activeSpeech = S.mouth > 0.035;
      var bilabialWave = activeSpeech ? Math.pow(Math.max(0, Math.sin(t * 13.8)), 10) : 0;
      var roundWave = activeSpeech ? Math.max(0, Math.sin(t * 5.2 + 1.1)) * S.mouth : 0;
      var spreadWave = activeSpeech ? Math.max(0, Math.cos(t * 6.4)) * 0.32 * S.mouth : 0.08;

      var targetJaw = S.mouth * (1 - 0.85 * bilabialWave);
      S.jawOpen += (targetJaw - S.jawOpen) * Math.min(1, dt * 20);
      S.lipPress += (bilabialWave * 0.85 - S.lipPress) * Math.min(1, dt * 22);
      S.lipRound += (roundWave * 0.75 - S.lipRound) * Math.min(1, dt * 14);
      var baseSmile = S.state === 'listening' ? 0.14 : 0.07;
      S.mouthSpread += ((baseSmile + spreadWave - S.lipRound * 0.25) - S.mouthSpread) * Math.min(1, dt * 12);

      // --- 2. EYEBROW & FACS MICRO-EXPRESSIONS ---
      var targetBrowRaise = 0;
      var targetBrowFurrow = 0;
      if (S.state === 'speaking') {
        targetBrowRaise = 0.25 + 0.55 * S.mouth * Math.max(0, Math.sin(t * 4.1));
      } else if (S.state === 'listening') {
        targetBrowRaise = 0.30 + 0.45 * S.user;
      } else if (S.state === 'thinking') {
        targetBrowFurrow = 0.68;
        targetBrowRaise = 0.12;
      }
      S.browRaise += (targetBrowRaise - S.browRaise) * dt * 7;
      S.browFurrow += (targetBrowFurrow - S.browFurrow) * dt * 6;

      // --- 3. REALISTIC HUMAN BLINK & SACCADIC GAZE ---
      S.nextBlink -= dt;
      if (S.nextBlink <= 0) {
        S.blink = 1.0;
        if (!S.doubleBlink && Math.random() < 0.22) {
          S.doubleBlink = true;
          S.nextBlink = 0.24;
        } else {
          S.doubleBlink = false;
          S.nextBlink = 2.4 + Math.random() * 3.8;
        }
      }
      S.blink = Math.max(0, S.blink - dt * 6.8);

      S.ngaze -= dt;
      if (S.ngaze <= 0) {
        if (S.state === 'thinking') {
          S.tgx = (Math.random() > 0.5 ? 1 : -1) * (0.35 + Math.random() * 0.35);
          S.tgy = -0.45 - Math.random() * 0.25;
          S.ngaze = 1.2 + Math.random() * 1.8;
        } else {
          var focusDirect = Math.random() < 0.65;
          S.tgx = focusDirect ? (Math.random() * 2 - 1) * 0.14 : (Math.random() * 2 - 1) * 0.42;
          S.tgy = focusDirect ? (Math.random() * 2 - 1) * 0.10 : (Math.random() * 2 - 1) * 0.24;
          S.ngaze = 0.9 + Math.random() * 2.4;
        }
      }
      S.gx += (S.tgx - S.gx) * Math.min(1, dt * 12);
      S.gy += (S.tgy - S.gy) * Math.min(1, dt * 12);

      // --- 4. NATURAL 3D HEAD KINEMATICS (Yaw, Pitch, Roll) ---
      if (!S.dragActive) {
        S.userYaw *= Math.pow(0.25, dt);
        S.userPitch *= Math.pow(0.25, dt);
      }

      var targetYaw, targetPitch, targetRoll;
      if (S.mode === '3d') {
        targetYaw = Math.sin(t * 0.62 + 0.9) * 0.50 + Math.sin(t * 0.25) * 0.12 + S.gx * 0.18 + S.userYaw;
        targetPitch = Math.sin(t * 0.40 + 0.4) * 0.12 + S.gy * 0.12 - S.mouth * 0.06 + S.userPitch;
        targetRoll = Math.sin(t * 0.29) * 0.055 + (S.state === 'listening' ? 0.06 : 0);
      } else {
        var speechNod = S.state === 'speaking' ? Math.sin(t * 6.2) * 0.045 * S.mouth : 0;
        var listenNod = S.state === 'listening' ? Math.sin(t * 4.8) * 0.035 * Math.min(1, S.user * 3) : 0;
        targetYaw = Math.sin(t * 0.42) * 0.18 + S.gx * 0.15 + S.userYaw;
        targetPitch = Math.sin(t * 0.31) * 0.065 + S.gy * 0.09 + speechNod + listenNod + S.userPitch;
        targetRoll = Math.sin(t * 0.27) * 0.038 + (S.state === 'listening' ? 0.065 : 0) + (S.state === 'thinking' ? -0.05 : 0);
      }

      S.yaw += (targetYaw - S.yaw) * Math.min(1, dt * 6);
      S.pitch += (targetPitch - S.pitch) * Math.min(1, dt * 6);
      S.roll += (targetRoll - S.roll) * Math.min(1, dt * 6);

      S.user *= 0.90;

      renderField(t, S.mode === '3d');
      paint();
    }

    function onPointerDown(e) {
      var pt = e.touches ? e.touches[0] : e;
      var rect = canvas.getBoundingClientRect();
      var x = pt.clientX - rect.left, y = pt.clientY - rect.top;
      if (x < 220 && y > rect.height - 44) {
        var idx = (STYLES.indexOf(S.style) + 1) % STYLES.length;
        S.style = STYLES[idx];
        size();
        return;
      }
      S.dragActive = true;
      S.dragStartX = pt.clientX;
      S.dragStartY = pt.clientY;
    }
    function onPointerMove(e) {
      if (!S.dragActive) return;
      var pt = e.touches ? e.touches[0] : e;
      var dx = (pt.clientX - S.dragStartX) / Math.max(120, canvas.clientWidth || 300);
      var dy = (pt.clientY - S.dragStartY) / Math.max(120, canvas.clientHeight || 300);
      S.userYaw = clamp(dx * 1.15, -0.85, 0.85);
      S.userPitch = clamp(dy * 0.65, -0.45, 0.45);
    }
    function onPointerUp() { S.dragActive = false; }

    canvas.addEventListener('mousedown', onPointerDown);
    root.addEventListener('mousemove', onPointerMove);
    root.addEventListener('mouseup', onPointerUp);
    canvas.addEventListener('touchstart', onPointerDown, { passive: true });
    canvas.addEventListener('touchmove', onPointerMove, { passive: true });
    canvas.addEventListener('touchend', onPointerUp, { passive: true });

    var ro = root.ResizeObserver ? new ResizeObserver(size) : null;
    if (ro) ro.observe(canvas);
    else root.addEventListener('resize', size);
    size();
    S.raf = root.requestAnimationFrame(frame);

    return {
      setState: function (s) { s = s || 'idle'; if (s !== S.state) { S.state = s; S.ngaze = 0; } },
      setLevels: function (muse, user) {
        if (muse != null) S.muse = Math.max(S.muse, +muse || 0);
        if (user != null) S.user = Math.max(S.user, +user || 0);
      },
      pulse: function (a) { S.pulse = Math.min(0.65, S.pulse + (a || 0.35)); },
      setMode: function (m) { S.mode = m === '3d' ? '3d' : '2d'; },
      setStyle: function (s) { if (STYLES.indexOf(s) >= 0) { S.style = s; size(); } },
      get mode() { return S.mode; },
      get style() { return S.style; },
      get state() { return S.state; },
      destroy: function () {
        S.alive = false;
        root.cancelAnimationFrame(S.raf);
        if (ro) ro.disconnect();
      }
    };
  }

  var api = { mount: mount, STYLES: STYLES };
  root.Presence = api;
  if (root.PRESENCE_AUTOBOOT) {
    root.addEventListener('DOMContentLoaded', function () {
      var c = document.getElementById('presence');
      var q = new URLSearchParams(location.search);
      var p = root.presence = mount(c, { mode: q.get('mode') || '2d', style: q.get('style') || 'braille' });
      root.muse = function (m, u, s) {
        p.setLevels(m, u);
        p.setState(s ? 'speaking' : (u > 0.08 ? 'listening' : (p.state === 'thinking' ? 'thinking' : 'idle')));
      };
    });
  }
})(window);
