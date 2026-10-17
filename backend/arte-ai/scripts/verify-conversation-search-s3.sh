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
mvn -f "$backend_dir/pom.xml" -pl arte-ai -am \
  -Dtest=ConversationSearchServiceTest,ConversationSearchReadPersistenceTest,ConversationSearchHttpTest,SearchInvocationVisibilityTest,SearchRedisSessionIntegrationTest,ConversationSearchInfrastructureIntegrationTest,SearchConfigurationTest,SearchContractsTest,SearchSyncWorkerTest,ExecutionSqlSessionFactoryTest,MybatisExecutionPersistenceTest,ConversationHttpIntegrationTest,ConfigurationAssemblyTest,ExecutionAssemblyTest,ReadOperationSnapshotTest,InvocationHttpIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dlog4j.configurationFile="$script_dir/../src/test/resources/search-s1-log4j2.xml" "$@" test
