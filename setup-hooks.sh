#!/usr/bin/env bash
# Configures git to use the project's version-controlled hooks in .githooks/
set -e

git config core.hooksPath .githooks
echo "✅ Git hooks configured → .githooks/"
echo "   post-commit: Domain Contract Hint active"
