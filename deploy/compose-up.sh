#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SCRIPT_DIR"

get_env_file_value() {
  key="$1"
  if [ ! -f .env ]; then
    return 0
  fi

  awk -v key="$key" '
    /^[[:space:]]*#/ || /^[[:space:]]*$/ { next }
    {
      line = $0
      sub(/^[[:space:]]*/, "", line)
      if (index(line, key "=") == 1) {
        sub(/^[^=]*=/, "", line)
        sub(/^[[:space:]]*/, "", line)
        sub(/[[:space:]]*$/, "", line)
        if ((substr(line, 1, 1) == "\"" && substr(line, length(line), 1) == "\"") ||
            (substr(line, 1, 1) == "\047" && substr(line, length(line), 1) == "\047")) {
          line = substr(line, 2, length(line) - 2)
        }
        print line
        exit
      }
    }
  ' .env
}

get_config_value() {
  key="$1"
  value=$(printenv "$key" 2>/dev/null || true)
  if [ -n "$value" ]; then
    printf '%s' "$value"
  else
    get_env_file_value "$key"
  fi
}

prepare_artifact() {
  name="$1"
  source_value="$2"
  target_dir="$3"
  extension="$4"
  build_variable="$5"

  uuid=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen)
  target_file="${name}-${uuid}.${extension}"
  target_path="${target_dir}/${target_file}"

  mkdir -p "$target_dir"
  rm -f "${target_dir}"/*

  case "$source_value" in
    http://*|https://*)
      echo "Downloading ${name} artifact..."
      curl --fail --location --silent --show-error "$source_value" --output "$target_path"
      ;;
    *)
      if [ ! -f "$source_value" ]; then
        echo "${name} artifact not found: ${source_value}" >&2
        exit 1
      fi
      echo "Copying local ${name} artifact..."
      cp "$source_value" "$target_path"
      ;;
  esac

  export "${build_variable}=artifacts/${target_file}"
  echo "Prepared ${name}: artifacts/${target_file}"
}

app_source=$(get_config_value NIP_APP_JAR_SOURCE)
front_source=$(get_config_value NIP_FRONT_DIST_SOURCE)

if [ -z "$app_source" ]; then
  echo "NIP_APP_JAR_SOURCE is required. Set it to a local jar path or an http(s) URL." >&2
  exit 1
fi

if [ -z "$front_source" ]; then
  echo "NIP_FRONT_DIST_SOURCE is required. Set it to a local dist.zip path or an http(s) URL." >&2
  exit 1
fi

prepare_artifact "nip-app-boot" "$app_source" "./backend/artifacts" "jar" "NIP_APP_JAR_FILE"
prepare_artifact "dist" "$front_source" "./frontend/artifacts" "zip" "NIP_FRONT_DIST_FILE"

compose_options=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --profile)
      if [ "$#" -lt 2 ]; then
        echo "--profile requires a profile name" >&2
        exit 1
      fi
      compose_options="${compose_options} --profile $2"
      shift 2
      ;;
    --)
      shift
      break
      ;;
    *)
      break
      ;;
  esac
done

# shellcheck disable=SC2086
exec docker compose $compose_options up -d --build --force-recreate "$@"
