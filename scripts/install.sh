#!/usr/bin/env sh
# Installs YuForge for the current user and exposes the `yuforge` command.
set -eu

jar_path="${1:-$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)/target/yuforge-1.0-SNAPSHOT.jar}"
jar_url="${YUFORGE_JAR_URL:-}"
install_root="${XDG_DATA_HOME:-$HOME/.local/share}/yuforge"
bin_dir="$HOME/.local/bin"
target_jar="$install_root/yuforge.jar"
launcher="$bin_dir/yuforge"

# __YUFORGE_RELEASE_JAR_URL__
# Source-tree installer leaves the line above as a comment and installs a local Maven jar.
# The release workflow replaces that comment with an immutable release asset URL.

command -v java >/dev/null 2>&1 || { echo 'Java 17+ is required.' >&2; exit 1; }
mkdir -p "$install_root" "$bin_dir"

if [ -n "$jar_url" ]; then
  command -v curl >/dev/null 2>&1 || { echo 'curl is required when YUFORGE_JAR_URL is set.' >&2; exit 1; }
  curl --fail --location --output "$target_jar" "$jar_url"
else
  [ -f "$jar_path" ] || { echo "Jar not found: $jar_path. Run mvn package or set YUFORGE_JAR_URL." >&2; exit 1; }
  cp "$jar_path" "$target_jar"
fi

cat > "$launcher" <<EOF
#!/usr/bin/env sh
exec java -jar "$target_jar" "\$@"
EOF
chmod +x "$launcher"

case ":$PATH:" in
  *":$bin_dir:"*) ;;
  *)
    profile="$HOME/.profile"
    export_line='export PATH="$HOME/.local/bin:$PATH"'
    grep -F "$export_line" "$profile" >/dev/null 2>&1 || printf '\n# YuForge CLI\n%s\n' "$export_line" >> "$profile"
    echo "Added $bin_dir to PATH via $profile. Open a new terminal, then run: yuforge"
    ;;
esac

echo "YuForge installed to $install_root. Run: yuforge"
