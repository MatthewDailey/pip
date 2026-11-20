# Environment for this repo's tools. Source it from your shell (POSIX sh, bash, zsh):
#
#   . scripts/env.sh
#
# Package scripts source it too, so turbo tasks work without any shell setup. Values already set win; .env at
# the repo root (see .env.example) is loaded first when present.

_sp_root="${SHADY_PIP_ROOT:-}"
if [ -z "$_sp_root" ]; then
  if [ -n "${BASH_SOURCE:-}" ]; then
    _sp_root="$(cd "$(dirname "${BASH_SOURCE}")/.." && pwd)"
  elif [ -n "${ZSH_VERSION:-}" ]; then
    # shellcheck disable=SC2296
    _sp_root="$(cd "$(dirname "${(%):-%x}")/.." && pwd)"
  else
    # Plain sh (package scripts): walk up from the working directory to pnpm-workspace.yaml.
    _sp_root="$(pwd)"
    while [ "$_sp_root" != / ] && [ ! -f "$_sp_root/pnpm-workspace.yaml" ]; do _sp_root="$(dirname "$_sp_root")"; done
  fi
fi
SHADY_PIP_ROOT="$_sp_root"
export SHADY_PIP_ROOT

if [ -f "$SHADY_PIP_ROOT/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$SHADY_PIP_ROOT/.env"
  set +a
fi

if [ -z "${JAVA_HOME:-}" ]; then
  for _sp_jdk in /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; do
    [ -d "$_sp_jdk" ] && JAVA_HOME="$_sp_jdk" && break
  done
  [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ] && JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
fi
[ -n "${JAVA_HOME:-}" ] && export JAVA_HOME

: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
export ANDROID_HOME
: "${AVD_NAME:=Pip_API_37}"
export AVD_NAME

case ":$PATH:" in
  *":$ANDROID_HOME/platform-tools:"*) ;;
  *) PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH" ;;
esac
[ -n "${JAVA_HOME:-}" ] && case ":$PATH:" in *":$JAVA_HOME/bin:"*) ;; *) PATH="$JAVA_HOME/bin:$PATH" ;; esac
export PATH

# rivc's launcher rebuilds itself with this Gradle when its sources change.
: "${RIVC_GRADLE:=$SHADY_PIP_ROOT/packages/rivc/gradlew}"
export RIVC_GRADLE

unset _sp_root _sp_jdk
