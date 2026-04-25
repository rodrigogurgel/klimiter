#!/usr/bin/env bash
set -euo pipefail

OUTPUT_DIR="project-info"
OUTPUT_FILE="$OUTPUT_DIR/project-info.txt"

mkdir -p "$OUTPUT_DIR"

{
  echo "============================================================"
  echo "PROJECT INFO EXPORT"
  echo "Generated at: $(date -Iseconds)"
  echo "Current directory: $(pwd)"
  echo "============================================================"
  echo

  echo "============================================================"
  echo "1. DIRECTORY TREE"
  echo "============================================================"
  if command -v tree >/dev/null 2>&1; then
    tree -d -L 6 -I 'build|.gradle|.idea|out|node_modules|.git|.kotlin|target'
  else
    echo "Command 'tree' not found. Using find fallback:"
    find . \
      \( -path './build' \
      -o -path './.gradle' \
      -o -path './.idea' \
      -o -path './out' \
      -o -path './node_modules' \
      -o -path './.git' \
      -o -path './.kotlin' \
      -o -path './target' \) -prune \
      -o -type d -print | sort
  fi
  echo

  echo "============================================================"
  echo "2. GRADLE PROJECTS"
  echo "============================================================"
  if [ -x "./gradlew" ]; then
    ./gradlew projects --console=plain
  else
    echo "./gradlew not found or not executable"
  fi
  echo

  echo "============================================================"
  echo "3. SETTINGS GRADLE"
  echo "============================================================"
  if [ -f "settings.gradle.kts" ]; then
    echo "--- settings.gradle.kts ---"
    cat settings.gradle.kts
  elif [ -f "settings.gradle" ]; then
    echo "--- settings.gradle ---"
    cat settings.gradle
  else
    echo "No settings.gradle(.kts) found"
  fi
  echo

  echo "============================================================"
  echo "4. ROOT BUILD GRADLE"
  echo "============================================================"
  if [ -f "build.gradle.kts" ]; then
    echo "--- build.gradle.kts ---"
    cat build.gradle.kts
  elif [ -f "build.gradle" ]; then
    echo "--- build.gradle ---"
    cat build.gradle
  else
    echo "No root build.gradle(.kts) found"
  fi
  echo

  echo "============================================================"
  echo "5. MODULE BUILD FILES"
  echo "============================================================"
  find . \
    \( -path './build' \
    -o -path './.gradle' \
    -o -path './.idea' \
    -o -path './out' \
    -o -path './node_modules' \
    -o -path './.git' \
    -o -path './.kotlin' \
    -o -path './target' \) -prune \
    -o \( -name 'build.gradle.kts' -o -name 'build.gradle' \) \
    -type f -print | sort | while read -r file; do
      if [ "$file" != "./build.gradle.kts" ] && [ "$file" != "./build.gradle" ]; then
        echo
        echo "--- $file ---"
        cat "$file"
      fi
    done
  echo

  echo "============================================================"
  echo "6. GRADLE VERSION CATALOGS"
  echo "============================================================"
  if [ -d "gradle" ]; then
    find gradle \
      -name '*.toml' \
      -type f \
      -print | sort | while read -r file; do
        echo
        echo "--- $file ---"
        cat "$file"
      done
  else
    echo "No gradle directory found"
  fi
  echo

  echo "============================================================"
  echo "7. KOTLIN FILE LIST"
  echo "============================================================"
  find . \
    \( -path './build' \
    -o -path './.gradle' \
    -o -path './.idea' \
    -o -path './out' \
    -o -path './node_modules' \
    -o -path './.git' \
    -o -path './.kotlin' \
    -o -path './target' \) -prune \
    -o -name '*.kt' \
    -type f -print | sort
  echo

  echo "============================================================"
  echo "8. KOTLIN PACKAGE DECLARATIONS"
  echo "============================================================"
  find . \
    \( -path './build' \
    -o -path './.gradle' \
    -o -path './.idea' \
    -o -path './out' \
    -o -path './node_modules' \
    -o -path './.git' \
    -o -path './.kotlin' \
    -o -path './target' \) -prune \
    -o -name '*.kt' \
    -type f -print | sort | while read -r file; do
      package_line="$(grep -m 1 '^package ' "$file" || true)"
      echo "$file :: ${package_line:-NO_PACKAGE}"
    done
  echo

  echo "============================================================"
  echo "9. KOTLIN IMPORT SUMMARY"
  echo "============================================================"
  find . \
    \( -path './build' \
    -o -path './.gradle' \
    -o -path './.idea' \
    -o -path './out' \
    -o -path './node_modules' \
    -o -path './.git' \
    -o -path './.kotlin' \
    -o -path './target' \) -prune \
    -o -name '*.kt' \
    -type f -print | sort | while read -r file; do
      echo
      echo "--- $file ---"
      grep '^import ' "$file" || true
    done
  echo

  echo "============================================================"
  echo "10. ARCHITECTURE-RELEVANT FILE CONTENTS"
  echo "============================================================"
  find . \
    \( -path './build' \
    -o -path './.gradle' \
    -o -path './.idea' \
    -o -path './out' \
    -o -path './node_modules' \
    -o -path './.git' \
    -o -path './.kotlin' \
    -o -path './target' \) -prune \
    -o -name '*.kt' \
    -type f -print | sort | while read -r file; do
      case "$file" in
        *"/api/"*|*"/spi/"*|*"/internal/"*|*"Factory.kt"|*"Configuration.kt"|*"Config.kt"|*"Application.kt")
          echo
          echo "--- $file ---"
          cat "$file"
          ;;
      esac
    done
  echo

} > "$OUTPUT_FILE"

echo "Generated: $OUTPUT_FILE"
echo
echo "You can send me this file content or attach the file:"
echo "$OUTPUT_FILE"