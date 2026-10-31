#!/usr/bin/env bash
# prepush-secret-scan.sh - refuse to push credentials into this repo.
#
# WHY THIS EXISTS
#   FieldKit talks to its relay with a bearer token, holds a Meta/Wearables key, and
#   drives a phone whose shared_prefs carry that relay token in clear text. Every one
#   of those values has been one `git add -A` away from a public repository. GitHub's
#   own push protection is the second line, not the first: it knows provider formats
#   like ghp_/AKIA, it never hears about this project's relay token, and it cannot
#   tell you that a device prefs dump is the leak.
#
# WHAT IT CHECKS
#   Path rules  - files that should never be in the tree at all: .env, *.pem/*.key/
#                 *.jks/*.keystore, id_rsa, .netrc, credentials.json, shared_prefs/
#                 dumps, secrets/tokens files, and build output (*.apk, *.aab).
#   Content rules - private-key blocks, GitHub/OpenAI/Meta/AWS/Google/Slack/Tailscale
#                 token shapes, JWTs, literal Bearer tokens, `password = "..."` style
#                 assignments, XML values that look like stored tokens, and a
#                 high-entropy catch-all for anything that looks like a random secret
#                 even when nobody labelled it.
#
# WHAT A PASS MEANS, PRECISELY
#   exit 0 means every file this run could see was actually read and matched none of
#   the rules. It does NOT mean the repository is free of credentials: a secret that
#   is encoded, split, encrypted, or that was already in history before this guard
#   existed will pass. exit 2 means nothing was verified at all, and a push in that
#   state is unguarded - that is a refusal, not a pass.
#
# EXIT CODES
#   0  clean: something was read and nothing matched (deletion-only pushes included,
#      because nothing entered the repository)
#   1  a credential would have been pushed - the push is refused
#   2  nothing could be verified (not a git repo, no git, no readable trees)
#
# USAGE
#   tools/prepush-secret-scan.sh              outgoing commits (vs upstream), else tree
#   tools/prepush-secret-scan.sh --all        every tracked file + untracked, on disk
#   tools/prepush-secret-scan.sh --range A..B a specific range of commits
#   tools/prepush-secret-scan.sh --hook       pre-push hook mode: refs arrive on stdin
#   tools/prepush-secret-scan.sh --self-check prove the rules catch leaks and spare clean code
#   tools/prepush-secret-scan.sh --list-rules print every rule
#   add --repo DIR before other flags to scan another working tree
#   add -v to list every hit instead of the first few per rule
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
MODE=auto
RANGE=""
MAX_PER_RULE=4
SELF_CHECK=0
LIST_RULES=0
NULL_SHA=0000000000000000000000000000000000000000
SIZE_CAP=2097152   # skip content of files over 2 MiB; the path rules still apply

die() { echo "prepush-secret-scan: $*" >&2; exit 2; }

while [ $# -gt 0 ]; do
  case "$1" in
    --repo)      REPO="${2:-}"; shift 2 ;;
    --all)       MODE=all; shift ;;
    --range)     MODE=range; RANGE="${2:-}"; shift 2 ;;
    --hook)      MODE=hook; shift ;;
    --self-check) SELF_CHECK=1; shift ;;
    --list-rules) LIST_RULES=1; shift ;;
    -v|--verbose) MAX_PER_RULE=1000000; shift ;;
    -h|--help)   sed -n '2,50p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "prepush-secret-scan: unknown argument: $1" >&2; exit 2 ;;
  esac
done

# ---------------------------------------------------------------------------
# Rules.  name|regex   for paths;  name|regex|value-regex for content ("-" = the
# whole match is the value).  grep -E syntax, one match per line is enough.
# ---------------------------------------------------------------------------
PATH_RULE_NAMES=(); PATH_RULE_RX=()
while IFS='|' read -r n rx; do
  [ -z "${n:-}" ] && continue
  PATH_RULE_NAMES+=("$n"); PATH_RULE_RX+=("$rx")
done <<'EOF'
env-file|(^|/)\.env(\..+)?$
env-like|(^|/)(meta\.env|env\.json)$
private-key-file|\.(pem|key|p12|pfx|pkcs12|jks|keystore|pepk)$
ssh-private-key|(^|/)id_(rsa|dsa|ecdsa|ed25519)$
login-file|(^|/)\.?(netrc|htpasswd|git-credentials)$
cloud-credentials|(^|/)(credentials|service[-_]account[^/]*)\.json$
device-prefs-dump|(^|/)shared_prefs/[^/]*\.xml$
secrets-file|(^|/)(secrets?|tokens?)\.(json|ya?ml|txt|xml)$
build-artifact|\.(apk|aab|idsig)$
ssh-dir|(^|/)\.ssh/
EOF

CONTENT_RULE_NAMES=(); CONTENT_RULE_RX=(); CONTENT_RULE_VAL=()
while IFS='|' read -r n rx val; do
  [ -z "${n:-}" ] && continue
  CONTENT_RULE_NAMES+=("$n"); CONTENT_RULE_RX+=("$rx"); CONTENT_RULE_VAL+=("$val")
done <<'EOF'
private-key-block|-----BEGIN [A-Z ]*PRIVATE KEY-----|-
github-token|gh[pousr]_[A-Za-z0-9]{30,}|gh[pousr]_[A-Za-z0-9]{30,}
github-pat|github_pat_[A-Za-z0-9_]{40,}|-
openai-key|sk-[A-Za-z0-9_-]{20,}|-
meta-access-token|EAA[A-Za-z0-9]{40,}|-
aws-access-key-id|AKIA[0-9A-Z]{16}|-
google-api-key|AIza[0-9A-Za-z_-]{35}|-
slack-token|xox[baprs]-[A-Za-z0-9-]{10,}|-
tailscale-key|tskey-[A-Za-z0-9-]{10,}|-
jwt|eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.|-
bearer-literal|Bearer[[:space:]]+[A-Za-z0-9._~+/-]{25,}=*|Bearer[[:space:]]+[A-Za-z0-9._~+/-]{25,}=*
assignment-quoted|([Pp]assw(or)?d|[Pp]assphrase|[Ss]ecret|[Aa]pi[_-]?[Kk]ey|[Aa]ccess[_-]?[Kk]ey|[Aa]uth[_-]?[Tt]oken|[Tt]oken|[Ss]ession[_-]?[Tt]oken|[Cc]lient[_-]?[Ss]ecret|[Pp]rivate[_-]?[Kk]ey|[Cc]redential)["']?[[:space:]]*[:=][[:space:]]*["'][^"']{12,}["']|[-[:alnum:]:=_+.~]+["'][^"']{12,}["']
assignment-env|^[[:space:]]*(export[[:space:]]+)?[A-Za-z0-9_]*(PASSWORD|PASSWD|PASSPHRASE|SECRET|TOKEN|API_?KEY|ACCESS_KEY|CREDENTIAL)[A-Za-z0-9_]*[[:space:]]*=[[:space:]]*[^[:space:]]{12,}|=[[:space:]]*([^[:space:]]{12,})$
xml-token-value|>[A-Za-z0-9+/=_-]{24,}<|-
high-entropy|[A-Za-z0-9+/]{40,}={0,2}|-
EOF

if [ "$LIST_RULES" = 1 ]; then
  echo "path rules (${#PATH_RULE_NAMES[@]}):"
  for i in "${!PATH_RULE_NAMES[@]}"; do printf '  %-18s %s\n' "${PATH_RULE_NAMES[$i]}" "${PATH_RULE_RX[$i]}"; done
  echo "content rules (${#CONTENT_RULE_NAMES[@]}):"
  for i in "${!CONTENT_RULE_NAMES[@]}"; do printf '  %-18s %s\n' "${CONTENT_RULE_NAMES[$i]}" "${CONTENT_RULE_RX[$i]}"; done
  exit 0
fi

# ---------------------------------------------------------------------------
# self-check: the controls that make this guard worth trusting.
#   Positive controls: planted credentials must be caught, in every mode.
#   Negative controls: clean code (long hashes, URLs, env-var indirection,
#   documented placeholders) must NOT be caught, and a non-repo must report 2.
#   If any control fails the guard is broken, so the guard fails closed.
# ---------------------------------------------------------------------------
if [ "$SELF_CHECK" = 1 ]; then
  command -v git >/dev/null 2>&1 || die "git is required for --self-check"
  SELF="$SCRIPT_DIR/${BASH_SOURCE[0]##*/}"
  T="$(mktemp -d)" || die "cannot create a scratch directory"
  trap 'rm -rf "$T"' EXIT
  PASS=0; FAILN=0
  ok() { PASS=$((PASS+1)); printf '  PASS  %s\n' "$1"; }
  bad() { FAILN=$((FAILN+1)); printf '  FAIL  %s\n' "$1"; }
  OUT=""; RC=0
  run() { OUT="$("$SELF" --repo "$1" "${@:2}" 2>&1)"; RC=$?; }
  expect() { # expect <rc> <description> <name-that-must-appear-in-output|-> <output>
    local want="$1" desc="$2" needle="$3" out="$4" rc="$5"
    if [ "$rc" != "$want" ]; then bad "$desc (exit $rc, wanted $want)"; return; fi
    if [ "$needle" != "-" ] && ! printf '%s' "$out" | grep -q -- "$needle"; then
      bad "$desc (exit $rc ok, but '$needle' never appeared)"; return
    fi
    ok "$desc"
  }

  R="$T/repo"; mkdir -p "$R"
  git -C "$R" init -q -b main
  git -C "$R" config user.email selfcheck@example.invalid
  git -C "$R" config user.name "prepush self-check"

  # --- control 1: clean code must pass -------------------------------------
  mkdir -p "$R/src" "$R/docs"
  cat >"$R/src/config.py" <<'PY'
import os

TOKEN = os.environ["FIELDKIT_TOKEN"]          # indirection, not a value
RELAY = "https://relay.example.internal/v1/auth/token/refresh/abcdefghijklmnopqrstuvwxyz0123456789"
INDEX_SHA = "d41d8cd98f00b204e9800998ecf8427e0123456789abcdef0123456789abcdef"
PY
  cat >"$R/docs/notes.md" <<'MD'
Fixed in bc3dda7 - the call survives the home screen. See commit
a93a9df7310eb2a7f2e19f2f6b0d8c2e6a6f3b1d for the docs change.
MD
  git -C "$R" add -A && git -C "$R" commit -qm "clean"
  C1="$(git -C "$R" rev-parse HEAD)"
  run "$R" --all; expect 0 "clean tree passes (env indirection, URL, sha256, commit shas)" - "$OUT" "$RC"

  # --- control 2: planted credentials must be caught ------------------------
  # Every planted value is assembled at run time from inert fragments.  That is
  # not decoration: control 8 runs this very file through these same rules, so
  # the scanner's own source may not contain one matchable credential.
  mkdir -p "$R/src" "$R/keys" "$R/cfg" "$R/data/shared_prefs" "$R/notes" "$R/web"
  GH_A='ghp_';                  GH_B='A1b2C3d4E5f6G7h8I9j0'; GH_C='K1l2M3n4O5p6Q7r8'; GH="$GH_A$GH_B$GH_C"
  MT_A='EAAJq7Zk9Pv2Lm4Xn6R';   MT_B='t8Wy1Qd3Hf5Gs7Jb9Nc2Ve4Kg6Mz8Ta1'; MT="$MT_A$MT_B"
  AW_A='AKIA';                  AW_B='IOSFODNN7EXAMPLE';     AW="$AW_A$AW_B"
  TS_A='tskey-';                TS_B='auth-kQ2wE4rT6yU8iI0oP1aS3dF5gH7jK'; TS="$TS_A$TS_B"
  PK_A='-----BEGIN ';           PK_B='OPENSSH PRIVATE KEY-----'; PK="$PK_A$PK_B"
  B64_A='b3BlbnNzaC1rZXktdjEA'; B64_B='AABG5vbmUAAAAEbm9uZQ=='; B64="$B64_A$B64_B"
  JW_A='eyJhbGciOiJIUzI1NiJ9';  JW_B='eyJzdWIiOiIxMjM0NTY3ODkwIn0'; JW_C='dQw4w9WgXcQ0Kf3nQ'; JW="$JW_A.$JW_B.$JW_C"
  XV='AbCdEf0123456789GhIjKlMnOp'
  printf 'GITHUB_TOKEN = "%s"\n' "$GH" >"$R/src/leak.py"
  printf 'META_ACCESS_TOKEN=%s\n' "$MT" >"$R/meta.env"
  printf '%s\n%s\n' "$PK" "$B64" >"$R/keys/id_rsa"
  printf '{"type":"service_account","api_key":"%s","project":"fieldkit"}\n' "$AW" >"$R/cfg/credentials.json"
  printf '<?xml version="1.0"?>\n<map><string name="token">%s</string></map>\n' "$XV" >"$R/data/shared_prefs/fieldkit.xml"
  # the file name is part of the rule: token(s)/secret(s).txt, not api.txt
  printf 'the mobile relay uses %s\n' "$TS" >"$R/notes/token.txt"
  printf 'fetch(u,{headers:{Authorization:"Bearer %s"}})\n' "$JW" >"$R/web/relay.js"
  printf 'PK\x03\x04not-a-real-apk\x00\n' >"$R/app-release.apk"
  git -C "$R" add -A && git -C "$R" commit -qm "leak"
  C2="$(git -C "$R" rev-parse HEAD)"

  run "$R" --all
  expect 1 "planted credentials are caught on disk" REFUSED "$OUT" "$RC"
  LEAKOUT="$OUT"
  for needle in github-token env-like private-key-block ssh-private-key cloud-credentials \
                device-prefs-dump xml-token-value secrets-file build-artifact \
                meta-access-token tailscale-key bearer-literal jwt; do
    printf '%s' "$LEAKOUT" | grep -q -- "$needle" \
      && ok "rule fires on a planted leak: $needle" \
      || bad "rule missed a planted leak: $needle"
  done

  # the guard must not print the value it is reporting
  if printf '%s' "$LEAKOUT" | grep -qF -- "$GH"; then
    bad "the report printed the credential in clear text"
  elif printf '%s' "$LEAKOUT" | grep -qF -- "${GH:12}"; then
    bad "the report printed the tail of the credential"
  else
    ok "the report masks the value (name + 3-char prefix + length only)"
  fi

  # --- control 3: hook mode scans what is leaving the machine --------------
  OUT="$(printf 'refs/heads/main %s refs/heads/main %s\n' "$C2" "$C1" | "$SELF" --repo "$R" --hook 2>&1)"; RC=$?
  expect 1 "hook mode catches a leak inside the outgoing range" github-token "$OUT" "$RC"
  OUT="$(printf 'refs/heads/main %s refs/heads/main %s\n' "$C2" "$NULL_SHA" | "$SELF" --repo "$R" --hook 2>&1)"; RC=$?
  expect 1 "hook mode catches a leak on a brand-new branch" REFUSED "$OUT" "$RC"
  OUT="$(printf 'refs/heads/main %s refs/heads/main %s\nrefs/heads/topic %s refs/heads/topic %s\n' \
         "$C2" "$C1" "$NULL_SHA" "$C1" | "$SELF" --repo "$R" --hook 2>&1)"; RC=$?
  expect 1 "hook mode reads every ref on stdin, not just the first" github-token "$OUT" "$RC"

  # --- control 4: placeholders and env vars must not fail a push -----------
  git -C "$R" rm -q -r --cached . >/dev/null && rm -rf "$R/src/leak.py" "$R/meta.env" "$R/keys" "$R/cfg" "$R/data" "$R/notes" "$R/web" "$R/app-release.apk"
  cat >"$R/.env.example" <<'ENV'
FIELDKIT_TOKEN="${FIELDKIT_TOKEN}"
PASSWORD=your-password-here
API_KEY=<your-api-key>
META_ACCESS_TOKEN=changeme
ENV
  cat >"$R/src/sample.py" <<'PY'
password = "your-password-here"
api_key = "<your-key>"
secret = os.environ.get("SECRET", "unset")
PY
  git -C "$R" add -A && git -C "$R" commit -qm "clean again"
  C3="$(git -C "$R" rev-parse HEAD)"
  run "$R" --all; expect 0 "placeholders and env indirection are ignored, .env.example allowed" - "$OUT" "$RC"

  # --- control 5: scope - an untracked file only matters once it is added --
  printf 'the mobile relay uses %s\n' "$TS" >"$R/pending-leak.env"
  run "$R" --all; expect 1 "a stray uncommitted secret in the tree is reported by --all" tailscale-key "$OUT" "$RC"
  OUT="$(printf 'refs/heads/main %s refs/heads/main %s\n' "$C3" "$C2" | "$SELF" --repo "$R" --hook 2>&1)"; RC=$?
  expect 0 "hook mode ignores that stray file: it is not in the outgoing range" - "$OUT" "$RC"
  rm -f "$R/pending-leak.env"

  # --- control 6: a push that only removes files is not a leak -------------
  OUT="$(printf 'refs/heads/main %s refs/heads/gone %s\n' "$NULL_SHA" "$C1" | "$SELF" --repo "$R" --hook 2>&1)"; RC=$?
  expect 0 "a deletion-only push passes with an explicit reason" "deletion" "$OUT" "$RC"
  OUT="$(printf 'refs/heads/main %s refs/heads/main %s\n' "$C1" "$C2" | "$SELF" --repo "$R" --hook 2>&1)"; RC=$?
  expect 0 "a branch rewound so the leak files leave the tree is not a leak" "deletion" "$OUT" "$RC"
  run "$R" --range "$C1..$C1"
  expect 0 "a push with no file changes says so instead of failing" "no file changes" "$OUT" "$RC"

  # --- control 7: nothing to verify is a refusal, not a pass ---------------
  mkdir -p "$T/not-a-repo"
  run "$T/not-a-repo" --all; expect 2 "outside a repository it refuses to vouch (exit 2)" "CANNOT VERIFY" "$OUT" "$RC"

  # --- control 8: the ruleset, applied to this very repository -------------
  run "$REPO" --all
  if [ "$RC" = 0 ]; then ok "this repository's own tree passes its own guard"
  else bad "this repository's own tree trips the guard (exit $RC) - fix or whitelist it"; fi

  echo
  if [ "$FAILN" = 0 ]; then
    echo "SELF-CHECK OK  $PASS controls passed - the guard fires on planted leaks and spares clean code."
    exit 0
  fi
  echo "SELF-CHECK FAILED  $FAILN of $((PASS+FAILN)) controls failed - refusing to vouch for a push." >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------

# A value that is plainly a placeholder must not fail a push. Whole-value match
# only: a real token that merely contains the word "test" is still a real token.
is_placeholder() {
  local v="$1" low="${1,,}"
  case "$v" in
    *'$'*|*'<'*|*'{'*|*'}'*|*'...'*|*'***'*) return 0 ;;
  esac
  [[ "$low" =~ ^(your|my|the|some|a)?_?(password|passwd|passphrase|secret|token|api_?key|access_?key|auth_?token|session_?token|client_?secret|private_?key|credential|credentials|redacted|masked|placeholder|changeme|change_me|example|sample|dummy|fake|test|todo|none|null|nil|unset|missing|value|string|str|key|xxx+|abc123|foo|bar|baz)(_here|_placeholder|_goes_here|_env|_from_env|[0-9]{0,2})?$ ]] && return 0
  [[ "$low" =~ ^[a-z][a-z0-9_]*$ && ${#low} -lt 24 ]] && return 0   # a bare identifier, not a secret
  return 1
}

mask() {
  local v="$1" n=${#1}
  if [ "$n" -le 3 ]; then printf '%s… (%d chars)' "$v" "$n"
  else printf '%s… (%d chars)' "${v:0:3}" "$n"; fi
}

FILES_SEEN=0
FILES_SKIPPED_BINARY=0
FILES_SKIPPED_LARGE=0
ABSENT=0
BLOBS=0
PLACEHOLDERS=0
EMPTY_OK=0        # 1 = a diff-based mode where 'no file changes' is a true answer
FINDINGS=()

TMPDIR_SCAN="$(mktemp -d)" || die "cannot create a scratch directory"
cleanup() { rm -rf "$TMPDIR_SCAN"; }
trap cleanup EXIT

# path rules
check_path() {
  local p="$1" i
  case "$p" in *.example|*.sample|*.template|*.dist) return ;; esac
  for i in "${!PATH_RULE_NAMES[@]}"; do
    if printf '%s\n' "$p" | grep -qE -e "${PATH_RULE_RX[$i]}"; then
      FINDINGS+=("path|$p|${PATH_RULE_NAMES[$i]}|")
    fi
  done
}

# content rules over a file already materialised at $1, display path $2
check_content() {
  local tmp="$1" disp="$2" i hit ln matched val shown
  case "$disp" in
    *.jar|*.apk|*.aab|*.idsig|*.png|*.jpg|*.jpeg|*.gif|*.webp|*.ico|*.wav|*.mp3|*.mp4|*.so|*.dex|*.class|*.aar|*.zip|*.gz|*.ttf|*.woff|*.woff2|*.pdf)
      FILES_SKIPPED_BINARY=$((FILES_SKIPPED_BINARY+1)); return ;;
  esac
  if ! LC_ALL=C grep -qI . "$tmp" 2>/dev/null; then
    FILES_SKIPPED_BINARY=$((FILES_SKIPPED_BINARY+1)); return
  fi
  for i in "${!CONTENT_RULE_NAMES[@]}"; do
    local rx="${CONTENT_RULE_RX[$i]}" vrx="${CONTENT_RULE_VAL[$i]}" hits=0
    while IFS= read -r hit; do
      [ -z "$hit" ] && continue
      ln="${hit%%:*}"; matched="${hit#*:}"
      if [ "$vrx" = "-" ]; then
        val="$(printf '%s\n' "$matched" | grep -oE -e "$rx" | head -1)"
      else
        val="$(printf '%s\n' "$matched" | grep -oE -e "$vrx" | head -1)"
      fi
      [ -z "$val" ] && val="$matched"
      # high-entropy needs more evidence before it may fail a push
      if [ "${CONTENT_RULE_NAMES[$i]}" = high-entropy ]; then
        case "$val" in
          *[a-z]*) ;; *) continue ;; esac
        case "$val" in
          *[A-Z]*) ;; *) continue ;; esac
        case "$val" in
          *[0-9]*) ;; *) continue ;; esac
        printf '%s' "$val" | grep -qE '^[0-9a-fA-F]+$' && continue
        printf '%s' "$matched" | grep -qiE 'sha(256|1|512)|md5|checksum|digest|signature|BEGIN CERTIFICATE|data:image|base64,|integrity=|utf-8' && continue
        printf '%s' "$matched" | grep -qE 'https?://' && continue
        [ ${#val} -lt 48 ] && ! printf '%s' "$matched" | grep -qiE 'token|secret|password|passwd|api.?key|auth|credential|bearer' && continue
      fi
      case "$val" in ['"'\<\>\{\}\$]*) val="${val:1:${#val}-2}" ;; esac
      if is_placeholder "$val"; then PLACEHOLDERS=$((PLACEHOLDERS+1)); continue; fi
      shown="$(mask "$val")"
      FINDINGS+=("content|$disp:$ln|${CONTENT_RULE_NAMES[$i]}|$shown")
      hits=$((hits+1))
      [ "$hits" -ge "$MAX_PER_RULE" ] && break
    done < <(grep -nE -e "$rx" -- "$tmp" 2>/dev/null | head -"$MAX_PER_RULE")
  done
}

# materialise a file: $1 display path, $2 "disk" or a revision
scan_one() {
  local disp="$1" src="$2" tmp size
  FILES_SEEN=$((FILES_SEEN+1))
  tmp="$TMPDIR_SCAN/blob"
  # A path rule may only fire on a file that is really there: `git diff` lists
  # deletions too, and refusing a push because a secret file was *removed* would
  # teach everyone to pass --no-verify.
  case "$src" in
    disk) [ -f "$REPO/$disp" ] || { ABSENT=$((ABSENT+1)); return; }
          cat -- "$REPO/$disp" >"$tmp" 2>/dev/null || : >"$tmp" ;;
    *)    git -C "$REPO" cat-file -e "$src:$disp" 2>/dev/null || { ABSENT=$((ABSENT+1)); return; }
          git -C "$REPO" show "$src:$disp" >"$tmp" 2>/dev/null || : >"$tmp" ;;
  esac
  check_path "$disp"
  size=$(wc -c <"$tmp")
  if [ "$size" -gt "$SIZE_CAP" ]; then FILES_SKIPPED_LARGE=$((FILES_SKIPPED_LARGE+1)); return; fi
  BLOBS=$((BLOBS+1))
  check_content "$tmp" "$disp"
}

# ---------------------------------------------------------------------------
# collect what this run should read
# ---------------------------------------------------------------------------
declare -A SEEN=()
add_disk() { local p="$1"; [ -n "${SEEN[disk|$p]:-}" ] && return; SEEN[disk|$p]=1; scan_one "$p" disk; }
add_rev()  { local p="$1" r="$2"; local k="$r|$p"; [ -n "${SEEN[$k]:-}" ] && return; SEEN[$k]=1; scan_one "$p" "$r"; }

scan_all() {
  local r="${1:-HEAD}" p
  while IFS= read -r p; do [ -n "$p" ] && add_rev "$p" "$r"; done < <(git -C "$REPO" ls-tree -r --name-only "$r" 2>/dev/null)
}

case "$MODE" in
  all)
    while IFS= read -r p; do [ -n "$p" ] && add_disk "$p"; done < <(git -C "$REPO" ls-files 2>/dev/null)
    while IFS= read -r p; do [ -n "$p" ] && add_disk "$p"; done < <(git -C "$REPO" ls-files --others --exclude-standard 2>/dev/null)
    LABEL="tracked + untracked files on disk"
    ;;
  range)
    case "$RANGE" in *..*) ;; *) die "--range wants A..B" ;; esac
    HEADREV="${RANGE##*..}"
    while IFS= read -r p; do [ -n "$p" ] && add_rev "$p" "$HEADREV"; done < <(git -C "$REPO" diff --name-only "$RANGE" 2>/dev/null)
    LABEL="commits $RANGE"
    EMPTY_OK=1
    ;;
  hook)
    NREF=0; NDEL=0; NNEW=0; EMPTY_OK=1
    # Read every ref line before running anything: a helper that reads stdin
    # must never share the pipe git fed the ref list on.
    REFS=()
    while IFS= read -r _refline; do [ -n "$_refline" ] && REFS+=("$_refline"); done
    for _refline in ${REFS[@]+"${REFS[@]}"}; do
      local_sha=""; remote_sha=""
      read -r _lref local_sha _rref remote_sha <<<"$_refline"
      local_sha="${local_sha:-}"; remote_sha="${remote_sha:-}"
      [ -z "$local_sha" ] && continue
      NREF=$((NREF+1))
      if [ "$local_sha" = "$NULL_SHA" ]; then NDEL=$((NDEL+1)); continue; fi
      if [ "$remote_sha" = "$NULL_SHA" ] || ! git -C "$REPO" cat-file -e "$remote_sha^{commit}" 2>/dev/null; then
        NNEW=$((NNEW+1)); scan_all "$local_sha"
      else
        while IFS= read -r p; do [ -n "$p" ] && add_rev "$p" "$local_sha"; done < <(git -C "$REPO" diff --name-only "$remote_sha..$local_sha" 2>/dev/null)
      fi
    done
    LABEL="outgoing refs ($NREF refs, $NNEW new branch(es), $NDEL deletion(s))"
    if [ "$NREF" -gt 0 ] && [ "$NDEL" = "$NREF" ]; then
      echo "OK  nothing is entering the repository: every ref in this push is a deletion."
      echo "    ($NREF deletion-only refs, 0 files to scan)"
      exit 0
    fi
    ;;
  auto)
    UP="$(git -C "$REPO" rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null)"
    if [ -n "$UP" ]; then
      HEADREV="$(git -C "$REPO" rev-parse HEAD 2>/dev/null)"
      while IFS= read -r p; do [ -n "$p" ] && add_rev "$p" "$HEADREV"; done < <(git -C "$REPO" diff --name-only "$UP..HEAD" 2>/dev/null)
      LABEL="outgoing commits vs $UP"
      EMPTY_OK=1
    else
      while IFS= read -r p; do [ -n "$p" ] && add_disk "$p"; done < <(git -C "$REPO" ls-files 2>/dev/null)
      LABEL="tracked files on disk (no upstream to compare)"
    fi
    ;;
esac

# ---------------------------------------------------------------------------
# report
# ---------------------------------------------------------------------------
RULES=$(( ${#PATH_RULE_NAMES[@]} + ${#CONTENT_RULE_NAMES[@]} ))
NFOUND=${#FINDINGS[@]}
echo "prepush secret scan  |  $RULES rules  |  $FILES_SEEN files, $BLOBS read, $LABEL"
[ "$FILES_SKIPPED_BINARY" -gt 0 ] && echo "  skipped $FILES_SKIPPED_BINARY binary/asset files, $FILES_SKIPPED_LARGE over the size cap"
[ "$ABSENT" -gt 0 ] && echo "  $ABSENT path(s) were deletions - absent from the pushed tree, so not scanned"
[ "$PLACEHOLDERS" -gt 0 ] && echo "  $PLACEHOLDERS placeholder-looking values ignored (documented examples, env vars)"

if [ "$FILES_SEEN" = 0 ]; then
  if [ "$EMPTY_OK" = 1 ]; then
    echo "OK  nothing is entering the repository: this push carries no file changes."
    echo "    ($LABEL)"
    exit 0
  fi
  echo "CANNOT VERIFY  no files were readable in $REPO - this push is unguarded (exit 2)." >&2
  exit 2
fi

if [ "$BLOBS" = 0 ]; then
  echo "OK  nothing is entering the repository: every changed path was a deletion."
  echo "    ($FILES_SEEN path(s) listed, 0 present in the pushed tree)"
  exit 0
fi

if [ "$NFOUND" = 0 ]; then
  echo "OK  nothing sensitive in the $BLOBS files that were read. Push may continue."
  exit 0
fi

echo
echo "REFUSED  $NFOUND finding(s) - do not push this to a public repository:"
for f in "${FINDINGS[@]}"; do
  IFS='|' read -r kind where rule shown <<<"$f"
  if [ "$kind" = path ]; then printf '  %-8s %-46s %s\n' "$kind" "$where" "$rule"
  else printf '  %-8s %-46s %-20s %s\n' "$kind" "$where" "$rule" "$shown"; fi
done
echo
echo "Values are masked on purpose. Remove the file or the value (and rotate it - a"
echo "value that reached a commit message, a branch, or a remote is already burned)."
echo "Rotate, then re-run:  git push   (or  git commit --amend  for an unpushed commit)"
exit 1
