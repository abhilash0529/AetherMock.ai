#!/usr/bin/env bash

# ==============================================================================
# AetherMock.ai - Startup & Launch Script (Java 25 + Spring Boot 4.1.0)
# ==============================================================================

set -e

# ANSI Color Codes for Stylish Terminal Output
BOLD="\033[1m"
CYAN="\033[36m"
GREEN="\033[32m"
YELLOW="\033[33m"
RED="\033[31m"
RESET="\033[0m"

echo -e "${CYAN}${BOLD}"
echo "  █████╗ ███████╗████████╗██╗  ██╗███████╗██████╗ ███╗   ███╗██████╗  ██████╗██╗██╗"
echo " ██╔══██╗██╔════╝╚══██╔══╝██║  ██║██╔════╝██╔══██╗████╗ ████║██╔══██╗██╔════╝██║██║"
echo " ███████║█████╗     ██║   ███████║█████╗  ██████╔╝██╔████╔██║██║  ██║██║     ██║██║"
echo " ██╔══██║██╔══╝     ██║   ██╔══██║██╔══╝  ██╔══██╗██║╚██╔╝██║██║  ██║██║     ██║██║"
echo " ██║  ██║███████╗   ██║   ██║  ██║███████╗██║  ██║██║ ╚═╝ ██║██████╔╝╚██████╗██║██║"
echo " ╚═╝  ╚═╝╚══════╝   ╚═╝   ╚═╝  ╚═╝╚══════╝╚═╝  ╚═╝╚═╝     ╚═╝╚═════╝  ╚═════╝╚═╝╚═╝"
echo -e "                 AI-Native API Virtualization Engine v1.0${RESET}\n"

# 1. Environment Variable Validation
echo -e "${BOLD}[1/4] Checking Environment Configurations...${RESET}"

if [ -z "$SPRING_AI_OPENAI_API_KEY" ]; then
    echo -e "${YELLOW}WARNING: 'SPRING_AI_OPENAI_API_KEY' is not set in environment.${RESET}"
    read -rp "Enter your OpenAI API Key (or press Enter to use mock key): " user_key
    if [ -n "$user_key" ]; then
        export SPRING_AI_OPENAI_API_KEY="$user_key"
        echo -e "${GREEN}✓ API Key set for current session.${RESET}"
    else
        export SPRING_AI_OPENAI_API_KEY="mock-key-for-dev"
        echo -e "${YELLOW}! Using default fallback key: 'mock-key-for-dev'${RESET}"
    fi
else
    echo -e "${GREEN}✓ OpenAI API Key detected.${RESET}"
fi

# 2. Check Java Version
echo -e "\n${BOLD}[2/4] Validating Java Runtime Environment...${RESET}"
if command -v java &> /dev/null; then
    JAVA_VER=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
    echo -e "Detected Java Version: ${CYAN}${JAVA_VER}${RESET}"
    if [ "$JAVA_VER" -lt 25 ]; then
        echo -e "${YELLOW}Note: AetherMock.ai target runtime is Java 25. Running on Java $JAVA_VER (Preview features enabled).${RESET}"
    fi
else
    echo -e "${RED}ERROR: Java installation not found. Please install JDK 25.${RESET}"
    exit 1
fi

# 3. Ensure Mock Data Directories Exist
echo -e "\n${BOLD}[3/4] Initializing Storage Directories...${RESET}"
MOCK_DIR="src/main/resources/mock-data"
if [ ! -d "$MOCK_DIR" ]; then
    echo -e "Creating missing mock data directory: ${CYAN}$MOCK_DIR${RESET}"
    mkdir -p "$MOCK_DIR/payment-service"
fi
echo -e "${GREEN}✓ Mock Data Directories ready.${RESET}"

# 4. Build and Run Application
echo -e "\n${BOLD}[4/4] Starting AetherMock.ai Core Services...${RESET}"
echo -e "Main Application: ${CYAN}http://localhost:8080${RESET}"
echo -e "WireMock Server:   ${CYAN}http://localhost:8089${RESET}"
echo -e "----------------------------------------------------------------------"

# Enable Java 25 preview features and Virtual Threads
export MAVEN_OPTS="--enable-preview"

# Execute Spring Boot Maven Plugin
./mvnw spring-boot:run || mvn spring-boot:run
