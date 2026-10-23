#!/usr/bin/env bash
set -u

PREFIX=/data/data/com.termux/files/usr
PY="$PREFIX/bin/python"
MC="$HOME/mtkclient"
SELF="$HOME/a55.sh"

banner() {
  echo
  echo "=================================================="
  echo "  TATSIE A55 WIPER  (mtkclient / Helio G35 lane)"
  echo "=================================================="
  echo
  echo "$1"
  echo
}

mrun() {
  su -c "LD_LIBRARY_PATH=$PREFIX/lib $PY $*"
}

pkg_install() {
  banner "STEP 1 - Termux packages"
  pkg update -y || true
  pkg install -y termux-tools python-pip python git libusb clang rust cmake make openssl pkg-config || {
    echo "pkg install failed - rerun:  bash $SELF install"
    exit 1
  }
  echo
  echo "[OK] termux packages installed"
}

repo_fetch() {
  banner "STEP 2 - fetching bkerler/mtkclient"
  if [ -d "$MC/.git" ]; then
    (cd "$MC" && git pull) || true
  else
    git clone https://github.com/bkerler/mtkclient "$MC" || {
      echo "clone failed - check internet, rerun:  bash $SELF install"
      exit 1
    }
  fi
  echo
  echo "[OK] mtkclient ready at $MC"
}

pip_install() {
  banner "STEP 3 - python deps (phone compiles C, 10-20 min, do not close)"
  cd "$MC" || exit 1
  if pip install -r requirements.txt; then
    echo
    echo "[OK] pip deps installed"
    return
  fi
  echo
  echo "strict install hit - retrying problem packages"
  pip install capstone keystone-engine --upgrade \
    || pip install --break-system-packages capstone keystone-engine
  pip install -r requirements.txt \
    || pip install --break-system-packages -r requirements.txt
  echo
  echo "[OK] pip deps installed (retry path)"
}

sanity() {
  banner "STEP 4 - sanity: mtk help"
  cd "$MC" || exit 1
  "$PY" mtk help | head -40
  echo
  echo "[CHECK] if you see the command list above = install GOOD"
}

install_all() {
  banner "FULL INSTALL STARTING"
  pkg_install
  repo_fetch
  pip_install
  sanity
  echo
  echo "INSTALL COMPLETE. next steps (read from your other phone):"
  echo "  bash $SELF handshake     <---- while battery connector is OFF"
  echo "  bash $SELF wipe"
  echo
}

handshake() {
  banner "BROM HANDSHAKE"
  echo "PREP CHECKLIST:"
  echo "  1) A55 back cover OFF"
  echo "  2) BATTERY CONNECTOR OFF  (bare power boots BROM, no buttons needed)"
  echo "  3) good data cable ready"
  echo "  4) A55 must show NO screen (fully off)"
  echo
  echo ">>> starting payload - PLUG THE CABLE the moment it waits for device"
  echo
  sleep 3
  mrun "$MC/mtk" payload
  echo
  echo "[CHECK] Handshake accepted = next:  su, then:  bash $SELF wipe"
}

wipe() {
  banner "ERASE userdata + frp  (pattern + FRP metadata kill)"
  echo "battery connector stays OFF. plug A55 each time it waits."
  echo
  echo "ERASE 1/2: userdata"
  echo
  mrun "$MC/mtk" e userdata || {
    echo "userdata erase failed - paste the log to Tatskie"
    exit 1
  }
  echo
  echo "ERASE 2/2: frp"
  echo
  mrun "$MC/mtk" e frp || {
    echo "frp erase failed - paste the log to Tatskie"
    exit 1
  }
  echo
  echo "resetting device out of BROM"
  mrun "$MC/mtk" reset || true
  echo
  echo "=================================================="
  echo "  DONE - reconnect battery connector"
  echo "  boot the A55 = fresh setup screen"
  echo "  set up with the son's new Google account"
  echo "=================================================="
}

case "${1:-}" in
  install)  install_all ;;
  sanity)   sanity ;;
  handshake) handshake ;;
  wipe)     wipe ;;
  *)
    banner "usage: bash $SELF {install | sanity | handshake | wipe}"
    echo "run order:  install  ->  handshake  ->  wipe"
    echo "handshake and wipe need ROOT: type su first, then bash $SELF handshake"
    ;;
esac
