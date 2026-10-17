#!/usr/bin/env bash
set -euo pipefail
: "${ARTE_SEARCH_IT_ES_URL:?Set the isolated verification ES endpoint}"
: "${ARTE_SEARCH_IT_REDIS_URL:?Set the isolated verification Redis endpoint}"
task_repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"
mvn "$@" -f "$task_repo_root/backend/pom.xml" -pl arte-ai -am \
  -Dtest=SearchContractsTest,SearchConfigurationTest,SearchInfrastructureIntegrationTest,ConversationHttpIntegrationTest,ConfigurationAssemblyTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  "-Dlog4j.configurationFile=$task_repo_root/backend/arte-ai/src/test/resources/search-s1-log4j2.xml" test
