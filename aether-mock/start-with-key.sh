#!/usr/bin/env bash

# ==============================================================================
# AetherMock.ai - Quick Launcher with OpenAI API Key
# ==============================================================================

set -e

# ANSI Color Codes
CYAN="\033[36m"
GREEN="\033[32m"
YELLOW="\033[33m"
RED="\033[31m"
BOLD="\033[1m"
RESET="\033[0m"

# 1. Ensure run.sh exists and is executable
if [ ! -f "./run.sh" ]; then
    echo -e "${RED}ERROR: 'run.sh' not found in current directory.${RESET}"
    exit 1
fi
chmod +x ./run.sh

# 2. Check if API Key was passed as a command-line argument ($1)
if [ -n "$1" ]; then
    API_KEY="$1"
else
    # Prompt user securely if no key was passed
    echo -e "${CYAN}${BOLD}=== AetherMock.ai Key Launcher ===${RESET}"
    read -rsp "Enter your OpenAI API Key (sk-...): " API_KEY
    echo ""
fi

# 3. Validate input
if [ -z "$API_KEY" ]; then
    echo -e "${RED}ERROR: No API key provided. Exiting.${RESET}"
    exit 1
fi

# 4. Export key and execute run.sh
echo -e "${GREEN}✓ Exporting SPRING_AI_OPENAI_API_KEY and launching AetherMock.ai...${RESET}\n"
SPRING_AI_OPENAI_API_KEY="$API_KEY" ./run.sh
