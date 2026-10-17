#!/usr/bin/env bash
set -euo pipefail
: "${JAVA_HOME:?Set JDK 21}"
: "${ARTE_SEARCH_IT_ES_URL:?Set isolated ES URL}"
: "${ARTE_SEARCH_IT_REDIS_URL:?Set isolated Redis URL}"
: "${ARTE_SEARCH_S2_IT_MYSQL_URL:?Set isolated localhost MySQL admin URL ending /mysql}"
: "${ARTE_SEARCH_S2_IT_MYSQL_USER:?Set isolated MySQL user}"
: "${ARTE_SEARCH_S2_IT_MYSQL_PASSWORD:?Set isolated MySQL password}"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
backend_dir="$(cd -- "$script_dir/../.." && pwd)"
run_tests() {
  local selected="$1"
  shift
  mvn -f "$backend_dir/pom.xml" -pl arte-ai -am \
    -Dtest="$selected" -Dsurefire.failIfNoSpecifiedTests=false \
    -Dlog4j.configurationFile="$script_dir/../src/test/resources/search-s1-log4j2.xml" "$@" test
}
# 先确认真实 IK、v2 DSL／高亮与契约，再运行较长的扫描和浏览器验收。
run_tests SearchContractsTest,SearchConfigurationTest,SearchInfrastructureIntegrationTest "$@"
run_tests SearchSyncWorkerTest,SearchQualityAcceptanceTest,SearchSyncInfrastructureIntegrationTest,ConversationSearchInfrastructureIntegrationTest,SearchRedisSessionIntegrationTest,ConversationSearchHttpTest,SearchInvocationVisibilityTest "$@"
